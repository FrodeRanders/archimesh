package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.service.*;
import java.time.Instant;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Explicit operations, independent from model history; remote probes never run under model serialization. */
@ApplicationScoped
public class IntegrationOperationsService {
    @Inject IntegrationRuntime runtime;
    @Inject AuthorizationService authorization;
    @Inject ArchimeshService models;
    @Inject CommitDeliveryService delivery;
    @Inject RemoteMutationCoordinator execution;
    @Inject ExternalChangeIngestService inbound;
    @Inject ArchitecturePublicationService publication;
    @Inject ObjectMapper json;
    public record Decision(long expectedVersion,String digest) { }
    public record Review(JsonNode details,String digest) { }
    public record ScanRequest(String requestId,String targetKey) { }
    public record RestoreRequest(IntegrationArchive.Package archive,String digest) { }
    public record IdentityClassification(String canonicalId,String semanticKind,String digest) { }
    public record DocumentPage(List<JsonNode> items,String nextCursor) { }

    public void requireOperator(AuthorizationSubject subject) {
        if (!runtime.enabled()) throw new Failure(404,"INTEGRATION_DISABLED");
        authorization.requireSubjectAllowed(subject,AuthorizationAction.ADMIN_INTEGRATION_OPERATIONS,null,null);
        if (!runtime.settings().operators().contains(subject.userId())) throw new Failure(403,"INTEGRATION_OPERATOR_REQUIRED");
    }
    public JsonNode manifest() {
        var targets=json.createArrayNode();
        runtime.settings().targets().stream().sorted(Comparator.comparing(IntegrationConfiguration.Target::key)).forEach(t -> targets.addObject()
                .put("key",t.key()).put("instanceId",t.instanceId()).put("apiVersion",t.expectedApiVersion()).put("mappingVersion",t.mapping().version())
                .put("configurationDigest",IntegrationConfigurationDigest.target(t)));
        return targets;
    }
    public JsonNode overview(AuthorizationSubject subject) {
        requireOperator(subject); var root=json.createObjectNode().put("instanceId",instance()).put("observedAt",Instant.now().toString());
        var control=control(); root.put("controlVersion",control.map(StateDocument::version).orElse(0L)); root.put("paused",control.map(d -> d.data().path("paused").asBoolean()).orElse(false)); root.set("targets",manifest());
        var configuration=root.putArray("configuration");
        for (var target:runtime.settings().targets()) {
            var item=configuration.addObject().put("key",target.key()); item.set("mapping",json.valueToTree(target.mapping()));
            item.set("catalogAccess",json.valueToTree(target.catalogAccess())); item.set("publicationAccess",json.valueToTree(target.publicationAccess()));
            item.set("inboundAccess",json.valueToTree(target.inboundAccess())); item.set("centralEditAccess",json.valueToTree(target.centralEditAccess()));
        }
        root.set("counts",runtime.metadata().statistics(instance())); return root;
    }
    public DocumentPage documents(AuthorizationSubject subject,DocumentKind kind,String after) {
        requireOperator(subject); if (after==null || after.length()>2048) throw new Failure(400,"INVALID_OPERATIONS_CURSOR");
        var page=runtime.metadata().list(instance(),kind,"",after,50); var rows=new ArrayList<JsonNode>();
        for (var doc:page) {
            var d=doc.data(); var row=json.createObjectNode().put("id",doc.id()).put("version",doc.version()).put("kind",doc.kind().name());
            for (String key:List.of("sourceModelId","targetKey","targetInstanceId","entityId","elementId","canonicalId","resourceId","state","code","paused","generation","attempts","nextAttemptAt","verifiedAt","verifiedRevision","remoteVerified","synced","pending","conflicts","sourceState","processed","failed","scheduled","phase","updatedAt")) if (d.has(key) && d.get(key).isValueNode()) row.set(key,d.get(key));
            if (d.has("inFlight")) row.put("uncertain",true);
            if (doc.kind()==DocumentKind.MODEL_SCOPE) try { row.put("currentGeneration",runtime.metadata().modelGeneration(d.path("sourceModelId").asText())); row.put("needsReview",d.path("paused").asBoolean() || !d.path("generation").asText().equals(row.path("currentGeneration").asText())); } catch (RuntimeException missing) { row.put("needsReview",true).put("code","MODEL_MISSING"); }
            rows.add(row);
        }
        return new DocumentPage(List.copyOf(rows),page.size()==50 ? page.getLast().id() : null);
    }
    public Review probe(AuthorizationSubject subject) {
        requireOperator(subject); var details=json.createObjectNode(); details.set("targets",manifest());
        for (var target:runtime.settings().targets()) runtime.validateTarget(target.key());
        details.put("controlVersion",control().map(StateDocument::version).orElse(0L)); return review(details);
    }
    public JsonNode pause(AuthorizationSubject subject,long version) {
        requireOperator(subject); models.setIntegrationPause(true,version,subject.userId()); audit("Paused",subject.userId(),null); return overview(subject);
    }
    public JsonNode resume(AuthorizationSubject subject,Decision decision) {
        requireOperator(subject); if (decision==null) throw new Failure(400,"REVIEW_REQUIRED"); var current=probe(subject);
        if (!current.digest().equals(decision.digest()) || control().map(StateDocument::version).orElse(0L)!=decision.expectedVersion()) throw new Failure(409,"OPERATIONS_REVIEW_STALE");
        models.setIntegrationPause(false,decision.expectedVersion(),subject.userId()); audit("Resumed",subject.userId(),null); return overview(subject);
    }
    public IntegrationArchive.Package exportArchive(AuthorizationSubject subject) {
        requireOperator(subject); delivery.requireWriterLease(); lock();
        try {
            requirePaused(); var nodes=runtime.metadata().archive(instance()); var targets=manifest(); String exportedAt=Instant.now().toString();
            String digest=archiveDigest(IntegrationArchive.FORMAT,instance(),targets,nodes); audit("Exported",subject.userId(),null);
            return new IntegrationArchive.Package(IntegrationArchive.FORMAT,instance(),exportedAt,targets,nodes,digest);
        } finally { execution.unlock(); }
    }
    private String archiveDigest(String format,String instance,JsonNode targets,List<IntegrationArchive.Node> nodes) { return PublicationJson.digest(json.valueToTree(List.of(format,instance,targets,nodes))); }
    private void validateArchive(IntegrationArchive.Package archive) {
        if (archive==null || !IntegrationArchive.supported(archive.format())) throw new Failure(400,"ARCHIVE_FORMAT_INVALID");
        if (!instance().equals(archive.instanceId()) || !PublicationJson.equal(manifest(),archive.targets())) throw new Failure(409,"ARCHIVE_CONFIGURATION_MISMATCH");
        IntegrationArchive.validate(archive.format(),instance(),archive.nodes(),json);
        if (!archiveDigest(archive.format(),archive.instanceId(),archive.targets(),archive.nodes()).equals(archive.digest())) throw new Failure(400,"ARCHIVE_DIGEST_MISMATCH");
    }
    public Review previewIdentity(AuthorizationSubject subject,IdentityClassification request) {
        requireOperator(subject); if (request==null) throw new Failure(400,"IDENTITY_CLASSIFICATION_REQUIRED"); lock();
        try {
            requirePaused();
            var details=(ObjectNode)runtime.metadata().previewIdentityMigration(instance(),request.canonicalId(),request.semanticKind());
            var verified=details.putArray("verifiedTargets");
            for (var resource:details.path("external")) {
                if (!"CARD".equals(resource.path("resourceKind").asText())) throw new Failure(409,"IDENTITY_CLASSIFICATION_OBJECTS_ONLY");
                var target=runtime.settings().targets().stream().filter(t -> t.instanceId().equals(resource.path("targetInstanceId").asText())).findFirst()
                        .orElseThrow(() -> new Failure(409,"IDENTITY_TARGET_NOT_CONFIGURED"));
                runtime.validateTarget(target.key()); var remote=runtime.connector(target.key()).getObject(resource.path("resourceId").asText());
                if (!remote.type().equals(details.path("legacyType").asText()) || !"ACTIVE".equals(remote.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT");
                verified.addObject().put("targetKey",target.key()).put("resourceId",remote.id()).put("type",remote.type())
                        .put("configurationDigest",IntegrationConfigurationDigest.target(target)).put("contentDigest",PublicationJson.digest(json.valueToTree(remote)));
            }
            return review(details);
        } finally { execution.unlock(); }
    }
    public JsonNode classifyIdentity(AuthorizationSubject subject,IdentityClassification request) {
        requireOperator(subject); delivery.requireWriterLease(); lock();
        try {
            var reviewed=previewIdentity(subject,request);
            if (!reviewed.digest().equals(request.digest())) throw new Failure(409,"IDENTITY_CLASSIFICATION_REVIEW_STALE");
            runtime.metadata().migrateIdentity(instance(),request.canonicalId(),request.semanticKind(),reviewed.details().path("metadataDigest").asText(),subject.userId());
            audit("IdentityClassified",subject.userId(),null); return overview(subject);
        } finally { execution.unlock(); }
    }
    public Review previewRestore(AuthorizationSubject subject,IntegrationArchive.Package archive) {
        requireOperator(subject); requirePaused(); validateArchive(archive);
        if (!runtime.metadata().archive(instance()).isEmpty()) throw new Failure(409,"ARCHIVE_DESTINATION_NOT_EMPTY");
        return review(json.createObjectNode().put("archiveDigest",archive.digest()).put("nodes",archive.nodes().size()).put("controlVersion",control().orElseThrow().version()).put("restorePaused",true).set("targets",manifest()));
    }
    public JsonNode restore(AuthorizationSubject subject,RestoreRequest request) {
        requireOperator(subject); delivery.requireWriterLease(); if (request==null) throw new Failure(400,"RESTORE_REQUIRED"); lock();
        try {
            var reviewed=previewRestore(subject,request.archive()); if (!reviewed.digest().equals(request.digest())) throw new Failure(409,"OPERATIONS_REVIEW_STALE");
            runtime.metadata().restoreArchive(instance(),request.archive().nodes()); audit("RestoredPaused",subject.userId(),null); return overview(subject);
        } finally { execution.unlock(); }
    }
    public JsonNode pauseModel(AuthorizationSubject subject,String model,long version) {
        requireOperator(subject); delivery.requireWriterLease(); lock();
        try {
            var scope=runtime.modelScope(model); var d=(ObjectNode)scope.data(); d.put("paused",true).put("updatedAt",System.currentTimeMillis());
            runtime.metadata().save(new StateDocument(instance(),scope.kind(),scope.id(),version,d)); audit("ModelPaused",subject.userId(),model); return d;
        } finally { execution.unlock(); }
    }
    public Review previewAttachment(AuthorizationSubject subject,String model) {
        requireOperator(subject); var scope=runtime.modelScope(model); var snapshot=models.getSnapshot(model,"HEAD"); String generation=runtime.metadata().modelGeneration(model);
        var archive=runtime.metadata().archive(instance()); var canonicalKinds=new HashMap<String,String>();
        for (var n:archive) if (n.label().equals("IntegrationCanonicalObject")) canonicalKinds.put((String)n.properties().get("canonicalId"),(String)n.properties().get("kind"));
        var identities=new LinkedHashMap<String,IntegrationArchive.Node>();
        for (var n:archive) if (model.equals(n.properties().get("sourceModelId")) && Set.of("IntegrationModelBinding","IntegrationPublicationIdentity").contains(n.label())) identities.putIfAbsent((String)n.properties().get("entityId"),n);
        if (identities.size()>500) throw new Failure(413,"ATTACHMENT_REVIEW_TOO_LARGE");
        var details=json.createObjectNode().put("model",model).put("scopeVersion",scope.version()).put("generation",generation).put("revision",snapshot.path("headRevision").asLong())
                .put("snapshotDigest",PublicationJson.digest(snapshot)).put("scopePaused",scope.data().path("paused").asBoolean());
        details.set("targets",manifest()); var rows=details.putArray("bindings"); var migrations=details.putArray("mappingMigrations"); boolean valid=true;
        var validated=new HashSet<String>();
        for (var entry:identities.entrySet()) {
            var p=entry.getValue().properties(); String element=entry.getKey(),canonical=(String)p.get("canonicalId"),type=(String)p.get("archimateType");
            var row=rows.addObject().put("entityId",element).put("canonicalId",canonical).put("archimateType",type).put("mappingVersion",(String)p.get("mappingVersion")); String code=null;
            JsonNode local=null; for (var e:snapshot.path(element.startsWith("elem:") ? "elements" : "relationships")) if (element.equals(e.path("id").asText())) local=e;
            if (local==null || !type.equals(local.path("archimateType").asText())) code="LOCAL_ENTITY_MISSING_OR_CHANGED";
            if (p.containsKey("state") && !"ACTIVE".equals(p.get("state"))) code="BINDING_REQUIRES_MANUAL_REPAIR";
            if (local!=null && p.containsKey("bindingMode") && !"PUBLISHED".equals(p.get("bindingMode"))) {
                var marker=InboundFields.local(json,snapshot,element,Set.of("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY));
                if (!canonical.equals(marker.path("property:"+ExternalObjectCatalogService.CANONICAL_PROPERTY).asText())) code="LOCAL_PROVENANCE_DRIFT";
            }
            if (code==null) for (var target:runtime.settings().targets()) {
                var external=runtime.metadata().findExternal(canonical,"turboea",target.instanceId(),element.startsWith("elem:") ? ResourceKind.CARD : ResourceKind.RELATION);
                if (external.isEmpty()) { if (target.instanceId().equals(p.get("targetInstanceId"))) code="EXTERNAL_BINDING_MISSING"; continue; }
                var remoteRow=row.putObject(target.key()).put("resourceId",external.get().resourceId());
                String externalType=archive.stream().filter(n -> n.label().equals("IntegrationExternalBinding")
                        && canonical.equals(n.properties().get("canonicalId")) && "turboea".equals(n.properties().get("system"))
                        && target.instanceId().equals(n.properties().get("targetInstanceId"))
                        && external.get().resourceId().equals(n.properties().get("resourceId")))
                        .map(n -> (String)n.properties().getOrDefault("externalType",canonicalKinds.get(canonical))).findFirst()
                        .orElseThrow(() -> new Failure(409,"EXTERNAL_BINDING_MISSING"));
                try {
                    if (!target.mapping().version().equals(p.get("mappingVersion"))) {
                        if (!control().map(d -> d.data().path("paused").asBoolean()).orElse(false)) throw new Failure(409,"MIGRATION_REQUIRES_PAUSE");
                        if (archive.stream().filter(n -> n.label().equals("IntegrationStateDocument")).anyMatch(n -> uncertainCanonical(n,canonical))) throw new Failure(409,"MIGRATION_UNCERTAIN_OUTCOME");
                        migrations.addObject().put("entityId",element).put("canonicalId",canonical).put("from",(String)p.get("mappingVersion")).put("to",target.mapping().version()).put("targetInstanceId",target.instanceId());
                    }
                    if (validated.add(target.key())) runtime.validateTarget(target.key());
                    if (element.startsWith("elem:")) {
                        var card=runtime.connector(target.key()).getObject(external.get().resourceId());
                        if (!card.type().equals(externalType) || !target.mapping().archimateType(card.type()).equals(type) || !"ACTIVE".equals(card.status())) throw new Failure(409,"EXTERNAL_TYPE_OR_LIFECYCLE_CONFLICT");
                        var fields=target.mapping().fields().getOrDefault(card.type(),Map.of()); remoteRow.put("type",card.type());
                        remoteRow.set("local",InboundFields.local(json,snapshot,element,fields.keySet())); remoteRow.set("remote",InboundFields.remote(json,card,fields));
                    } else verifyRelation(model,target,local,external.get(),type,externalType,remoteRow);
                } catch (ConnectorException failure) { code="EXTERNAL_"+failure.kind(); }
                catch (Failure failure) { code=failure.getMessage(); }
                catch (IllegalArgumentException invalid) { code="MAPPING_MIGRATION_REQUIRED"; }
            }
            row.put("state",code==null ? "VERIFIED" : "BLOCKED"); if (code!=null) { row.put("code",code); valid=false; }
        }
        // Identity claims and uncertain dispatch records are included in the digest, never discarded on attachment.
        details.put("metadataDigest",PublicationJson.digest(json.valueToTree(archive.stream().filter(n -> relatedMetadata(n,model,identities.values())).toList())));
        details.put("canAttach",valid); return review(details);
    }
    private boolean uncertainCanonical(IntegrationArchive.Node node,String canonical) {
        try {
            var data=json.readTree((String)node.properties().get("dataJson"));
            if ("CENTRAL_LANE".equals(node.properties().get("kind")) && data.has("inFlight"))
                return runtime.settings().targets().stream().anyMatch(t -> CentralEditModel.laneId(t.instanceId(),canonical).equals(node.properties().get("id")));
            return canonical.equals(data.path("canonicalId").asText()) && (data.has("inFlight") || Set.of("AMBIGUOUS","IN_FLIGHT").contains(data.path("state").asText()));
        }
        catch (java.io.IOException corrupt) { throw new Failure(409,"CORRUPT_INTEGRATION_METADATA"); }
    }
    private boolean relatedMetadata(IntegrationArchive.Node node,String model,Collection<IntegrationArchive.Node> identities) {
        if (model.equals(node.properties().get("sourceModelId")) || node.label().equals("IntegrationExternalBinding")) return true;
        if (!node.label().equals("IntegrationStateDocument")) return false;
        try {
            var data=json.readTree((String)node.properties().get("dataJson"));
            return model.equals(data.path("sourceModelId").asText()) || model.equals(data.path("ownerModel").asText()) || identities.stream().anyMatch(n -> data.path("canonicalId").asText().equals(n.properties().get("canonicalId")));
        } catch (java.io.IOException corrupt) { throw new Failure(409,"CORRUPT_INTEGRATION_METADATA"); }
    }
    private void verifyRelation(String model,IntegrationConfiguration.Target target,JsonNode local,ExternalResourceKey external,String type,String remoteType,ObjectNode row) {
        var mappings=target.mapping().relationTypes();
        String source=local.path("sourceId").asText(),destination=local.path("targetId").asText(); boolean found=false;
        for (var mapping:mappings.entrySet()) if (mapping.getKey().equals(remoteType) && mapping.getValue().archimateType().equals(type)) {
            String from=mapping.getValue().reversed() ? destination : source,to=mapping.getValue().reversed() ? source : destination;
            var a=runtime.metadata().findPublicationIdentity(new ModelElementKey(instance(),model,EntityKind.ELEMENT,from)).orElseThrow(() -> new Failure(409,"RELATION_ENDPOINT_MISSING"));
            var b=runtime.metadata().findPublicationIdentity(new ModelElementKey(instance(),model,EntityKind.ELEMENT,to)).orElseThrow(() -> new Failure(409,"RELATION_ENDPOINT_MISSING"));
            var ar=runtime.metadata().findExternal(a.canonical().canonicalId(),"turboea",target.instanceId(),ResourceKind.CARD).orElseThrow(() -> new Failure(409,"RELATION_ENDPOINT_MISSING"));
            var br=runtime.metadata().findExternal(b.canonical().canonicalId(),"turboea",target.instanceId(),ResourceKind.CARD).orElseThrow(() -> new Failure(409,"RELATION_ENDPOINT_MISSING"));
            for (var relation:runtime.connector(target.key()).relations(ar.resourceId())) if (relation.id().equals(external.resourceId()) && relation.type().equals(mapping.getKey()) && relation.sourceId().equals(ar.resourceId()) && relation.targetId().equals(br.resourceId())) { found=true; row.put("type",relation.type()).put("sourceId",relation.sourceId()).put("targetId",relation.targetId()); }
        }
        if (!found) throw new Failure(409,"RELATION_IDENTITY_OR_DIRECTION_CHANGED");
    }
    public JsonNode attach(AuthorizationSubject subject,String model,Decision decision) {
        requireOperator(subject); delivery.requireWriterLease(); if (decision==null) throw new Failure(400,"REVIEW_REQUIRED"); lock();
        try {
            var current=previewAttachment(subject,model); if (!current.digest().equals(decision.digest()) || !current.details().path("canAttach").asBoolean() || current.details().path("scopeVersion").asLong()!=decision.expectedVersion()) throw new Failure(409,"ATTACHMENT_REVIEW_STALE_OR_BLOCKED");
            models.confirmIntegrationAttachment(model,current.details().path("generation").asText(),current.details().path("snapshotDigest").asText(),decision.expectedVersion(),current.details().path("mappingMigrations"));
            audit("ModelAttached",subject.userId(),model); return json.createObjectNode().put("state","ATTACHED_REAPPROVAL_REQUIRED");
        } finally { execution.unlock(); }
    }
    public StateDocument scan(AuthorizationSubject subject,ScanRequest request) {
        requireOperator(subject); delivery.requireWriterLease(); runtime.requireRunning();
        if (request==null || request.requestId()==null || !UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId())) throw new Failure(400,"SCAN_REQUEST_ID_REQUIRED");
        if (runtime.settings().targets().stream().noneMatch(t -> t.key().equals(request.targetKey()))) throw new Failure(404,"TARGET_NOT_FOUND");
        lock(); try {
            runtime.requireRunning(); String id="scan:"+request.requestId(); var existing=runtime.metadata().load(instance(),DocumentKind.RECONCILIATION,id);
            if (existing.isPresent()) { if (!existing.get().data().path("targetKey").asText().equals(request.targetKey()) || !existing.get().data().path("requestedBy").asText().equals(subject.userId())) throw new Failure(409,"SCAN_REQUEST_ID_REUSED"); return existing.get(); }
            var data=json.createObjectNode().put("targetKey",request.targetKey()).put("requestedBy",subject.userId()).put("state","PENDING").put("phase","INBOUND").put("cursor","").put("processed",0).put("failed",0).put("createdAt",System.currentTimeMillis());
            data.set("targets",manifest()); audit("ScanRequested",subject.userId(),id); return runtime.metadata().save(new StateDocument(instance(),DocumentKind.RECONCILIATION,id,0,data));
        } finally { execution.unlock(); }
    }
    public void runScan(String id) {
        if (!execution.tryLock()) return;
        try {
            runtime.requireRunning(); delivery.requireWriterLease(); var job=runtime.metadata().load(instance(),DocumentKind.RECONCILIATION,id).orElseThrow(); var data=(ObjectNode)job.data();
            if (!Set.of("PENDING","RUNNING").contains(data.path("state").asText())) return;
            if (!runtime.settings().operators().contains(data.path("requestedBy").asText())) {
                data.put("state","PAUSED").put("code","OPERATOR_REVOKED"); runtime.metadata().save(new StateDocument(instance(),job.kind(),id,job.version(),data)); return;
            }
            if (!PublicationJson.equal(manifest(),data.path("targets"))) { data.put("state","CONFLICT").put("code","CONFIGURATION_CHANGED"); }
            else {
                var target=runtime.settings().targets().stream().filter(t -> t.key().equals(data.path("targetKey").asText())).findFirst().orElseThrow();
                String cursor=data.path("cursor").asText();
                if (data.path("phase").asText().equals("INBOUND")) {
                    var page=runtime.metadata().bindings(instance(),target.instanceId(),null,cursor,10);
                    for (var binding:page) {
                        try {
                            inbound.reconcile(target.key(),binding,true);
                            var result=runtime.metadata().load(instance(),DocumentKind.INBOUND_STATE,ExternalChangeIngestService.stateId(binding));
                            if (result.isEmpty() || !"SYNCED".equals(result.get().data().path("state").asText())) data.put("failed",data.path("failed").asInt()+1);
                        } catch (RuntimeException failed) { data.put("failed",data.path("failed").asInt()+1); }
                        data.put("processed",data.path("processed").asInt()+1).put("cursor",ExternalChangeIngestService.cursor(binding));
                    }
                    if (page.size()<10) data.put("phase","PUBLICATION").put("cursor",""); data.put("state","RUNNING");
                } else {
                    var page=runtime.metadata().list(instance(),DocumentKind.PUBLICATION_POLICY,"pub-policy:",cursor,10);
                    for (var policy:page) { if (target.key().equals(policy.data().path("targetKey").asText())) { try {
                        if (!"DISABLED".equals(policy.data().path("policy").path("mode").asText())) {
                            String request=UUID.nameUUIDFromBytes((job.id()+":"+policy.id()+":"+policy.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                            publication.enqueue(policy.data().path("sourceModelId").asText(),target.key(),new AuthorizationSubject(policy.data().path("approvedBy").asText(),Set.of()),new PublicationModel.RunRequest(request,null));
                            data.put("scheduled",data.path("scheduled").asInt()+1);
                        }
                    } catch (RuntimeException failed) { data.put("failed",data.path("failed").asInt()+1); } } data.put("cursor",policy.id()); }
                    data.put("state",page.size()<10 ? "COMPLETE" : "RUNNING");
                }
            }
            data.put("updatedAt",System.currentTimeMillis()); runtime.metadata().save(new StateDocument(instance(),job.kind(),id,job.version(),data));
        } catch (RuntimeException unavailable) { /* No cursor advance; bounded worker retries after availability returns. */ }
        finally { execution.unlock(); }
    }
    private Review review(JsonNode details) { return new Review(details,PublicationJson.digest(details)); }
    private Optional<StateDocument> control() { return runtime.metadata().load(instance(),DocumentKind.OPERATIONS,"control"); }
    private void requirePaused() { if (!control().map(d -> d.data().path("paused").asBoolean()).orElse(false)) throw new Failure(409,"ARCHIVE_REQUIRES_PAUSE"); }
    private void lock() { if (!execution.tryLock()) throw new Failure(409,"INTEGRATION_BUSY"); }
    private String instance() { return runtime.settings().instanceId(); }
    private void audit(String action,String actor,String scope) { org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=Operations{} actor={} scope={}",action,safe(actor),safe(scope)); }
    private String safe(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
}
