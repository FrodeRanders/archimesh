package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.service.*;
import java.util.*;
import java.util.function.LongSupplier;
import static org.gautelis.archimesh.integration.CentralEditModel.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Explicit central changes: reviewed immutable intent -> fenced PATCH -> verified inbound fan-out. */
@ApplicationScoped
public class CentralEditService {
    @Inject IntegrationRuntime runtime;
    @Inject CentralEditAuthorization authorization;
    @Inject InboundAuthorization incoming;
    @Inject ExternalChangeIngestService inbound;
    @Inject CentralProposalGrant grants;
    @Inject ArchimeshService models;
    @Inject CommitDeliveryService delivery;
    @Inject RemoteMutationCoordinator execution;
    @Inject ObjectMapper json;
    LongSupplier now=System::currentTimeMillis;

    public Map<String,Boolean> capabilities(String model,String key,AuthorizationSubject subject) {
        if (!runtime.enabled() || !target(key).centralEditAccess().enabled()) throw new Failure(404,"CENTRAL_EDITS_DISABLED");
        boolean request=false,approve=false,resolve=false;
        try { authorization.require(model,key,subject,false); request=true; } catch (Failure | jakarta.ws.rs.WebApplicationException denied) { }
        try { authorization.require(model,key,subject,true); approve=true; } catch (Failure | jakarta.ws.rs.WebApplicationException denied) { }
        try { incoming.administrator(model,key,subject); resolve=true; } catch (Failure | jakarta.ws.rs.WebApplicationException denied) { }
        if (!request && !approve && !resolve) throw new Failure(403,"CENTRAL_EDIT_ACCESS_DENIED");
        return Map.of("canRequest",request,"canApprove",approve,"canResolve",resolve);
    }
    public List<String> targets(String model,AuthorizationSubject subject) {
        if (!runtime.enabled()) return List.of(); var result=new ArrayList<String>();
        for (var target:runtime.settings().targets()) if (target.centralEditAccess().enabled()) {
            try { capabilities(model,target.key(),subject); result.add(target.key()); }
            catch (Failure denied) { if (denied.status()!=403) throw denied; }
        }
        return result.stream().sorted().toList();
    }
    public BindingPage bindings(String model,String key,AuthorizationSubject subject,String after) {
        var target=authorization.read(model,key,subject); var result=new ArrayList<Map<String,String>>();
        var page=metadata().bindings(instance(),target.instanceId(),model,after==null ? "" : after,100);
        var names=new HashMap<String,String>(); models.getSnapshot(model,"HEAD").path("elements").forEach(e -> names.put(e.path("id").asText(),e.path("name").asText()));
        for (var b:page)
            if (b.state()==BindingState.ACTIVE && names.containsKey(b.model().entityId()) && target.centralEditAccess().cardTypes().contains(b.canonical().kind()))
                result.add(Map.of("elementId",b.model().entityId(),"name",names.get(b.model().entityId()),"canonicalId",b.canonical().canonicalId(),"resourceId",b.external().resourceId(),"cursor",ExternalChangeIngestService.cursor(b)));
        return new BindingPage(List.copyOf(result),page.size()==100 ? ExternalChangeIngestService.cursor(page.getLast()) : null);
    }
    public Context context(String model,String key,String element,AuthorizationSubject subject) { return frame(model,key,element,subject,false).context(); }
    public Preview preview(String model,String key,AuthorizationSubject subject,PreviewRequest request) {
        if (request==null) throw new Failure(400,"CENTRAL_PREVIEW_REQUIRED");
        authorization.require(model,key,subject,false);
        var frame=frame(model,key,request.elementId(),subject,false);
        return preview(frame,CentralFields.proposed(json,frame.target(),frame.binding().canonical().kind(),request.proposed()));
    }
    public Job submit(String model,String key,AuthorizationSubject subject,Request request) {
        var target=authorization.require(model,key,subject,false); delivery.requireWriterLease();
        if (request==null || request.requestId()==null || !UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId())) throw new Failure(400,"CENTRAL_REQUEST_ID_REQUIRED");
        var input=new PreviewRequest(request.ref(),request.elementId(),request.proposed());
        String id=prefix(model,key)+PublicationJson.key("",request.requestId());
        String fingerprint=PublicationJson.digest(json.valueToTree(List.of(subject.userId(),request)));
        var previous=load(DocumentKind.CENTRAL_EDIT,id);
        if (previous.isPresent()) { if (!previous.get().data().path("fingerprint").asText().equals(fingerprint)) throw new Failure(409,"CENTRAL_REQUEST_ID_REUSED"); checkScope(previous.get(),target); return view(previous.get()); }
        var frame=frame(model,key,input.elementId(),subject,false); var proposed=CentralFields.proposed(json,target,frame.binding().canonical().kind(),input.proposed()); var reviewed=preview(frame,proposed);
        if (!reviewed.digest().equals(request.previewDigest())) throw new Failure(409,"CENTRAL_PREVIEW_STALE");
        if (reviewed.mode().equals("WRITE_THROUGH") && !reviewed.driftFields().isEmpty()) throw new Failure(409,"CENTRAL_BASELINE_REVIEW_REQUIRED");
        var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",model).put("targetKey",key).put("targetInstanceId",target.instanceId())
                .put("canonicalId",frame.binding().canonical().canonicalId()).put("resourceId",frame.binding().external().resourceId()).put("elementId",input.elementId())
                .put("type",frame.binding().canonical().kind()).put("mappingVersion",target.mapping().version()).put("scopeDigest",CentralEditAuthorization.digest(target))
                .put("modelGeneration",runtime.requireModelActive(model)).put("requestedBy",subject.userId()).put("requestId",request.requestId()).put("fingerprint",fingerprint).put("policyVersion",frame.policy().version())
                .put("mode",reviewed.mode()).put("createdAt",now.getAsLong()).put("attempts",0).put("nextAttemptAt",0)
                .put("state",reviewed.mode().equals("PROPOSAL") ? "WAITING_APPROVAL" : "PENDING");
        var selected=CentralFields.keys(proposed); data.set("proposed",proposed); data.set("baseRemote",CentralFields.remote(json,target,frame.card(),selected));
        data.set("baseLocal",InboundFields.local(json,frame.snapshot(),input.elementId(),selected)); data.set("baseline",select(frame.baseline(),selected));
        var document=new StateDocument(instance(),DocumentKind.CENTRAL_EDIT,id,0,data);
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            if (reviewed.mode().equals("WRITE_THROUGH")) { requireWrites(target,frame.binding().canonical().kind(),proposed); document=enqueue(document,target); }
            else document=metadata().save(document);
            audit("CentralRequested",document,subject.userId()); return view(document);
        } finally { execution.unlock(); }
    }
    public Review review(String model,String key,String id,AuthorizationSubject subject) {
        var target=authorization.require(model,key,subject,true); var job=job(model,key,id,target);
        return review(job,frame(model,key,job.data().path("elementId").asText(),subject,true));
    }
    public Job decide(String model,String key,String id,AuthorizationSubject subject,Decision decision) {
        var target=authorization.require(model,key,subject,true); delivery.requireWriterLease();
        if (decision==null) throw new Failure(400,"CENTRAL_DECISION_REQUIRED");
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            var job=job(model,key,id,target);
            if (!job.data().path("state").asText().equals("WAITING_APPROVAL")) {
                if (Objects.equals(job.data().path("decisionDigest").asText(null),decision.reviewDigest()) && job.data().path("approvedBy").asText().equals(subject.userId())
                        && job.data().path("decisionApprove").asBoolean()==decision.approve()) return view(job);
                throw new Failure(409,"CENTRAL_DECISION_ALREADY_MADE");
            }
            if (job.version()!=decision.expectedVersion()) throw new Failure(409,"CENTRAL_DECISION_STALE");
            var frame=frame(model,key,job.data().path("elementId").asText(),subject,true); var review=review(job,frame);
            if (!review.digest().equals(decision.reviewDigest())) throw new Failure(409,"CENTRAL_REVIEW_STALE");
            runtime.requireGeneration(model,job.data().path("modelGeneration").asText(null));
            if (decision.approve() && review.baseChanged()) throw new Failure(409,"CENTRAL_PROPOSAL_BASE_CHANGED");
            if (decision.approve() && !review.preview().driftFields().isEmpty() && !decision.resolveDrift()) throw new Failure(409,"CENTRAL_BASELINE_REVIEW_REQUIRED");
            var data=(ObjectNode)job.data(); data.put("approvedBy",subject.userId()).put("decisionDigest",decision.reviewDigest()).put("decisionApprove",decision.approve())
                    .put("baselineResolutionApproved",decision.resolveDrift()).put("decisionAt",now.getAsLong()).put("policyVersion",frame.policy().version()).put("state",decision.approve() ? "PENDING" : "REJECTED");
            job=new StateDocument(instance(),job.kind(),job.id(),job.version(),data);
            if (decision.approve()) { requireWrites(target,data.path("type").asText(),data.path("proposed")); job=enqueue(job,target); } else job=metadata().save(job);
            audit(decision.approve() ? "CentralApproved" : "CentralRejected",job,subject.userId()); return view(job);
        } finally { execution.unlock(); }
    }
    public Job get(String model,String key,String id,AuthorizationSubject subject) { return view(job(model,key,id,authorization.read(model,key,subject))); }
    public Page list(String model,String key,AuthorizationSubject subject,String after) {
        var target=authorization.read(model,key,subject); if (after==null || after.length()>512) throw new Failure(400,"INVALID_CENTRAL_CURSOR");
        var page=metadata().list(instance(),DocumentKind.CENTRAL_EDIT,prefix(model,key),after,50); var result=new ArrayList<Job>();
        for (var job:page) { checkScope(job,target); result.add(view(job)); }
        return new Page(List.copyOf(result),page.size()==50 ? page.getLast().id() : null);
    }
    public Job cancel(String model,String key,String id,AuthorizationSubject subject,Outcome request) {
        var target=authorization.require(model,key,subject,false); delivery.requireWriterLease();
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            var job=job(model,key,id,target);
            if (!job.data().path("requestedBy").asText().equals(subject.userId())) throw new Failure(403,"ONLY_REQUESTER_CAN_CANCEL");
            if (request==null || request.expectedVersion()!=job.version() || job.data().path("remoteVerified").asBoolean() || !Set.of("PENDING","WAITING_APPROVAL","RETRY","PAUSED").contains(job.data().path("state").asText())) throw new Failure(409,"CENTRAL_CANCEL_STALE");
            if (lane(job).data().has("inFlight")) throw new Failure(409,"CENTRAL_OUTCOME_UNCERTAIN");
            job=save(job,"CANCELLED",null); audit("CentralCancelled",job,subject.userId()); return view(job);
        } finally { execution.unlock(); }
    }
    /** Uncertain PATCHes are never replayed. A separate explicit action can verify their content outcome. */
    public Job verifyOutcome(String model,String key,String id,AuthorizationSubject subject,Outcome request) {
        var target=authorization.read(model,key,subject); delivery.requireWriterLease();
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            var job=job(model,key,id,target); var data=job.data();
            if (data.path("mode").asText().equals("PROPOSAL")) authorization.require(model,key,subject,true);
            else { authorization.require(model,key,subject,false); if (!data.path("requestedBy").asText().equals(subject.userId())) throw new Failure(403,"ONLY_REQUESTER_CAN_VERIFY"); }
            if (request==null || request.expectedVersion()!=job.version() || !Set.of("AMBIGUOUS","IN_FLIGHT").contains(data.path("state").asText())) throw new Failure(409,"CENTRAL_OUTCOME_REVIEW_STALE");
            var lane=lane(job); if (!lane.data().path("jobId").asText().equals(id)) throw new Failure(409,"CENTRAL_REQUEST_SUPERSEDED");
            runtime.validateTarget(key); var card=read(target,data.path("resourceId").asText(),data.path("type").asText());
            if (!PublicationJson.equal(CentralFields.remote(json,target,card,CentralFields.keys(data.path("proposed"))),data.path("proposed"))) return view(save(job,"AMBIGUOUS","CENTRAL_OUTCOME_NOT_CONFIRMED"));
            job=verified(job,lane); audit("CentralOutcomeVerified",job,subject.userId()); return view(job);
        } finally { execution.unlock(); }
    }
    public Job retryFanout(String model,String key,String id,AuthorizationSubject subject,Outcome request) {
        var target=authorization.read(model,key,subject); delivery.requireWriterLease();
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            var job=job(model,key,id,target);
            if (request==null || request.expectedVersion()!=job.version() || !job.data().path("remoteVerified").asBoolean() || !lane(job).data().path("jobId").asText().equals(id) || !Set.of("PARTIAL","SYNCED","ERROR","CONFLICT").contains(job.data().path("state").asText())) throw new Failure(409,"CENTRAL_FANOUT_REVIEW_STALE");
            var data=(ObjectNode)job.data(); data.put("cursor","").put("state","REMOTE_VERIFIED"); data.remove("code");
            return view(metadata().save(new StateDocument(instance(),job.kind(),id,job.version(),data)));
        } finally { execution.unlock(); }
    }

    public void run(String id) {
        if (!execution.tryLock()) return;
        try {
            runtime.requireRunning();
            var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow(); var data=job.data(); String state=data.path("state").asText();
            if (!Set.of("PENDING","RETRY","IN_FLIGHT","REMOTE_VERIFIED","SYNCING").contains(state) || data.path("nextAttemptAt").asLong()>now.getAsLong()) return;
            runtime.requireGeneration(data.path("sourceModelId").asText(),data.path("modelGeneration").asText(null));
            var target=target(data.path("targetKey").asText()); checkScope(job,target); delivery.requireWriterLease();
            if (data.path("remoteVerified").asBoolean()) { fanout(job,target); return; }
            var lane=lane(job);
            if (!lane.data().path("jobId").asText().equals(id)) { save(job,"SUPERSEDED",null); return; }
            if (lane.data().has("inFlight") || state.equals("IN_FLIGHT")) { save(job,"AMBIGUOUS","CENTRAL_PATCH_OUTCOME_UNCERTAIN"); return; }
            authorization.require(data.path("sourceModelId").asText(),target.key(),subject(data.path("requestedBy").asText()),false);
            if (data.path("mode").asText().equals("PROPOSAL")) authorization.require(data.path("sourceModelId").asText(),target.key(),subject(data.path("approvedBy").asText()),true);
            var policy=incoming.capability(data.path("sourceModelId").asText(),target.key());
            if (policy.version()!=data.path("policyVersion").asLong()) throw new Failure(409,"CENTRAL_INBOUND_APPROVAL_CHANGED");
            var binding=binding(data.path("sourceModelId").asText(),data.path("elementId").asText(),target); validateJobBinding(job,binding);
            var snapshot=models.getSnapshot(binding.model().sourceModelId(),"HEAD"); validateLocal(binding,snapshot);
            var local=InboundFields.local(json,snapshot,binding.model().entityId(),CentralFields.keys(data.path("proposed")));
            if (!PublicationJson.equal(local,data.path("baseLocal"))) throw new Failure(409,"CENTRAL_LOCAL_CHANGED");
            execution.requireCentralAllowed(runtime,target.instanceId(),binding.canonical().canonicalId()); requireWrites(target,data.path("type").asText(),data.path("proposed")); runtime.validateTarget(target.key());
            var card=read(target,binding.external().resourceId(),binding.canonical().kind());
            var actual=CentralFields.remote(json,target,card,CentralFields.keys(data.path("proposed")));
            if (PublicationJson.equal(actual,data.path("proposed"))) { verified(job,lane); return; }
            if (!PublicationJson.equal(actual,data.path("baseRemote"))) throw new Failure(409,"CENTRAL_REMOTE_DRIFT");
            // Reread immediately before building an attributes replacement map; preserve every unowned member.
            card=read(target,binding.external().resourceId(),binding.canonical().kind()); actual=CentralFields.remote(json,target,card,CentralFields.keys(data.path("proposed")));
            if (!PublicationJson.equal(actual,data.path("baseRemote"))) throw new Failure(409,"CENTRAL_REMOTE_DRIFT");
            var patch=CentralFields.payload(json,target,binding.canonical().kind(),data.path("proposed"),card);
            var intent=(ObjectNode)data; intent.put("state","IN_FLIGHT").put("operationId",UUID.randomUUID().toString()).put("dispatchedAt",now.getAsLong());
            var laneData=(ObjectNode)lane.data(); laneData.putObject("inFlight").put("jobId",id).put("operationId",intent.path("operationId").asText());
            var dispatch=metadata().saveAtomically(List.of(new StateDocument(instance(),job.kind(),id,job.version(),intent),new StateDocument(instance(),lane.kind(),lane.id(),lane.version(),laneData)));
            job=dispatch.get(0); lane=dispatch.get(1);
            try { runtime.centralWriter(target.key()).patchObject(binding.external().resourceId(),patch); }
            catch (ConnectorException failure) {
                if (failure.status()>=400 && failure.status()<500 && failure.status()!=408) definitiveFailure(job,lane,failure);
                else save(job,"AMBIGUOUS","CENTRAL_PATCH_OUTCOME_UNCERTAIN");
                return;
            } catch (RuntimeException uncertain) { save(job,"AMBIGUOUS","CENTRAL_PATCH_OUTCOME_UNCERTAIN"); return; }
            try {
            runtime.requireRunning();
                var after=read(target,binding.external().resourceId(),binding.canonical().kind());
                if (!PublicationJson.equal(CentralFields.remote(json,target,after,CentralFields.keys(data.path("proposed"))),data.path("proposed"))) { save(job,"AMBIGUOUS","CENTRAL_PATCH_VERIFICATION_DIVERGED"); return; }
                verified(job,lane);
            } catch (ConnectorException | Failure uncertain) { save(job,"AMBIGUOUS","CENTRAL_PATCH_VERIFICATION_UNAVAILABLE"); }
        } catch (ConnectorException failure) { var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow(); retry(job,failure); }
        catch (Failure failure) { if (failure.getMessage().equals("INTEGRATION_PAUSED")) return; var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow(); save(job,failure.status()==403 || failure.status()==404 ? "PAUSED" : "CONFLICT",failure.getMessage()); }
        catch (jakarta.ws.rs.WebApplicationException denied) { var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow(); save(job,"PAUSED","CENTRAL_AUTHORIZATION_REVOKED"); }
        catch (RuntimeException unavailable) {
            var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow();
            if (!job.data().path("remoteVerified").asBoolean()) {
                if (lane(job).data().has("inFlight")) save(job,"AMBIGUOUS","CENTRAL_PATCH_OUTCOME_UNCERTAIN");
                else save(job,"ERROR","CENTRAL_STATE_UNAVAILABLE");
            }
        } finally { execution.unlock(); }
    }
    private void fanout(StateDocument job,IntegrationConfiguration.Target target) {
        var data=job.data();
        if (!grants.current(job.id())) { save(job,"SUPERSEDED",null); return; }
        var card=read(target,data.path("resourceId").asText(),data.path("type").asText());
        if (!PublicationJson.equal(CentralFields.remote(json,target,card,CentralFields.keys(data.path("proposed"))),data.path("proposed"))) throw new Failure(409,"CENTRAL_REMOTE_ADVANCED");
        var page=metadata().bindings(instance(),target.instanceId(),null,data.path("cursor").asText(""),10);
        for (var binding:page) {
            if (binding.canonical().canonicalId().equals(data.path("canonicalId").asText())) {
                var result=inbound.applyProposal(job.id(),binding,null); job=recordDelivery(job,binding,result);
            } else { var updated=(ObjectNode)job.data(); updated.put("cursor",ExternalChangeIngestService.cursor(binding)); job=metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),updated)); }
        }
        if (page.size()<10) {
            data=job.data(); String state=data.path("synced").asInt()==0 && data.path("pending").asInt()==0 && data.path("conflicts").asInt()==0 ? "CONFLICT" : data.path("pending").asInt()+data.path("conflicts").asInt()==0 ? "SYNCED" : "PARTIAL";
            save(job,state,state.equals("CONFLICT") ? "NO_BOUND_OCCURRENCES" : null);
        }
    }
    private StateDocument recordDelivery(StateDocument job,ReservedImport binding,ExternalChangeIngestService.ProposalDelivery result) {
        String id=deliveryPrefix(binding.model().sourceModelId(),job.data().path("targetKey").asText())+PublicationJson.key("",job.id(),binding.model().entityId());
        var prior=load(DocumentKind.CENTRAL_DELIVERY,id); var stats=(ObjectNode)job.data();
        if (prior.isPresent()) stats.put(bucket(prior.get().data().path("state").asText()),stats.path(bucket(prior.get().data().path("state").asText())).asInt()-1);
        String bucket=bucket(result.state()); stats.put(bucket,stats.path(bucket).asInt()+1).put("cursor",ExternalChangeIngestService.cursor(binding)).put("state","SYNCING");
        if (binding.model().sourceModelId().equals(stats.path("sourceModelId").asText()) && binding.model().entityId().equals(stats.path("elementId").asText())) stats.put("sourceState",result.state());
        var delivery=json.createObjectNode().put("sourceModelId",binding.model().sourceModelId()).put("elementId",binding.model().entityId()).put("canonicalId",binding.canonical().canonicalId())
                .put("jobId",job.id()).put("state",result.state()).put("code",result.code()).put("verifiedRevision",result.revision());
        return metadata().saveAtomically(List.of(new StateDocument(instance(),job.kind(),job.id(),job.version(),stats),new StateDocument(instance(),DocumentKind.CENTRAL_DELIVERY,id,prior.map(StateDocument::version).orElse(0L),delivery))).getFirst();
    }
    public List<JsonNode> deliveries(String model,String key,AuthorizationSubject subject,String after) {
        incoming.administrator(model,key,subject);
        return metadata().list(instance(),DocumentKind.CENTRAL_DELIVERY,deliveryPrefix(model,key),after==null ? "" : after,100).stream().map(d -> { var data=(ObjectNode)d.data(); data.put("id",d.id()); return (JsonNode)data; }).toList();
    }
    public ExternalChangeIngestService.Inspection inspectDelivery(String model,String key,AuthorizationSubject subject,String element,String id) { return inbound.inspectProposal(model,key,subject,element,id); }
    public void resolveDelivery(String model,String key,AuthorizationSubject subject,String element,String id,String digest) {
        var target=incoming.administrator(model,key,subject);
        if (!execution.tryLock()) throw new Failure(409,"CENTRAL_EDIT_BUSY");
        try {
            runtime.requireRunning();
            var current=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow();
            if (!Set.of("PARTIAL","SYNCED").contains(current.data().path("state").asText())) throw new Failure(409,"CENTRAL_FANOUT_NOT_COMPLETE");
            var binding=binding(model,element,target); var inspected=inbound.inspectProposal(model,key,subject,element,id);
            if (!inspected.digest().equals(digest)) throw new Failure(409,"INBOUND_INSPECTION_STALE");
            var result=inbound.applyProposal(id,binding,digest); var job=load(DocumentKind.CENTRAL_EDIT,id).orElseThrow();
            var before=job.data().path("cursor").asText(""); job=recordDelivery(job,binding,result); var data=(ObjectNode)job.data(); data.put("cursor",before).put("state",data.path("pending").asInt()+data.path("conflicts").asInt()==0 ? "SYNCED" : "PARTIAL");
            metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),data));
        } finally { execution.unlock(); }
    }

    private record Frame(IntegrationConfiguration.Target target,ReservedImport binding,StateDocument policy,ExternalRepositoryConnector.ExternalObject card,JsonNode snapshot,JsonNode baseline,Context context) { }
    private Frame frame(String model,String key,String element,AuthorizationSubject subject,boolean approving) {
        var target=approving ? authorization.require(model,key,subject,true) : authorization.read(model,key,subject);
        var policy=incoming.capability(model,key); var binding=binding(model,element,target); runtime.validateTarget(key); var card=read(target,binding.external().resourceId(),binding.canonical().kind());
        var snapshot=models.getSnapshot(model,"HEAD"); validateLocal(binding,snapshot); var mappings=CentralFields.mappings(target,binding.canonical().kind());
        var remote=CentralFields.remote(json,target,card,mappings.keySet()); var local=InboundFields.local(json,snapshot,element,mappings.keySet()); var base=inbound.baseline(binding,target,mappings.keySet()); var fields=new ArrayList<Field>();
        for (String field:new TreeSet<>(mappings.keySet())) { var mapping=mappings.get(field); fields.add(new Field(field,mapping.remoteField(),mapping.valueType().name(),mapping.authority().name(),target.centralEditAccess().fields().get(mapping.remoteField()).name(),remote.path(field),local.path(field),base.get(field),CentralFields.display(remote.path(field)))); }
        return new Frame(target,binding,policy,card,snapshot,base,new Context(key,element,binding.canonical().canonicalId(),binding.external().resourceId(),binding.canonical().kind(),target.mapping().version(),List.copyOf(fields)));
    }
    private Preview preview(Frame frame,JsonNode proposed) {
        var selected=CentralFields.keys(proposed); var fields=frame.context().fields().stream().filter(f -> selected.contains(f.field())).toList();
        var context=new Context(frame.context().targetKey(),frame.context().elementId(),frame.context().canonicalId(),frame.context().resourceId(),frame.context().type(),frame.context().mappingVersion(),fields);
        var remote=CentralFields.remote(json,frame.target(),frame.card(),selected); var local=InboundFields.local(json,frame.snapshot(),frame.binding().model().entityId(),selected);
        var normalized=CentralFields.localRepresentation(json,remote); var drift=new ArrayList<String>();
        for (String field:selected) if (!frame.baseline().has(field) || !PublicationJson.equal(frame.baseline().path(field),normalized.path(field)) || !PublicationJson.equal(local.path(field),frame.baseline().path(field))) drift.add(field);
        String mode=fields.stream().anyMatch(f -> f.mode().equals("PROPOSAL")) ? "PROPOSAL" : "WRITE_THROUGH";
        String digest=PublicationJson.digest(json.valueToTree(List.of(context,proposed,mode,drift,frame.policy().version(),frame.snapshot().path("headRevision"),CentralEditAuthorization.digest(frame.target()))));
        return new Preview(context,json.convertValue(proposed,new com.fasterxml.jackson.core.type.TypeReference<Map<String,JsonNode>>(){}),CentralFields.displayMap(proposed),mode,List.copyOf(drift),digest);
    }
    private Review review(StateDocument job,Frame frame) {
        var preview=preview(frame,job.data().path("proposed"));
        boolean changed=!PublicationJson.equal(CentralFields.remote(json,frame.target(),frame.card(),CentralFields.keys(job.data().path("proposed"))),job.data().path("baseRemote"))
                || !PublicationJson.equal(InboundFields.local(json,frame.snapshot(),frame.binding().model().entityId(),CentralFields.keys(job.data().path("proposed"))),job.data().path("baseLocal"));
        String digest=PublicationJson.digest(json.valueToTree(List.of(job.id(),job.version(),preview.digest(),changed)));
        return new Review(job.id(),job.version(),preview,job.data().path("state").asText(),changed,digest);
    }
    private StateDocument enqueue(StateDocument job,IntegrationConfiguration.Target target) {
        var lane=lane(job); if (lane.data().has("inFlight")) throw new Failure(409,"CENTRAL_OUTCOME_UNCERTAIN");
        execution.requireCentralAllowed(runtime,target.instanceId(),job.data().path("canonicalId").asText());
        var data=(ObjectNode)lane.data(); data.put("jobId",job.id()).put("generation",data.path("generation").asLong()+1);
        return metadata().saveAtomically(List.of(job,new StateDocument(instance(),lane.kind(),lane.id(),lane.version(),data))).getFirst();
    }
    private StateDocument verified(StateDocument job,StateDocument lane) {
        var data=(ObjectNode)job.data(); data.put("remoteVerified",true).put("state","REMOTE_VERIFIED").put("verifiedAt",now.getAsLong()).put("cursor","").put("nextAttemptAt",0); data.remove("code");
        var fence=(ObjectNode)lane.data(); fence.remove("inFlight");
        var saved=metadata().saveAtomically(List.of(new StateDocument(instance(),job.kind(),job.id(),job.version(),data),new StateDocument(instance(),lane.kind(),lane.id(),lane.version(),fence))).getFirst(); audit("CentralRemoteVerified",saved,"external-central-worker"); return saved;
    }
    private void definitiveFailure(StateDocument job,StateDocument lane,ConnectorException failure) {
        var data=retryData(job,failure); var fence=(ObjectNode)lane.data(); fence.remove("inFlight");
        metadata().saveAtomically(List.of(new StateDocument(instance(),job.kind(),job.id(),job.version(),data),new StateDocument(instance(),lane.kind(),lane.id(),lane.version(),fence)));
    }
    private void retry(StateDocument job,ConnectorException failure) { metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),retryData(job,failure))); }
    private ObjectNode retryData(StateDocument job,ConnectorException failure) {
        var data=(ObjectNode)job.data(); int attempts=data.path("attempts").asInt()+1; boolean retry=failure.retryable() && attempts<4;
        data.put("attempts",attempts).put("state",retry ? "RETRY" : "ERROR").put("code","EXTERNAL_"+failure.kind()).put("nextAttemptAt",now.getAsLong()+Math.max(failure.retryAfter().toMillis(),(1L<<Math.min(attempts,4))*1000+java.util.concurrent.ThreadLocalRandom.current().nextLong(1000))); return data;
    }
    private StateDocument save(StateDocument job,String state,String code) { var data=(ObjectNode)job.data(); data.put("state",state); if (code==null) data.remove("code"); else data.put("code",code); return metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),data)); }
    private StateDocument lane(StateDocument job) { String id=laneId(job.data().path("targetInstanceId").asText(),job.data().path("canonicalId").asText()); return load(DocumentKind.CENTRAL_LANE,id).orElse(new StateDocument(instance(),DocumentKind.CENTRAL_LANE,id,0,json.createObjectNode())); }
    private StateDocument job(String model,String key,String id,IntegrationConfiguration.Target target) { var job=load(DocumentKind.CENTRAL_EDIT,IntegrationIdentity.required(id)).orElseThrow(() -> new Failure(404,"CENTRAL_REQUEST_NOT_FOUND")); if (!job.data().path("sourceModelId").asText().equals(model) || !job.data().path("targetKey").asText().equals(key)) throw new Failure(404,"CENTRAL_REQUEST_NOT_FOUND"); checkScope(job,target); return job; }
    private void checkScope(StateDocument job,IntegrationConfiguration.Target target) { if (!job.data().path("scopeDigest").asText().equals(CentralEditAuthorization.digest(target))) throw new Failure(409,"CENTRAL_CONFIGURATION_CHANGED"); }
    private IntegrationConfiguration.Target target(String key) { return runtime.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new Failure(404,"TARGET_NOT_FOUND")); }
    private ReservedImport binding(String model,String element,IntegrationConfiguration.Target target) { var b=metadata().findBinding(new ModelElementKey(instance(),model,EntityKind.ELEMENT,element)).orElseThrow(() -> new Failure(404,"BINDING_NOT_FOUND")); if (b.state()!=BindingState.ACTIVE || !b.external().targetInstanceId().equals(target.instanceId()) || !b.mappingVersion().equals(target.mapping().version()) || !b.archimateType().equals(target.mapping().archimateType(b.canonical().kind()))) throw new Failure(409,"CENTRAL_BINDING_DRIFT"); return b; }
    private void validateJobBinding(StateDocument job,ReservedImport binding) { if (!job.data().path("canonicalId").asText().equals(binding.canonical().canonicalId()) || !job.data().path("resourceId").asText().equals(binding.external().resourceId())) throw new Failure(409,"CENTRAL_BINDING_CHANGED"); }
    private void validateLocal(ReservedImport binding,JsonNode snapshot) { if (!InboundFields.element(snapshot,binding.model().entityId()).path("archimateType").asText().equals(binding.archimateType())) throw new Failure(409,"LOCAL_TYPE_CONFLICT"); if (!binding.bindingMode().equals("PUBLISHED")) { var marker=InboundFields.local(json,snapshot,binding.model().entityId(),Set.of("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY)); if (!marker.path("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY).asText().equals(binding.canonical().canonicalId())) throw new Failure(409,"LOCAL_PROVENANCE_DRIFT"); } }
    private ExternalRepositoryConnector.ExternalObject read(IntegrationConfiguration.Target target,String resource,String type) { var card=runtime.connector(target.key()).getObject(resource); if (!card.type().equals(type) || !"ACTIVE".equals(card.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT"); return card; }
    private void requireWrites(IntegrationConfiguration.Target target,String type,JsonNode proposed) {
        if (!target.centralEditAccess().acceptUnconditionalWriteRisk()) throw new Failure(409,"UNCONDITIONAL_WRITE_RISK_NOT_APPROVED");
        var mappings=CentralFields.mappings(target,type);
        for (String field:CentralFields.keys(proposed)) if (mappings.get(field).remoteField().startsWith("attributes.") && !target.centralEditAccess().completeAttributeReadsApproved()) throw new Failure(409,"COMPLETE_ATTRIBUTE_READS_NOT_APPROVED");
    }
    private Job view(StateDocument job) { var d=job.data(); return new Job(job.id(),job.version(),d.path("state").asText(),d.path("code").asText(null),d.path("elementId").asText(),d.path("canonicalId").asText(),d.path("resourceId").asText(),d.path("requestedBy").asText(),d.path("approvedBy").asText(null),d.path("mode").asText(),d.path("remoteVerified").asBoolean(),d.path("proposed"),CentralFields.displayMap(d.path("proposed")),d.path("synced").asInt(),d.path("pending").asInt(),d.path("conflicts").asInt(),d.path("sourceState").asText(null)); }
    private String bucket(String state) { return state.equals("SYNCED") ? "synced" : Set.of("PAUSED","RETRY","PENDING").contains(state) ? "pending" : "conflicts"; }
    private ObjectNode select(JsonNode object,Set<String> fields) { var result=json.createObjectNode(); for (String field:fields) if (object.has(field)) result.set(field,object.path(field)); return result; }
    private String deliveryPrefix(String model,String target) { return PublicationJson.key("central-delivery:",model,target)+":"; }
    private AuthorizationSubject subject(String user) { return new AuthorizationSubject(user,Set.of()); }
    private IntegrationStateRepository metadata() { return runtime.metadata(); }
    private String instance() { return runtime.settings().instanceId(); }
    private Optional<StateDocument> load(DocumentKind kind,String id) { return metadata().load(instance(),kind,id); }
    private void audit(String action,StateDocument job,String actor) { org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action={} actor={} model={} canonical={} resource={} correlation={} fields={} decision={}",action,safe(actor),safe(job.data().path("sourceModelId").asText()),safe(job.data().path("canonicalId").asText()),safe(job.data().path("resourceId").asText()),safe(job.id()),CentralFields.keys(job.data().path("proposed")),job.data().path("state").asText()); }
    private String safe(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
}
