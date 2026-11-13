package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.service.ArchimeshService;
import org.gautelis.archimesh.service.CommitDeliveryService;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.*;

/** Durable, selective outbound reconciliation. No model mutations and no remote HTTP in model transactions. */
@ApplicationScoped
public class ArchitecturePublicationService {
    @Inject IntegrationRuntime runtime;
    @Inject ArchimeshService models;
    @Inject AuthorizationService authorization;
    @Inject CommitDeliveryService delivery;
    @Inject ObjectMapper json;
    @Inject RemoteMutationCoordinator execution=new RemoteMutationCoordinator();
    LongSupplier now=System::currentTimeMillis;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="app.integrations.publication.interval-seconds",defaultValue="300") long reconciliationInterval=300;

    public record PolicyView(long version,PolicySpec policy,String approvedBy) { }
    public record ResourceView(String entityId,String canonicalId,String resourceId,String state,String code,JsonNode baseline) { }
    public record Status(PolicyView policy,JsonNode job,List<ResourceView> resources) { }
    public record Inspection(String canonicalId,String resourceId,JsonNode desired,JsonNode remote,String remoteDigest,String state) { }

    public List<String> targets(String model,AuthorizationSubject subject) {
        authorization.requireSubjectAllowed(subject,AuthorizationAction.ADMIN_MODEL_PUBLICATION,model,"HEAD");
        var acl=models.findModelAccessControl(model).orElseThrow(() -> new Failure(404,"MODEL_NOT_FOUND"));
        if (!acl.adminUsers().contains(subject.userId())) throw new Failure(403,"EXPLICIT_MODEL_ADMIN_APPROVAL_REQUIRED");
        if (!runtime.enabled()) return List.of();
        return runtime.settings().targets().stream().filter(t -> t.publicationAccess().enabled() && t.publicationAccess().publishers().contains(subject.userId())).map(IntegrationConfiguration.Target::key).sorted().toList();
    }

    public Preview preview(String model,String targetKey,AuthorizationSubject subject,PolicySpec spec) {
        var target=authorize(model,targetKey,subject);
        if (spec==null) throw new Failure(400,"PUBLICATION_POLICY_REQUIRED");
        if (spec.mode()!=Mode.DISABLED && (spec.mode()!=Mode.TAG || "HEAD".equals(spec.ref())))
            throw new Failure(403,"PUBLICATION_TAG_REQUIRED");
        var snapshot=spec.mode()==Mode.DISABLED ? json.createObjectNode() : models.getSnapshot(model,spec.ref());
        var plan=new ArchitectureProjection(json).build(instance(),model,target,spec,snapshot,metadata());
        return new Preview(spec,plan,PublicationJson.digest(json.valueToTree(List.of(spec,plan,runtime.requireModelActive(model),configurationDigest(target)))));
    }
    public PolicyView policy(String model,String targetKey,AuthorizationSubject subject) {
        authorize(model,targetKey,subject); return policyView(load(DocumentKind.PUBLICATION_POLICY,policyId(model,targetKey)));
    }
    public synchronized PolicyView approve(String model,String targetKey,AuthorizationSubject subject,Approval approval) {
        if (!execution.tryLock()) throw new PublicationModel.Failure(409,"INTEGRATION_BUSY");
        try {
            runtime.requireRunning();
            if (approval==null || approval.policy()==null || approval.expectedVersion()<0) throw new Failure(400,"INVALID_PUBLICATION_APPROVAL");
            var reviewed=preview(model,targetKey,subject,approval.policy());
            if (!reviewed.digest().equals(approval.previewDigest())) throw new Failure(409,"PUBLICATION_PREVIEW_STALE");
            var data=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",model).put("targetKey",targetKey)
                    .put("targetInstanceId",reviewed.plan().targetInstanceId()).put("approvedBy",subject.userId()).put("modelGeneration",runtime.requireModelActive(model)).put("configurationDigest",configurationDigest(authorize(model,targetKey,subject)));
            data.set("policy",json.valueToTree(approval.policy()));
            data.put("approvedPlanDigest",PublicationJson.digest(json.valueToTree(reviewed.plan())));
            var saved=metadata().save(new StateDocument(instance(),DocumentKind.PUBLICATION_POLICY,policyId(model,targetKey),approval.expectedVersion(),data));
            return policyView(Optional.of(saved));

        } finally { execution.unlock(); }
    }
    public synchronized StateDocument enqueue(String model,String targetKey,AuthorizationSubject subject,RunRequest request) {
        if (!execution.tryLock()) throw new PublicationModel.Failure(409,"INTEGRATION_BUSY");
        try {
            runtime.requireRunning();
            authorize(model,targetKey,subject); delivery.requireWriterLease();
            if (request==null || request.requestId()==null || !UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId()))
                throw new Failure(400,"VALID_PUBLICATION_REQUEST_ID_REQUIRED");
            String id=PublicationJson.key("pub-job:",model,targetKey,request.requestId());
            var existing=load(DocumentKind.PUBLICATION_JOB,id);
            if (existing.isPresent()) {
                if (!existing.get().data().path("requestedBy").asText().equals(subject.userId())
                        || !Objects.equals(existing.get().data().path("requestDigest").asText(null),request.previewDigest()))
                    throw new Failure(409,"PUBLICATION_REQUEST_ID_REUSED");
                return existing.get();
            }
            var policy=load(DocumentKind.PUBLICATION_POLICY,policyId(model,targetKey)).orElseThrow(() -> new Failure(409,"PUBLICATION_DISABLED"));
            if (!policy.data().path("targetInstanceId").asText().equals(runtime.settings().targets().stream().filter(t -> t.key().equals(targetKey)).findFirst().orElseThrow().instanceId()))
                throw new Failure(409,"TARGET_MIGRATION_REQUIRES_APPROVAL");
            runtime.requireGeneration(model,policy.data().path("modelGeneration").asText(null));
            if (!configurationDigest(authorize(model,targetKey,subject)).equals(policy.data().path("configurationDigest").asText())) throw new Failure(409,"PUBLICATION_CONFIGURATION_CHANGED");
            var spec=json.convertValue(policy.data().path("policy"),PolicySpec.class);
            if (spec.mode()==Mode.DISABLED) throw new Failure(409,"PUBLICATION_DISABLED");
            // Scheduled capabilities remain tied to a currently authorized, explicitly named approver.
            authorize(model,targetKey,new AuthorizationSubject(policy.data().path("approvedBy").asText(),Set.of()));
            var reviewed=preview(model,targetKey,subject,spec);
            if (!policy.data().path("approvedPlanDigest").asText().equals(PublicationJson.digest(json.valueToTree(reviewed.plan()))))
                throw new Failure(409,"PUBLICATION_TAG_APPROVAL_STALE");
            if (request.previewDigest()!=null && !request.previewDigest().equals(reviewed.digest())) throw new Failure(409,"PUBLICATION_PREVIEW_STALE");
            for (var resource:reviewed.plan().resources()) metadata().reservePublicationIdentity(resource.identity());
            var job=json.createObjectNode().put("schemaVersion",1).put("sourceModelId",model).put("targetKey",targetKey)
                    .put("modelGeneration",runtime.requireModelActive(model)).put("requestId",request.requestId()).put("requestedBy",subject.userId()).put("policyVersion",policy.version())
                    .put("state","PENDING").put("attempts",0).put("nextAttemptAt",0).put("requestDigest",request.previewDigest());
            job.set("plan",json.valueToTree(reviewed.plan()));
            var saved=metadata().save(new StateDocument(instance(),DocumentKind.PUBLICATION_JOB,id,0,job));
            var pointer=load(DocumentKind.CHECKPOINT,scopeId(model,targetKey));
            if (pointer.isPresent() && (pointer.get().data().path("policyVersion").asLong()>policy.version()
                    || pointer.get().data().path("policyVersion").asLong()==policy.version() && pointer.get().data().path("revision").asLong()>reviewed.plan().revision()))
                return saveJob(saved,"SUPERSEDED",null);
            var current=json.createObjectNode().put("jobId",id).put("policyVersion",policy.version()).put("revision",reviewed.plan().revision())
                    .put("planDigest",PublicationJson.digest(json.valueToTree(reviewed.plan())));
            metadata().save(new StateDocument(instance(),DocumentKind.CHECKPOINT,scopeId(model,targetKey),pointer.map(StateDocument::version).orElse(0L),current));
            return saved;

        } finally { execution.unlock(); }
    }

    /** One globally serialized remote execution, stronger than per-model/per-canonical ordering. */
    public void runJob(String id) {
        if (!execution.tryLock()) return;
        try {
            var document=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow();
            if (!Set.of("PENDING","RETRY","RUNNING").contains(document.data().path("state").asText()) || document.data().path("nextAttemptAt").asLong()>now.getAsLong()) return;
            runtime.requireGeneration(document.data().path("sourceModelId").asText(),document.data().path("modelGeneration").asText(null));
            var plan=json.convertValue(document.data().path("plan"),Plan.class);
            if (!current(document,plan)) { saveJob(document,"SUPERSEDED",null); return; }
            String approver=load(DocumentKind.PUBLICATION_POLICY,policyId(plan.modelId(),plan.targetKey())).orElseThrow().data().path("approvedBy").asText();
            var target=authorize(plan.modelId(),plan.targetKey(),new AuthorizationSubject(approver,Set.of()));
            if (!configurationDigest(target).equals(load(DocumentKind.PUBLICATION_POLICY,policyId(plan.modelId(),plan.targetKey())).orElseThrow().data().path("configurationDigest").asText())) throw new Failure(409,"PUBLICATION_CONFIGURATION_CHANGED");
            runtime.validateTarget(plan.targetKey()); delivery.requireWriterLease();
            if (!plan.mappingVersion().equals(target.mapping().version()) || !plan.targetInstanceId().equals(target.instanceId())) throw new Failure(409,"MAPPING_OR_TARGET_MIGRATION_REQUIRED");
            document=saveJob(document,"RUNNING",null);
            var writer=runtime.writer(plan.targetKey()); var endpoints=new HashMap<String,String>();
            for (var resource:plan.resources()) {
                if (!current(document,plan)) { saveJob(document,"SUPERSEDED",null); return; }
                delivery.requireWriterLease(); authorize(plan.modelId(),plan.targetKey(),new AuthorizationSubject(approver,Set.of()));
                String remote=resource.identity().model().entityKind()==EntityKind.ELEMENT
                        ? publishCard(document,plan,resource,target,writer) : publishRelation(document,plan,resource,target,writer,endpoints);
                endpoints.put(resource.identity().model().entityId(),remote);
                contribution(plan,resource,"ACTIVE");
            }
            if (!current(document,plan)) { saveJob(document,"SUPERSEDED",null); return; }
            withdrawMissing(plan); saveJob(document,"SYNCED",null);
        } catch (ConnectorException error) {
            var job=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow();
            int attempts=job.data().path("attempts").asInt()+1;
            boolean retry=error.retryable() && attempts<4;
            var data=(ObjectNode)job.data(); data.put("attempts",attempts).put("state",retry ? "RETRY" : "ERROR")
                    .put("code","EXTERNAL_"+error.kind()).put("nextAttemptAt",now.getAsLong()+Math.max(error.retryAfter().toMillis(),
                            (1L<<attempts)*1000+java.util.concurrent.ThreadLocalRandom.current().nextLong(1000)));
            metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),data));
        } catch (Failure error) {
            if (error.getMessage().equals("INTEGRATION_PAUSED")) return;
            var job=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow(); saveJob(job,error.status()==403 || error.status()==404 ? "PAUSED" : error.getMessage().contains("AMBIGUOUS") ? "AMBIGUOUS" : "CONFLICT",error.getMessage());
            var conflict=json.createObjectNode().put("sourceModelId",job.data().path("sourceModelId").asText()).put("jobId",id)
                    .put("reason",error.getMessage()).put("state","OPEN");
            var prior=load(DocumentKind.CONFLICT,id);
            metadata().save(new StateDocument(instance(),DocumentKind.CONFLICT,id,prior.map(StateDocument::version).orElse(0L),conflict));
        } catch (jakarta.ws.rs.WebApplicationException denied) {
            var job=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow(); saveJob(job,"PAUSED","PUBLICATION_AUTHORIZATION_REVOKED");
        } catch (IllegalArgumentException mapping) {
            var job=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow(); saveJob(job,"ERROR","PUBLICATION_MAPPING_INVALID");
        } catch (RuntimeException unavailable) {
            // A crash/failure after dispatch leaves the resource's durable IN_FLIGHT intent intact.
            var job=load(DocumentKind.PUBLICATION_JOB,id).orElseThrow(); saveJob(job,"ERROR","PUBLICATION_STATE_UNAVAILABLE");
        } finally { execution.unlock(); }
    }

    private String publishCard(StateDocument job,Plan plan,Resource resource,IntegrationConfiguration.Target target,ExternalRepositoryWriter writer) {
        execution.requirePublicationAllowed(runtime,target.instanceId(),resource.identity().canonical().canonicalId());
        var state=resourceState(plan,resource); var data=(ObjectNode)state.data();
        ensureNoUnknownMutation(data);
        var binding=metadata().findExternal(resource.identity().canonical().canonicalId(),"turboea",target.instanceId(),ResourceKind.CARD);
        if (binding.isEmpty()) {
            requireSteward(plan,resource,data);
            if (!resource.fields().containsKey("name")) throw new Failure(409,"PUBLICATION_NAME_AUTHORITY_REQUIRED");
            var payload=cardPayload(resource.fields(),null); payload.put("type",resource.remoteType()).put("external_id",resource.identity().canonical().canonicalId()).put("strict_attributes",true);
            state=inFlight(state,"CREATE",resource.fields());
            ExternalRepositoryConnector.ExternalObject created;
            try { created=writer.createObject(payload); }
            catch (ConnectorException error) { mutationFailed(state,error); throw error; }
            if (!resource.remoteType().equals(created.type()) || !resource.identity().canonical().canonicalId().equals(created.externalId())) throw new Failure(409,"AMBIGUOUS_CREATE_RESPONSE");
            var verified=readCard(writer,created.id());
            if (!PublicationJson.equal(flat(verified,resource.fields().keySet()),json.valueToTree(resource.fields()))) throw new Failure(409,"AMBIGUOUS_CREATE_VALUES");
            recordIdentity(plan,resource,created.id()); verified(state,resource,created.id(),flat(verified,resource.fields().keySet()));
            return created.id();
        }
        String remoteId=binding.get().resourceId(); ExternalRepositoryConnector.ExternalObject remote;
        try { remote=readCard(writer,remoteId); }
        catch (Failure orphaned) { throw resourceConflict(state,remoteId,orphaned.getMessage()); }
        if (!resource.remoteType().equals(remote.type()) || !"ACTIVE".equals(remote.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT");
        var actual=flat(remote,resource.fields().keySet()); var desired=json.valueToTree(resource.fields());
        if (!resource.steward() || !plan.modelId().equals(data.path("ownerModel").asText())) {
            if (!PublicationJson.equal(actual,desired)) throw new Failure(409,"CANONICAL_CONTRIBUTION_CONFLICT");
            recordIdentity(plan,resource,remoteId);
            return remoteId;
        }
        if (!data.has("baseline")) {
            if (!PublicationJson.equal(actual,desired)) throw resourceConflict(state,remoteId,"BASELINE_APPROVAL_REQUIRED");
            verified(state,resource,remoteId,actual); recordIdentity(plan,resource,remoteId); return remoteId;
        }
        if (!PublicationJson.equal(data.path("baseline"),actual)) throw resourceConflict(state,remoteId,"REMOTE_DRIFT");
        if (PublicationJson.equal(actual,desired)) { verified(state,resource,remoteId,actual); recordIdentity(plan,resource,remoteId); return remoteId; }
        if (!target.publicationAccess().acceptUnconditionalWriteRisk()) throw resourceConflict(state,remoteId,"UNCONDITIONAL_WRITE_RISK_NOT_APPROVED");
        // Read again immediately before constructing replacement maps; compare only owned fields.
        remote=readCard(writer,remoteId); actual=flat(remote,resource.fields().keySet());
        if (!PublicationJson.equal(data.path("baseline"),actual)) throw resourceConflict(state,remoteId,"REMOTE_DRIFT");
        state=inFlight(state,"PATCH",resource.fields());
        try { writer.patchObject(remoteId,cardPayload(resource.fields(),remote)); }
        catch (ConnectorException error) { mutationFailed(state,error); throw error; }
        var after=flat(readCard(writer,remoteId),resource.fields().keySet());
        if (!PublicationJson.equal(after,desired)) throw new Failure(409,"AMBIGUOUS_PATCH_VERIFICATION");
        verified(state,resource,remoteId,after); recordIdentity(plan,resource,remoteId); return remoteId;
    }

    private String publishRelation(StateDocument job,Plan plan,Resource resource,IntegrationConfiguration.Target target,
                                   ExternalRepositoryWriter writer,Map<String,String> endpoints) {
        execution.requirePublicationAllowed(runtime,target.instanceId(),resource.identity().canonical().canonicalId(),org.gautelis.federation.Identity.ResourceKind.RELATIONSHIP);
        String source=endpoints.get(resource.sourceEntityId()),destination=endpoints.get(resource.targetEntityId());
        if (source==null || destination==null) throw new Failure(409,"PUBLISH_ENDPOINTS_FIRST");
        var state=resourceState(plan,resource); var data=(ObjectNode)state.data(); ensureNoUnknownMutation(data);
        var external=metadata().findExternal(resource.identity().canonical().canonicalId(),"turboea",target.instanceId(),ResourceKind.RELATION);
        var relations=writer.relations(source);
        if (external.isPresent()) {
            var actual=relations.stream().filter(r -> r.id().equals(external.get().resourceId())).findFirst().orElseThrow(() -> new Failure(409,"ORPHANED_RELATION"));
            if (!actual.type().equals(resource.remoteType()) || !actual.sourceId().equals(source) || !actual.targetId().equals(destination)) throw new Failure(409,"RELATION_ENDPOINT_DRIFT");
            verified(state,resource,actual.id(),relationFields(resource,source,destination)); recordIdentity(plan,resource,actual.id()); return actual.id();
        }
        requireSteward(plan,resource,data);
        if (relations.stream().anyMatch(r -> r.type().equals(resource.remoteType()) && r.sourceId().equals(source) && r.targetId().equals(destination)))
            throw resourceConflict(state,null,"EXISTING_RELATION_REQUIRES_EXPLICIT_LINK");
        state=inFlight(state,"CREATE",json.convertValue(relationFields(resource,source,destination),new com.fasterxml.jackson.core.type.TypeReference<SortedMap<String,JsonNode>>(){}));
        ExternalRepositoryConnector.ExternalRelation created;
        try { created=writer.createRelation(resource.remoteType(),source,destination); }
        catch (ConnectorException error) { mutationFailed(state,error); throw error; }
        boolean found=writer.relations(source).stream().anyMatch(r -> r.id().equals(created.id()) && r.type().equals(resource.remoteType()) && r.sourceId().equals(source) && r.targetId().equals(destination));
        if (!found) throw new Failure(409,"AMBIGUOUS_RELATION_VERIFICATION");
        recordIdentity(plan,resource,created.id()); verified(state,resource,created.id(),relationFields(resource,source,destination)); return created.id();
    }

    public Status status(String model,String target,AuthorizationSubject subject) {
        var settings=authorize(model,target,subject); var policy=policy(model,target,subject); var pointer=load(DocumentKind.CHECKPOINT,scopeId(model,target));
        if (pointer.isEmpty()) return new Status(policy,null,List.of());
        var job=load(DocumentKind.PUBLICATION_JOB,pointer.get().data().path("jobId").asText());
        if (job.isEmpty()) return new Status(policy,null,List.of());
        var plan=json.convertValue(job.get().data().path("plan"),Plan.class); var resources=new ArrayList<ResourceView>();
        if (!plan.targetInstanceId().equals(settings.instanceId())) throw new Failure(409,"TARGET_MIGRATION_REQUIRES_APPROVAL");
        for (var resource:plan.resources()) {
            var state=load(DocumentKind.PUBLICATION_RESOURCE,resourceId(plan,resource));
            var external=metadata().findExternal(resource.identity().canonical().canonicalId(),"turboea",plan.targetInstanceId(),resource.identity().model().entityKind()==EntityKind.ELEMENT ? ResourceKind.CARD : ResourceKind.RELATION);
            resources.add(new ResourceView(resource.identity().model().entityId(),resource.identity().canonical().canonicalId(),
                    state.map(s -> s.data().path("resourceId").asText(null)).orElseGet(() -> external.map(ExternalResourceKey::resourceId).orElse(null)),state.map(s -> s.data().path("state").asText()).orElse("PENDING"),
                    state.map(s -> s.data().path("code").asText(null)).orElse(null),state.map(s -> s.data().path("baseline")).orElse(null)));
        }
        var summary=(ObjectNode)job.get().data(); summary.remove("plan"); summary.put("id",job.get().id());
        return new Status(policy,summary,List.copyOf(resources));
    }
    public Inspection inspect(String model,String target,AuthorizationSubject subject,String canonical,String suppliedId) {
        authorize(model,target,subject); var plan=latestPlan(model,target); var resource=selected(plan,canonical);
        var state=load(DocumentKind.PUBLICATION_RESOURCE,resourceId(plan,resource)).orElseThrow(() -> new Failure(404,"PUBLICATION_RESOURCE_NOT_FOUND"));
        String remoteId=suppliedId==null || suppliedId.isBlank() ? state.data().path("resourceId").asText(null) : required(suppliedId);
        if (remoteId==null) remoteId=metadata().findExternal(canonical,"turboea",plan.targetInstanceId(),resource.identity().model().entityKind()==EntityKind.ELEMENT ? ResourceKind.CARD : ResourceKind.RELATION).map(ExternalResourceKey::resourceId).orElse(null);
        if (remoteId==null) throw new Failure(409,"SUPPLY_VERIFIED_REMOTE_RESOURCE_ID");
        var writer=runtime.writer(target); JsonNode remote;
        if (resource.identity().model().entityKind()==EntityKind.ELEMENT) {
            var card=readCard(writer,remoteId);
            if (!card.type().equals(resource.remoteType())) throw new Failure(409,"EXTERNAL_TYPE_CONFLICT");
            remote=flat(card,resource.fields().keySet());
        } else {
            String source=endpoint(plan,resource.sourceEntityId()),destination=endpoint(plan,resource.targetEntityId());
            String selectedId=remoteId;
            if (writer.relations(source).stream().noneMatch(r -> r.id().equals(selectedId) && r.type().equals(resource.remoteType()) && r.sourceId().equals(source) && r.targetId().equals(destination)))
                throw new Failure(409,"RELATION_IDENTITY_CONFLICT");
            remote=relationFields(resource,source,destination);
        }
        return new Inspection(canonical,remoteId,json.valueToTree(resource.fields()),remote,PublicationJson.digest(remote),state.data().path("state").asText());
    }
    public Inspection adopt(String model,String target,AuthorizationSubject subject,Adoption request) {
        authorize(model,target,subject); delivery.requireWriterLease();
        if (request==null) throw new Failure(400,"PUBLICATION_ADOPTION_REQUIRED");
        if (!execution.tryLock()) throw new Failure(409,"PUBLICATION_BUSY");
        try {
            var plan=latestPlan(model,target); var resource=selected(plan,request.canonicalId());
            var state=load(DocumentKind.PUBLICATION_RESOURCE,resourceId(plan,resource)).orElseThrow();
            requireSteward(plan,resource,state.data());
            var inspection=inspect(model,target,subject,request.canonicalId(),request.resourceId());
            if (!inspection.remoteDigest().equals(request.remoteDigest())) throw new Failure(409,"REMOTE_BASELINE_CHANGED");
            if (state.data().has("inFlight") && state.data().path("inFlight").path("action").asText().equals("CREATE")
                    && resource.identity().model().entityKind()==EntityKind.ELEMENT) {
                var card=readCard(runtime.writer(target),inspection.resourceId());
                if (!resource.identity().canonical().canonicalId().equals(card.externalId()) || !PublicationJson.equal(inspection.remote(),json.valueToTree(resource.fields())))
                    throw new Failure(409,"AMBIGUOUS_CREATE_IDENTITY_NOT_VERIFIED");
            }
            recordIdentity(plan,resource,inspection.resourceId()); verified(state,resource,inspection.resourceId(),inspection.remote());
            return inspection;
        } finally { execution.unlock(); }
    }

    private IntegrationConfiguration.Target authorize(String model,String key,AuthorizationSubject subject) {
        if (!runtime.enabled()) throw new Failure(404,"INTEGRATION_DISABLED"); required(model);
        authorization.requireSubjectAllowed(subject,AuthorizationAction.ADMIN_MODEL_PUBLICATION,model,"HEAD");
        var acl=models.findModelAccessControl(model).orElseThrow(() -> new Failure(404,"MODEL_NOT_FOUND"));
        if (!acl.adminUsers().contains(subject.userId())) throw new Failure(403,"EXPLICIT_MODEL_ADMIN_APPROVAL_REQUIRED");
        var target=runtime.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new Failure(404,"TARGET_NOT_FOUND"));
        if (!target.publicationAccess().enabled() || !target.publicationAccess().publishers().contains(subject.userId())) throw new Failure(403,"PUBLICATION_ACCESS_DENIED");
        runtime.requireModelActive(model);
        return target;
    }
    private String configurationDigest(IntegrationConfiguration.Target target) { return IntegrationConfigurationDigest.target(target); }
    private boolean current(StateDocument job,Plan plan) {
        var policy=load(DocumentKind.PUBLICATION_POLICY,policyId(plan.modelId(),plan.targetKey()));
        var pointer=load(DocumentKind.CHECKPOINT,scopeId(plan.modelId(),plan.targetKey()));
        if (policy.isEmpty() || !policy.get().data().path("policy").path("mode").asText().equals("TAG")
                || "HEAD".equals(plan.ref()) || models.getSnapshot(plan.modelId(),plan.ref()).path("headRevision").asLong()!=plan.revision()) return false;
        if (!policy.get().data().path("approvedPlanDigest").asText().equals(PublicationJson.digest(json.valueToTree(plan)))) return false;
        return policy.isPresent() && policy.get().version()==job.data().path("policyVersion").asLong()
                && policy.get().data().path("policy").path("mode").asText().equals("TAG")
                && pointer.isPresent() && pointer.get().data().path("jobId").asText().equals(job.id());
    }
    private StateDocument resourceState(Plan plan,Resource resource) {
        String id=resourceId(plan,resource); var existing=load(DocumentKind.PUBLICATION_RESOURCE,id);
        if (existing.isPresent()) {
            if (!existing.get().data().path("mappingVersion").asText().equals(plan.mappingVersion())) throw new Failure(409,"RESOURCE_MAPPING_MIGRATION_REQUIRED");
            if (resource.steward() && !plan.modelId().equals(existing.get().data().path("ownerModel").asText())) throw new Failure(409,"CANONICAL_STEWARD_CONFLICT");
            return existing.get();
        }
        if (!resource.steward()) throw new Failure(409,"EXPLICIT_STEWARD_REQUIRED");
        var data=json.createObjectNode().put("schemaVersion",1).put("ownerModel",plan.modelId()).put("canonicalId",resource.identity().canonical().canonicalId())
                .put("targetInstanceId",plan.targetInstanceId()).put("mappingVersion",plan.mappingVersion()).put("state","PENDING");
        return metadata().save(new StateDocument(instance(),DocumentKind.PUBLICATION_RESOURCE,id,0,data));
    }
    private void requireSteward(Plan plan,Resource resource,JsonNode data) {
        if (!resource.steward() || !plan.modelId().equals(data.path("ownerModel").asText())) throw new Failure(409,"CANONICAL_CONTRIBUTION_CONFLICT");
    }
    private void ensureNoUnknownMutation(JsonNode data) { if (data.has("inFlight")) throw new Failure(409,"AMBIGUOUS_REMOTE_MUTATION_REQUIRES_REVIEW"); }
    private StateDocument inFlight(StateDocument state,String action,SortedMap<String,JsonNode> desired) {
        var data=(ObjectNode)state.data(); data.put("state","IN_FLIGHT");
        var intent=data.putObject("inFlight").put("action",action).put("operationId",UUID.randomUUID().toString()); intent.set("desired",json.valueToTree(desired));
        return metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
    }
    private void mutationFailed(StateDocument state,ConnectorException error) {
        if (error.status()>=400 && error.status()<500) {
            var data=(ObjectNode)state.data(); data.remove("inFlight"); data.put("state","ERROR").put("code","EXTERNAL_"+error.kind());
            metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
        } else {
            var data=(ObjectNode)state.data(); data.put("state","AMBIGUOUS").put("code","AMBIGUOUS_REMOTE_MUTATION_REQUIRES_REVIEW");
            metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
            throw new Failure(409,"AMBIGUOUS_REMOTE_MUTATION_REQUIRES_REVIEW");
        }
    }
    private Failure resourceConflict(StateDocument state,String id,String code) {
        var data=(ObjectNode)state.data(); data.put("state",code.startsWith("ORPHANED") ? "ORPHANED" : "CONFLICT").put("code",code); if (id!=null) data.put("resourceId",id);
        metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data)); return new Failure(409,code);
    }
    private void verified(StateDocument state,Resource resource,String id,JsonNode baseline) {
        var data=(ObjectNode)state.data(); data.remove(List.of("inFlight","code")); data.put("state","SYNCED").put("resourceId",id)
                .put("verifiedAt",now.getAsLong()).put("digest",PublicationJson.digest(baseline)); data.set("baseline",PublicationJson.canonical(baseline));
        metadata().save(new StateDocument(instance(),state.kind(),state.id(),state.version(),data));
    }
    private void recordIdentity(Plan plan,Resource resource,String id) {
        metadata().recordPublishedIdentity(resource.identity(),new ExternalResourceKey("turboea",plan.targetInstanceId(),
                resource.identity().model().entityKind()==EntityKind.ELEMENT ? ResourceKind.CARD : ResourceKind.RELATION,id),plan.revision(),
                PublicationJson.key("publication:",plan.modelId(),resource.identity().model().entityId()));
    }
    private ObjectNode flat(ExternalRepositoryConnector.ExternalObject remote,Set<String> fields) {
        var result=json.createObjectNode();
        for (String field:new TreeSet<>(fields)) {
            JsonNode value=switch(field) {
                case "name" -> json.valueToTree(remote.name()); case "description" -> json.valueToTree(remote.description()); case "lifecycle" -> remote.lifecycle();
                default -> remote.attributes()==null ? null : remote.attributes().get(field.substring("attributes.".length()));
            };
            result.set(field,PublicationJson.field(value));
        }
        return result;
    }
    private ObjectNode cardPayload(Map<String,JsonNode> fields,ExternalRepositoryConnector.ExternalObject remote) {
        var result=json.createObjectNode(); ObjectNode attributes=null;
        for (var entry:fields.entrySet()) {
            if (entry.getKey().startsWith("attributes.")) {
                if (attributes==null) { attributes=remote!=null && remote.attributes()!=null ? (ObjectNode)remote.attributes().deepCopy() : json.createObjectNode(); result.set("attributes",attributes); }
                attributes.set(entry.getKey().substring("attributes.".length()),entry.getValue());
            } else result.set(entry.getKey(),entry.getValue());
        }
        return result;
    }
    private ExternalRepositoryConnector.ExternalObject readCard(ExternalRepositoryWriter writer,String id) {
        try { return writer.getObject(id); }
        catch (ConnectorException missing) { if (missing.kind()==ConnectorException.Kind.NOT_FOUND) throw new Failure(409,"ORPHANED_EXTERNAL_CARD"); throw missing; }
    }
    private ObjectNode relationFields(Resource resource,String source,String target) { return json.createObjectNode().put("type",resource.remoteType()).put("source_id",source).put("target_id",target); }
    private String endpoint(Plan plan,String id) {
        var resource=plan.resources().stream().filter(r -> r.identity().model().entityId().equals(id)).findFirst().orElseThrow();
        return metadata().findExternal(resource.identity().canonical().canonicalId(),"turboea",plan.targetInstanceId(),ResourceKind.CARD)
                .orElseThrow(() -> new Failure(409,"PUBLISH_ENDPOINTS_FIRST")).resourceId();
    }
    private void contribution(Plan plan,Resource resource,String status) {
        String id=contributionPrefix(plan)+PublicationJson.key("",resource.identity().model().entityId());
        var prior=load(DocumentKind.CONTRIBUTION,id);
        var data=json.createObjectNode().put("sourceModelId",plan.modelId()).put("entityId",resource.identity().model().entityId())
                .put("canonicalId",resource.identity().canonical().canonicalId()).put("targetInstanceId",plan.targetInstanceId()).put("ref",plan.ref())
                .put("revision",plan.revision()).put("mappingVersion",plan.mappingVersion()).put("status",status).put("digest",PublicationJson.digest(json.valueToTree(resource)));
        metadata().save(new StateDocument(instance(),DocumentKind.CONTRIBUTION,id,prior.map(StateDocument::version).orElse(0L),data));
    }
    private void withdrawMissing(Plan plan) {
        var selected=new HashSet<String>(); plan.resources().forEach(r -> selected.add(r.identity().model().entityId()));
        String after="";
        while (true) {
            var page=metadata().list(instance(),DocumentKind.CONTRIBUTION,contributionPrefix(plan),after,100);
            for (var document:page) {
                after=document.id();
                if (selected.contains(document.data().path("entityId").asText()) || document.data().path("status").asText().equals("WITHDRAWN")) continue;
                var data=(ObjectNode)document.data(); data.put("status","WITHDRAWN").put("withdrawnAtRevision",plan.revision());
                metadata().save(new StateDocument(instance(),document.kind(),document.id(),document.version(),data));
            }
            if (page.size()<100) break;
        }
        // Resource bindings and remote cards/relations deliberately survive withdrawal. M3 never DELETEs remote data.
    }
    private Plan latestPlan(String model,String target) {
        var pointer=load(DocumentKind.CHECKPOINT,scopeId(model,target)).orElseThrow(() -> new Failure(404,"PUBLICATION_JOB_NOT_FOUND"));
        var job=load(DocumentKind.PUBLICATION_JOB,pointer.data().path("jobId").asText()).orElseThrow();
        var plan=json.convertValue(job.data().path("plan"),Plan.class);
        if (!current(job,plan)) throw new Failure(409,"PUBLICATION_POLICY_CHANGED");
        var configured=runtime.settings().targets().stream().filter(t -> t.key().equals(target)).findFirst().orElseThrow();
        if (!configured.instanceId().equals(plan.targetInstanceId()) || !configured.mapping().version().equals(plan.mappingVersion()))
            throw new Failure(409,"MAPPING_OR_TARGET_MIGRATION_REQUIRED");
        return plan;
    }
    private Resource selected(Plan plan,String canonical) { return plan.resources().stream().filter(r -> r.identity().canonical().canonicalId().equals(canonical)).findFirst().orElseThrow(() -> new Failure(404,"RESOURCE_NOT_SELECTED")); }
    private PolicyView policyView(Optional<StateDocument> document) { return document.map(d -> new PolicyView(d.version(),json.convertValue(d.data().path("policy"),PolicySpec.class),d.data().path("approvedBy").asText())).orElse(new PolicyView(0,new PolicySpec(Mode.DISABLED,"HEAD",List.of(),List.of(),Set.of()),null)); }
    private StateDocument saveJob(StateDocument job,String state,String code) { var data=(ObjectNode)job.data(); data.put("state",state); if (state.equals("SYNCED")) data.put("verifiedAt",now.getAsLong()); if (code==null) data.remove("code"); else data.put("code",code); return metadata().save(new StateDocument(instance(),job.kind(),job.id(),job.version(),data)); }
    private Optional<StateDocument> load(DocumentKind kind,String id) { return metadata().load(instance(),kind,id); }
    private IntegrationStateRepository metadata() { return runtime.metadata(); }
    private String instance() { return runtime.settings().instanceId(); }
    private String policyId(String model,String target) { return PublicationJson.key("pub-policy:",model,target); }
    private String scopeId(String model,String target) { return PublicationJson.key("pub-scope:",model,target); }
    private String resourceId(Plan plan,Resource resource) { return PublicationJson.key("pub-resource:",plan.targetInstanceId(),resource.identity().canonical().canonicalId()); }
    private String contributionPrefix(Plan plan) { return PublicationJson.key("pub-contribution:",plan.modelId(),plan.targetInstanceId())+":"; }

    /** Polling is the repair path for missed/duplicated Kafka signals, retention and restarts. */
    void schedulePolicy(StateDocument policy) {
        var data=policy.data(); var spec=json.convertValue(data.path("policy"),PolicySpec.class);
        if (spec.mode()==Mode.DISABLED) return;
        String model=data.path("sourceModelId").asText(),target=data.path("targetKey").asText();
        var subject=new AuthorizationSubject(data.path("approvedBy").asText(),Set.of());
        var reviewed=preview(model,target,subject,spec); String digest=PublicationJson.digest(json.valueToTree(reviewed.plan()));
        var previous=load(DocumentKind.CHECKPOINT,scopeId(model,target));
        long intervalMillis=Math.max(5,reconciliationInterval)*1000;
        if (previous.isPresent() && previous.get().data().path("policyVersion").asLong()==policy.version()
                && previous.get().data().path("planDigest").asText().equals(digest)) {
            var job=load(DocumentKind.PUBLICATION_JOB,previous.get().data().path("jobId").asText());
            // Recheck remote drift even when the local projection has not changed. Conflicts require review.
            if (job.isEmpty() || !job.get().data().path("state").asText().equals("SYNCED")
                    || now.getAsLong()-job.get().data().path("verifiedAt").asLong()<intervalMillis) return;
        }
        String request=UUID.nameUUIDFromBytes((policy.id()+":"+policy.version()+":"+digest+":"+now.getAsLong()/intervalMillis).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        enqueue(model,target,subject,new RunRequest(request,null));
    }
}
