package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.model.ExternalChangeCommand;
import org.gautelis.archimesh.service.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Three-way inbound reconciliation. Every model change is a durable ordinary accepted batch. */
@ApplicationScoped
public class ExternalChangeIngestService {
    @Inject IntegrationRuntime runtime;
    @Inject InboundAuthorization access;
    @Inject ArchimeshService models;
    @Inject CommitDeliveryService delivery;
    @Inject ObjectMapper json;
    @Inject CentralProposalGrant proposalGrants;
    private final ReentrantLock execution=new ReentrantLock();
    LongSupplier now=System::currentTimeMillis;

    public record Approval(boolean enabled,long expectedVersion,String targetInstanceId,String mappingVersion) { }
    public record BindingView(String elementId,String canonicalId,String resourceId,String state,String code,long verifiedRevision,int attempts,long nextAttemptAt) { }
    public record Status(long policyVersion,boolean enabled,String targetInstanceId,String mappingVersion,Map<String,Set<String>> fields,List<BindingView> bindings,String nextCursor) { }
    public record Inspection(String elementId,JsonNode base,JsonNode local,JsonNode remote,JsonNode observedConflict,long revision,String digest) { }
    public record Resolution(String elementId,String inspectionDigest) { }

    public List<String> targets(String model,AuthorizationSubject subject) {
        if (!runtime.enabled()) return List.of();
        var result=new ArrayList<String>();
        for (var target:runtime.settings().targets()) {
            if (!target.inboundAccess().enabled()) continue;
            try { access.administrator(model,target.key(),subject); result.add(target.key()); }
            catch (Failure denied) { if (denied.status()!=403) throw denied; }
        }
        return result.stream().sorted().toList();
    }
    public synchronized Status approve(String model,String key,AuthorizationSubject subject,Approval approval) {
        var target=access.administrator(model,key,subject); delivery.requireWriterLease();
        if (approval==null || approval.expectedVersion()<0 || !target.instanceId().equals(approval.targetInstanceId()) || !target.mapping().version().equals(approval.mappingVersion())) throw new Failure(409,"INBOUND_APPROVAL_STALE");
        if (approval.enabled()) runtime.validateTarget(key);
        var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",model).put("targetKey",key).put("targetInstanceId",target.instanceId())
                .put("mappingVersion",target.mapping().version()).put("approvedBy",subject.userId()).put("enabled",approval.enabled()).put("configurationDigest",configurationDigest(target));
        metadata().save(new StateDocument(instance(),DocumentKind.INBOUND_POLICY,InboundAuthorization.policyId(model,key),approval.expectedVersion(),data));
        return status(model,key,subject,"");
    }
    public Status status(String model,String key,AuthorizationSubject subject,String after) {
        var target=access.administrator(model,key,subject); var policy=policy(model,key);
        if (after==null || after.length()>2048) throw new Failure(400,"INVALID_BINDING_CURSOR");
        var page=metadata().bindings(instance(),target.instanceId(),model,after,100); var views=new ArrayList<BindingView>();
        for (var binding:page) {
            var state=load(binding); var d=state.map(StateDocument::data).orElse(json.createObjectNode());
            views.add(new BindingView(binding.model().entityId(),binding.canonical().canonicalId(),binding.external().resourceId(),
                    d.path("state").asText(binding.state()==BindingState.ORPHANED ? "ORPHANED" : "PAUSED"),d.path("code").asText(null),d.path("verifiedRevision").asLong(),d.path("attempts").asInt(),d.path("nextAttemptAt").asLong()));
        }
        var fields=new TreeMap<String,Set<String>>();
        for (String type:target.inboundAccess().cardTypes()) {
            var incoming=new TreeSet<>(InboundFields.normalMappings(target,type).keySet());
            if (target.centralEditAccess().enabled() && target.centralEditAccess().cardTypes().contains(type)) incoming.addAll(CentralFields.shared(target,type,CentralFields.mappings(target,type).keySet()));
            fields.put(type,incoming);
        }
        return new Status(policy.map(StateDocument::version).orElse(0L),policy.map(p -> p.data().path("enabled").asBoolean()).orElse(false),target.instanceId(),target.mapping().version(),fields,List.copyOf(views),page.size()==100 ? cursor(page.getLast()) : null);
    }
    public void refresh(String model,String key,AuthorizationSubject subject,String element) {
        var target=access.administrator(model,key,subject);
        var binding=binding(model,element,target); reconcile(key,binding,true);
    }
    public void reconcile(String key,ReservedImport binding,boolean force) {
        if (!execution.tryLock()) { if (force) throw new Failure(409,"INBOUND_BUSY"); return; }
        try {
            var target=access.target(key); var state=load(binding).orElseGet(() -> initial(binding,key));
            var data=(ObjectNode)state.data();
            if (!force && (data.path("nextAttemptAt").asLong()>now.getAsLong() || Set.of("CONFLICT","ERROR","ORPHANED","RETIRED").contains(data.path("state").asText()))) return;
            try {
                delivery.requireWriterLease(); var policy=access.capability(binding.model().sourceModelId(),key);
                if (!configurationDigest(target).equals(policy.data().path("configurationDigest").asText())) throw new Failure(409,"INBOUND_CONFIGURATION_CHANGED");
                validateBinding(binding,target);
                if (!target.mapping().version().equals(data.path("mappingVersion").asText())) throw new Failure(409,"INBOUND_BASELINE_MIGRATION_REQUIRED");
                if (data.has("pending")) { finishPending(state,binding); return; }
                if (InboundFields.normalMappings(target,binding.canonical().kind()).isEmpty()) return;
                runtime.validateTarget(key);
                var card=runtime.connector(key).getObject(binding.external().resourceId());
                if (!card.type().equals(binding.canonical().kind())) throw new Failure(409,"EXTERNAL_TYPE_CONFLICT");
                if (!"ACTIVE".equals(card.status())) throw new Failure(409,"EXTERNAL_OBJECT_RETIRED");
                var remote=InboundFields.remote(json,target,card); var snapshot=models.getSnapshot(binding.model().sourceModelId(),"HEAD");
                validateLocal(binding,snapshot);
                var local=InboundFields.local(json,snapshot,binding.model().entityId(),keys(remote));
                var baseline=data.has("baseline") ? data.path("baseline") : importedBaseline(binding,target,keys(remote));
                for (String field:keys(remote)) {
                    if (PublicationJson.equal(remote.path(field),local.path(field))) continue;
                    if (!baseline.has(field)) throw conflict(state,baseline,local,remote,snapshot.path("headRevision").asLong(),"BASELINE_APPROVAL_REQUIRED");
                    if (!PublicationJson.equal(local.path(field),baseline.path(field)))
                        throw conflict(state,baseline,local,remote,snapshot.path("headRevision").asLong(),PublicationJson.equal(remote.path(field),baseline.path(field)) ? "LOCAL_DRIFT" : "BOTH_SIDES_CHANGED");
                }
                if (PublicationJson.equal(local,remote)) { checkpoint(state,binding,remote,snapshot.path("headRevision").asLong(),null); return; }
                var command=command(binding,target,policy.version(),snapshot,local,remote);
                data.remove("conflict"); data.set("pending",json.valueToTree(command)); data.put("state","PENDING").put("attempts",0).put("nextAttemptAt",0);
                state=metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
                finishPending(state,binding);
            } catch (ConnectorException external) {
                if (external.kind()==ConnectorException.Kind.NOT_FOUND) { metadata().orphanBinding(binding.model()); failure(binding,"ORPHANED","EXTERNAL_OBJECT_NOT_FOUND",false,0); }
                else failure(binding,external.retryable() ? "RETRY" : "ERROR","EXTERNAL_"+external.kind(),external.retryable(),external.retryAfter().toMillis());
            } catch (Failure denied) {
                String status=denied.status()==403 || denied.status()==404 ? "PAUSED" : denied.getMessage().equals("EXTERNAL_OBJECT_RETIRED") ? "RETIRED" : denied.getMessage().equals("BINDING_ORPHANED") ? "ORPHANED" : "CONFLICT";
                failure(binding,status,denied.getMessage(),false,0);
            } catch (jakarta.ws.rs.WebApplicationException denied) { failure(binding,"PAUSED","INBOUND_AUTHORIZATION_REVOKED",false,0); }
            catch (IllegalArgumentException invalid) { failure(binding,"CONFLICT","INBOUND_MAPPING_OR_COMMAND_INVALID",false,0); }
            catch (RuntimeException interrupted) { failure(binding,"RETRY","INBOUND_ACCEPTANCE_OR_STATE_UNAVAILABLE",true,0); }
        } finally { execution.unlock(); }
    }
    public Inspection inspect(String model,String key,AuthorizationSubject subject,String element) {
        var target=access.administrator(model,key,subject); var binding=binding(model,element,target); validateBinding(binding,target);
        runtime.validateTarget(key); var card=runtime.connector(key).getObject(binding.external().resourceId());
        if (!card.type().equals(binding.canonical().kind()) || !"ACTIVE".equals(card.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT");
        var remote=InboundFields.remote(json,target,card); var snapshot=models.getSnapshot(model,"HEAD"); validateLocal(binding,snapshot);
        var local=InboundFields.local(json,snapshot,element,keys(remote)); var policy=policy(model,key);
        long revision=snapshot.path("headRevision").asLong();
        var base=load(binding).map(d -> d.data().path("baseline")).orElse(json.nullNode());
        String digest=PublicationJson.digest(json.valueToTree(Arrays.asList(binding,target.mapping(),policy.map(StateDocument::version).orElse(0L),revision,local,remote)));
        return new Inspection(element,base,local,remote,load(binding).map(d -> d.data().path("conflict")).orElse(json.nullNode()),revision,digest);
    }
    /** Explicit local adoption only: this endpoint never writes to Turbo EA. */
    public void acceptRemote(String model,String key,AuthorizationSubject subject,Resolution resolution) {
        access.administrator(model,key,subject); delivery.requireWriterLease();
        if (resolution==null) throw new Failure(400,"INBOUND_RESOLUTION_REQUIRED");
        if (!execution.tryLock()) throw new Failure(409,"INBOUND_BUSY");
        try {
            var inspected=inspect(model,key,subject,resolution.elementId());
            if (!inspected.digest().equals(resolution.inspectionDigest())) throw new Failure(409,"INBOUND_INSPECTION_STALE");
            var target=access.target(key); var policy=access.capability(model,key);
            if (!configurationDigest(target).equals(policy.data().path("configurationDigest").asText())) throw new Failure(409,"INBOUND_CONFIGURATION_CHANGED");
            var binding=binding(model,resolution.elementId(),target); var state=load(binding).orElseGet(() -> initial(binding,key));
            if (state.data().has("pending")) throw new Failure(409,"RECOVER_PENDING_ACCEPTANCE_FIRST");
            if (PublicationJson.equal(inspected.local(),inspected.remote())) { checkpoint(state,binding,inspected.remote(),inspected.revision(),null); return; }
            var snapshot=models.getSnapshot(model,"HEAD");
            if (snapshot.path("headRevision").asLong()!=inspected.revision()) throw new Failure(409,"INBOUND_INSPECTION_STALE");
            var command=command(binding,target,policy.version(),snapshot,inspected.local(),inspected.remote());
            var data=(ObjectNode)state.data(); data.remove("conflict"); data.set("pending",json.valueToTree(command)); data.put("state","PENDING").put("attempts",0).put("nextAttemptAt",0);
            state=metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data)); finishPending(state,binding);
        } finally { execution.unlock(); }
    }
    public record ProposalDelivery(String state,String code,long revision) { }
    public Inspection inspectProposal(String model,String key,AuthorizationSubject subject,String element,String proposal) {
        var target=access.administrator(model,key,subject); var binding=binding(model,element,target);
        var proof=proposalGrants.proof(proposal,key,binding.canonical().canonicalId(),binding.external().resourceId());
        var card=runtime.connector(key).getObject(binding.external().resourceId()); validateBinding(binding,target);
        var frame=proposalFrame(binding,target,proof,card);
        return new Inspection(element,baseline(binding,target,CentralFields.keys(frame.remote())),frame.local(),frame.remote(),load(binding).map(d -> d.data().path("conflict")).orElse(json.nullNode()),frame.revision(),frame.digest());
    }
    public ProposalDelivery applyProposal(String proposal,ReservedImport binding,String resolutionDigest) {
        if (!execution.tryLock()) return new ProposalDelivery("PAUSED","INBOUND_BUSY",0);
        String key=null;
        try {
            var proof=proposalGrants.proof(proposal,loadProposalTarget(proposal),binding.canonical().canonicalId(),binding.external().resourceId());
            key=proof.data().path("targetKey").asText(); var target=access.target(key); var policy=access.capability(binding.model().sourceModelId(),key);
            validateBinding(binding,target); delivery.requireWriterLease();
            if (!proposalGrants.current(proposal)) throw new Failure(409,"CENTRAL_PROPOSAL_SUPERSEDED");
            var state=load(binding).orElseGet(() -> initial(binding,proof.data().path("targetKey").asText()));
            if (state.data().has("pending")) { finishPending(state,binding); state=load(binding).orElseThrow(); if (state.data().has("pending")) return proposalDelivery(state); }
            runtime.validateTarget(key); var card=runtime.connector(key).getObject(binding.external().resourceId());
            var frame=proposalFrame(binding,target,proof,card);
            if (resolutionDigest!=null && !resolutionDigest.equals(frame.digest())) throw new Failure(409,"INBOUND_INSPECTION_STALE");
            var base=baseline(binding,target,CentralFields.keys(frame.remote())); var selected=CentralFields.keys(proof.data().path("proposed"));
            var original=CentralFields.localRepresentation(json,proof.data().path("baseRemote"));
            for (String field:CentralFields.keys(frame.remote())) {
                if (PublicationJson.equal(frame.local().path(field),frame.remote().path(field))) continue;
                boolean permitted=base.has(field) && PublicationJson.equal(frame.local().path(field),base.path(field));
                if (selected.contains(field)) permitted=permitted || resolutionDigest!=null
                        || PublicationJson.equal(frame.local().path(field),original.path(field))
                        || proof.data().path("sourceModelId").asText().equals(binding.model().sourceModelId()) && proof.data().path("elementId").asText().equals(binding.model().entityId()) && PublicationJson.equal(frame.local().path(field),proof.data().path("baseLocal").path(field))
                        || !base.has(field) && (frame.local().path(field).isNull() || !field.startsWith("property:") && frame.local().path(field).asText().isEmpty());
                if (!permitted) throw conflict(state,base,frame.local(),frame.remote(),frame.revision(),"PROPOSAL_LOCAL_DRIFT");
            }
            if (PublicationJson.equal(frame.local(),frame.remote())) { checkpoint(state,binding,frame.remote(),frame.revision(),null); return proposalDelivery(load(binding).orElseThrow()); }
            var command=new ExternalChangeCommand(instance(),key,target.instanceId(),binding.canonical().canonicalId(),binding.external().resourceId(),binding.model().sourceModelId(),"HEAD",binding.model().entityId(),binding.archimateType(),binding.mappingVersion(),policy.version(),UUID.randomUUID().toString(),frame.revision(),frame.local(),frame.remote(),proposal);
            var data=(ObjectNode)state.data(); data.remove("conflict"); data.set("pending",json.valueToTree(command)); data.put("state","PENDING").put("attempts",0).put("nextAttemptAt",0);
            state=metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data)); finishPending(state,binding);
            return proposalDelivery(load(binding).orElseThrow());
        } catch (Failure failure) {
            if (resolutionDigest!=null) throw failure;
            String status=failure.status()==403 || failure.status()==404 ? "PAUSED" : "CONFLICT";
            if (key!=null) { if (load(binding).isEmpty()) initial(binding,key); failure(binding,status,failure.getMessage(),false,0); }
            return new ProposalDelivery(status,failure.getMessage(),0);
        } catch (jakarta.ws.rs.WebApplicationException denied) { return new ProposalDelivery("PAUSED","INBOUND_AUTHORIZATION_REVOKED",0); }
        catch (ConnectorException unavailable) { return new ProposalDelivery("ERROR","EXTERNAL_"+unavailable.kind(),0); }
        finally { execution.unlock(); }
    }
    private String loadProposalTarget(String id) { return metadata().load(instance(),DocumentKind.CENTRAL_EDIT,id).orElseThrow(() -> new Failure(404,"CENTRAL_PROPOSAL_NOT_FOUND")).data().path("targetKey").asText(); }
    private ProposalDelivery proposalDelivery(StateDocument state) { return new ProposalDelivery(state.data().path("state").asText(),state.data().path("code").asText(null),state.data().path("verifiedRevision").asLong()); }
    private record ProposalFrame(JsonNode local,JsonNode remote,long revision,String digest) { }
    private ProposalFrame proposalFrame(ReservedImport binding,IntegrationConfiguration.Target target,StateDocument proof,ExternalRepositoryConnector.ExternalObject card) {
        if (!card.type().equals(binding.canonical().kind()) || !"ACTIVE".equals(card.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT");
        var selected=CentralFields.keys(proof.data().path("proposed"));
        if (!PublicationJson.equal(CentralFields.remote(json,target,card,selected),proof.data().path("proposed"))) throw new Failure(409,"CENTRAL_REMOTE_ADVANCED");
        var remote=InboundFields.remote(json,card,proposalGrants.fields(target,binding.canonical().kind(),proof));
        var snapshot=models.getSnapshot(binding.model().sourceModelId(),"HEAD"); validateLocal(binding,snapshot);
        var local=InboundFields.local(json,snapshot,binding.model().entityId(),CentralFields.keys(remote)); long revision=snapshot.path("headRevision").asLong();
        String digest=PublicationJson.digest(json.valueToTree(List.of(proof.id(),proof.version(),binding,revision,local,remote)));
        return new ProposalFrame(local,remote,revision,digest);
    }

    private void finishPending(StateDocument state,ReservedImport binding) {
        var command=json.convertValue(state.data().path("pending"),ExternalChangeCommand.class);
        var result=models.submitExternalChange(command);
        if (!result.accepted() && !"EXTERNAL_CHANGE_ALREADY_EQUAL".equals(result.code())) {
            if (Set.of("EXTERNAL_CHANGE_LOCAL_STALE","EXTERNAL_CHANGE_POLICY_STALE","EXTERNAL_CHANGE_PROPOSAL_STALE").contains(result.code())) {
                var data=(ObjectNode)state.data(); data.remove("pending");
                metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
                failure(binding,"RETRY",result.code(),true,0); return;
            }
            throw new Failure(409,result.code());
        }
        var snapshot=models.getSnapshot(binding.model().sourceModelId(),"HEAD"); validateLocal(binding,snapshot);
        var actual=InboundFields.local(json,snapshot,binding.model().entityId(),keys(command.remote()));
        if (!PublicationJson.equal(actual,command.remote())) {
            var data=(ObjectNode)state.data(); data.remove("pending");
            metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
            failure(binding,"RETRY","ACCEPTED_OBSERVATION_SUPERSEDED",true,0); return;
        }
        checkpoint(state,binding,command.remote(),snapshot.path("headRevision").asLong(),result.accepted() ? command.opBatchId() : null);
    }
    private void checkpoint(StateDocument state,ReservedImport binding,JsonNode remote,long revision,String batch) {
        var data=(ObjectNode)state.data(); data.remove(List.of("pending","code","conflict"));
        var baseline=data.path("baseline").isObject() ? (ObjectNode)data.path("baseline").deepCopy() : json.createObjectNode();
        remote.fields().forEachRemaining(entry -> baseline.set(entry.getKey(),PublicationJson.canonical(entry.getValue())));
        data.set("baseline",baseline); data.put("state","SYNCED").put("verifiedRevision",revision).put("verifiedAt",now.getAsLong())
                .put("digest",PublicationJson.digest(remote)).put("attempts",0).put("nextAttemptAt",0);
        if (batch!=null) data.put("lastAcceptedBatch",batch);
        metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
        org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=InboundVerified model={} canonical={} resource={} correlation={} revision={} actor=external-sync fields={} decision=SYNCED",
                audit(binding.model().sourceModelId()),audit(binding.canonical().canonicalId()),audit(binding.external().resourceId()),audit(batch),revision,keys(remote));
    }
    private Failure conflict(StateDocument state,JsonNode base,JsonNode local,JsonNode remote,long revision,String code) {
        var data=(ObjectNode)state.data(); data.set("baseline",base);
        var conflict=data.putObject("conflict").put("reason",code).put("observedRevision",revision).put("observedAt",now.getAsLong()).put("baseDigest",PublicationJson.digest(base));
        conflict.set("local",local); conflict.set("remote",remote);
        metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
        return new Failure(409,code);
    }
    private void failure(ReservedImport binding,String status,String code,boolean retry,long delay) {
        var state=load(binding).orElseThrow(); var data=(ObjectNode)state.data(); int attempts=data.path("attempts").asInt()+(retry ? 1 : 0);
        if (retry && attempts>=4) status="ERROR";
        data.put("state",status).put("code",code).put("attempts",attempts).put("nextAttemptAt",retry ? now.getAsLong()+Math.max(delay,(1L<<Math.min(attempts,4))*1000+java.util.concurrent.ThreadLocalRandom.current().nextLong(1000)) : 0);
        metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
        org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=InboundDeferred model={} canonical={} resource={} code={} decision={}",audit(binding.model().sourceModelId()),audit(binding.canonical().canonicalId()),audit(binding.external().resourceId()),audit(code),audit(status));
    }
    private ExternalChangeCommand command(ReservedImport binding,IntegrationConfiguration.Target target,long policy,JsonNode snapshot,JsonNode local,JsonNode remote) {
        return new ExternalChangeCommand(instance(),target.key(),target.instanceId(),binding.canonical().canonicalId(),binding.external().resourceId(),binding.model().sourceModelId(),"HEAD",binding.model().entityId(),binding.archimateType(),binding.mappingVersion(),policy,UUID.randomUUID().toString(),snapshot.path("headRevision").asLong(),local,remote);
    }
    public JsonNode baseline(ReservedImport binding,IntegrationConfiguration.Target target,Set<String> fields) {
        return load(binding).filter(d -> d.data().has("baseline")).map(d -> d.data().path("baseline")).orElseGet(() -> importedBaseline(binding,target,fields));
    }
    private ObjectNode importedBaseline(ReservedImport binding,IntegrationConfiguration.Target target,Set<String> fields) {
        var baseline=json.createObjectNode();
        if (!binding.bindingMode().equals("CREATE")) return baseline;
        var plan=metadata().load(instance(),DocumentKind.JOB,"import-plan:"+binding.opBatchId());
        if (plan.isEmpty()) return baseline;
        var imported=json.createObjectNode(); boolean found=false;
        for (var op:plan.get().data().path("operations")) {
            if (op.path("type").asText().equals("CreateElement") && op.path("element").path("id").asText().equals(binding.model().entityId())) { imported.setAll((ObjectNode)op.path("element")); found=true; }
            if (op.path("type").asText().equals("SetProperty") && op.path("targetId").asText().equals(binding.model().entityId())) imported.set("property:"+op.path("key").asText(),op.path("value"));
        }
        if (!found) return baseline;
        var mappings=InboundFields.normalMappings(target,binding.canonical().kind());
        for (String field:fields) if (mappings.containsKey(field) && target.catalogAccess().fields().contains(mappings.get(field).remoteField())) {
            JsonNode value=imported.get(field);
            baseline.set(field,value==null ? field.startsWith("property:") ? json.nullNode() : json.getNodeFactory().textNode("") : value);
        }
        return baseline;
    }
    private void validateBinding(ReservedImport binding,IntegrationConfiguration.Target target) {
        if (binding.state()!=BindingState.ACTIVE) throw new Failure(409,"BINDING_ORPHANED");
        if (!binding.external().targetInstanceId().equals(target.instanceId()) || !binding.mappingVersion().equals(target.mapping().version())) throw new Failure(409,"BINDING_MAPPING_OR_TARGET_CHANGED");
        if (!binding.archimateType().equals(target.mapping().archimateType(binding.canonical().kind()))) throw new Failure(409,"BINDING_TYPE_CONFLICT");
    }
    private void validateLocal(ReservedImport binding,JsonNode snapshot) {
        var element=InboundFields.element(snapshot,binding.model().entityId());
        if (!binding.archimateType().equals(element.path("archimateType").asText())) throw new Failure(409,"LOCAL_TYPE_CONFLICT");
        if (!binding.bindingMode().equals("PUBLISHED")) {
            var provenance=InboundFields.local(json,snapshot,binding.model().entityId(),Set.of("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY));
            if (!binding.canonical().canonicalId().equals(provenance.path("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY).asText())) throw new Failure(409,"LOCAL_PROVENANCE_DRIFT");
        }
    }
    private ReservedImport binding(String model,String element,IntegrationConfiguration.Target target) {
        var result=metadata().findBinding(new ModelElementKey(instance(),model,EntityKind.ELEMENT,element)).orElseThrow(() -> new Failure(404,"BINDING_NOT_FOUND"));
        if (!result.external().targetInstanceId().equals(target.instanceId())) throw new Failure(404,"BINDING_NOT_FOUND"); return result;
    }
    private StateDocument initial(ReservedImport b,String key) {
        var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",b.model().sourceModelId()).put("entityId",b.model().entityId()).put("canonicalId",b.canonical().canonicalId())
                .put("targetKey",key).put("targetInstanceId",b.external().targetInstanceId()).put("mappingVersion",b.mappingVersion()).put("state","PENDING");
        return metadata().save(new StateDocument(instance(),DocumentKind.INBOUND_STATE,stateId(b),0,data));
    }
    private Optional<StateDocument> load(ReservedImport binding) { return metadata().load(instance(),DocumentKind.INBOUND_STATE,stateId(binding)); }
    private Optional<StateDocument> policy(String model,String key) { return metadata().load(instance(),DocumentKind.INBOUND_POLICY,InboundAuthorization.policyId(model,key)); }
    private IntegrationStateRepository metadata() { return runtime.metadata(); }
    private String instance() { return runtime.settings().instanceId(); }
    private Set<String> keys(JsonNode object) { var keys=new TreeSet<String>(); object.fieldNames().forEachRemaining(keys::add); return keys; }
    public static String stateId(ReservedImport b) { return PublicationJson.key("in-state:",b.model().sourceModelId(),b.model().entityId(),b.external().targetInstanceId()); }
    public static String cursor(ReservedImport b) { return b.model().sourceModelId().codePointCount(0,b.model().sourceModelId().length())+":"+b.model().sourceModelId()+":"+b.model().entityId(); }
    public static String configurationDigest(IntegrationConfiguration.Target target) { return PublicationJson.digest(new ObjectMapper().valueToTree(List.of(target.mapping(),new TreeSet<>(target.inboundAccess().cardTypes()),new TreeSet<>(target.inboundAccess().fields())))); }
    private String audit(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
}
