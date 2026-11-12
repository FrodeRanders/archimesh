package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.model.ExternalModelImport;
import org.gautelis.archimesh.service.ArchimeshService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.InformationAuthorityPolicy.Authority;

/** Single-card import coordinator. Remote I/O and metadata transactions never hold the model monitor. */
@ApplicationScoped
public class ExternalObjectCatalogService {
    public static final String CANONICAL_PROPERTY="archimesh.external.canonicalId";
    @Inject IntegrationRuntime runtime;
    @Inject RemoteMutationCoordinator execution=new RemoteMutationCoordinator();
    @Inject ArchimeshService models;
    @Inject AuthorizationService authorization;
    @Inject ObjectMapper json;

    public record TargetOption(String key, List<String> types, boolean canImport) { }
    public record CardSummary(String id,String type,String name,String description,String elementId) { }
    public record Catalog(List<CardSummary> items,long total,int page,int pageSize) { }
    public record FieldValue(String field,String value,String authority) { }
    public record CompatibleElement(String id,String name) { }
    public record Preview(String targetKey,String cardId,String name,String description,String archimateType,
                          String mappingVersion,String externalType,long headRevision,List<FieldValue> fields,List<CompatibleElement> compatibleElements,
                          String elementId,String bindingState,String digest) { }
    public enum ImportMode { CREATE, LINK }
    public record ImportRequest(String requestId,String targetKey,String cardId,String ref,ImportMode mode,
                                String elementId,String previewDigest) { }
    public record ImportResult(String requestId,String elementId,String canonicalId,String targetKey,String cardId,
                               String opBatchId,long acceptedRevision,boolean reused) { }
    public record BindingStatus(String elementId,String canonicalId,String targetKey,String cardId,String state,String mappingVersion) { }
    public static final class RequestFailure extends RuntimeException {
        private final int status;
        public RequestFailure(int status,String code) { super(code); this.status=status; }
        public int status() { return status; }
    }

    public List<TargetOption> targets(String modelId,AuthorizationSubject subject) {
        requireModel(modelId,"HEAD",subject,false);
        if (!runtime.enabled()) return List.of();
        return runtime.settings().targets().stream().filter(t -> readable(t,subject)).map(t -> new TargetOption(t.key(),
                t.catalogAccess().cardTypes().stream().sorted().toList(),t.catalogAccess().importers().contains(subject.userId()))).toList();
    }

    public Catalog catalog(String modelId,AuthorizationSubject subject,String targetKey,String type,String query,int page,int pageSize) {
        var target=target(modelId,"HEAD",subject,targetKey,false);
        requireType(target,type); runtime.validateTarget(targetKey);
        var result=runtime.connector(targetKey).search(query,type,page,pageSize);
        var items=new ArrayList<CardSummary>();
        for (var card:result.items()) {
            if (!type.equals(card.type())) throw new RequestFailure(502,"CATALOG_SCOPE_MISMATCH");
            var binding=runtime.metadata().findModelBinding(runtime.settings().instanceId(),modelId,external(target,card.id()));
            items.add(new CardSummary(card.id(),card.type(),safeText(card.name(),512),
                    target.catalogAccess().fields().contains("description") ? safeText(card.description(),16384) : null,
                    binding.map(b -> b.model().entityId()).orElse(null)));
        }
        // Do not expose the connector's withheld_ids or other service-account metadata.
        return new Catalog(List.copyOf(items),result.total(),result.page(),result.pageSize());
    }

    public Preview preview(String modelId,String ref,AuthorizationSubject subject,String targetKey,String cardId) {
        var target=target(modelId,ref,subject,targetKey,false);
        runtime.validateTarget(targetKey);
        var card=runtime.connector(targetKey).getObject(required(cardId));
        requireType(target,card.type());
        if (!"ACTIVE".equals(card.status())) throw new RequestFailure(409,"EXTERNAL_OBJECT_INACTIVE");
        String archimateType=target.mapping().archimateType(card.type());
        var snapshot=models.getSnapshot(modelId,ref);
        var canonical=runtime.metadata().findCanonical(external(target,cardId)).map(CanonicalObject::canonicalId).orElse(null);
        var fields=importFields(target,card,canonical);
        var binding=runtime.metadata().findModelBinding(runtime.settings().instanceId(),modelId,external(target,cardId));
        var compatible=new ArrayList<CompatibleElement>();
        for (var element:snapshot.path("elements")) {
            if (!archimateType.equals(element.path("archimateType").asText()) || compatible.size() >= 200) continue;
            String id=element.path("id").asText();
            var existing=runtime.metadata().findBinding(new ModelElementKey(runtime.settings().instanceId(),modelId,EntityKind.ELEMENT,id));
            if (existing.isEmpty() || binding.isPresent() && existing.get().canonical().equals(binding.get().canonical()))
                compatible.add(new CompatibleElement(id,safeText(element.path("name").asText(""),512)));
        }
        String name=safeText(card.name(),512);
        String description=target.catalogAccess().fields().contains("description") ? safeText(card.description(),16384) : null;
        long head=snapshot.path("headRevision").asLong();
        String bindingState=binding.map(b -> b.state().name()).orElse(null);
        if (binding.isPresent() && binding.get().state()==BindingState.ACTIVE) {
            try { verify(binding.get(),snapshot,null); } catch (RequestFailure drift) { bindingState="DRIFT"; }
        }
        String digest=digest(json.valueToTree(Arrays.asList(modelId,ref,targetKey,cardId,archimateType,
                target.mapping().version(),head,fields,name,description,runtime.requireModelActive(modelId))));
        return new Preview(targetKey,cardId,name,description,archimateType,target.mapping().version(),card.type(),head,fields,List.copyOf(compatible),
                binding.map(b -> b.model().entityId()).orElse(null),bindingState,digest);
    }

    public ImportResult importObject(String modelId,AuthorizationSubject subject,ImportRequest request) {
        execution.lock();
        try {
            runtime.requireRunning();
            if (request == null || request.mode() == null || request.requestId() == null) throw new RequestFailure(400,"INVALID_IMPORT");
            if (!UUID.fromString(request.requestId()).toString().equalsIgnoreCase(request.requestId())) throw new RequestFailure(400,"INVALID_REQUEST_ID");
            required(request.cardId());
            if (request.previewDigest() == null || !request.previewDigest().matches("[a-f0-9]{64}")) throw new RequestFailure(400,"PREVIEW_REQUIRED");
            if (request.mode() == ImportMode.LINK && (request.elementId() == null || !request.elementId().startsWith("elem:")))
                throw new RequestFailure(400,"LINK_ELEMENT_REQUIRED");
            var target=target(modelId,request.ref(),subject,request.targetKey(),true);
            var state=runtime.metadata(); String instance=runtime.settings().instanceId();
            String jobId="import-request:"+digest(json.valueToTree(List.of(modelId,request.requestId())));
            String fingerprint=digest(json.valueToTree(List.of(subject.userId(),request)));
            var journal=state.load(instance,DocumentKind.JOB,jobId);
            if (journal.isPresent()) {
                runtime.requireGeneration(modelId,journal.get().data().path("modelGeneration").asText(null));
                if (!fingerprint.equals(journal.get().data().path("fingerprint").asText())) throw new RequestFailure(409,"REQUEST_ID_REUSED");
                requireType(target,journal.get().data().path("externalType").asText());
                if (journal.get().data().has("result")) {
                    var stored=json.convertValue(journal.get().data().path("result"),ImportResult.class);
                    var binding=state.findBinding(new ModelElementKey(instance,modelId,EntityKind.ELEMENT,stored.elementId())).orElseThrow();
                    if (!binding.external().equals(external(target,request.cardId()))) throw new RequestFailure(409,"TARGET_IDENTITY_CHANGED");
                    verify(binding,models.getSnapshot(modelId,"HEAD"),null);
                    return stored;
                }
                var pending=state.findModelBinding(instance,modelId,external(target,request.cardId()));
                if (pending.isPresent()) {
                    var plan=state.load(instance,DocumentKind.JOB,"import-plan:"+pending.get().opBatchId());
                    if (plan.isPresent()) return finishImport(modelId,subject,request,pending.get(),plan.get(),
                            plan.get().data().path("headRevision").asLong(-1),true,journal.get());
                }
            }
            var reusable=reuseExisting(modelId,subject,request,target,jobId,fingerprint);
            if (reusable.isPresent()) return reusable.get();
            Preview preview=preview(modelId,request.ref(),subject,request.targetKey(),request.cardId());
            if (!preview.digest().equals(request.previewDigest())) {
                reusable=reuseExisting(modelId,subject,request,target,jobId,fingerprint);
                if (reusable.isPresent()) return reusable.get();
                throw new RequestFailure(409,"PREVIEW_STALE");
            }
            var requestData=requestData(modelId,subject,request,fingerprint,preview.externalType());
            journal=Optional.of(ensureDocument(jobId,requestData,"fingerprint"));
            var known=state.findModelBinding(instance,modelId,external(target,request.cardId()));
            if (request.mode() == ImportMode.LINK && (request.elementId() == null || !request.elementId().startsWith("elem:")))
                throw new RequestFailure(400,"LINK_ELEMENT_REQUIRED");
            if (known.isPresent() && request.mode() == ImportMode.LINK && !known.get().model().entityId().equals(request.elementId()))
                throw new RequestFailure(409,"ALREADY_LINKED_TO_ANOTHER_ELEMENT");
            String entityId=known.map(b -> b.model().entityId()).orElse(request.mode() == ImportMode.LINK ? request.elementId() : "elem:"+UUID.randomUUID());
            if (request.mode() == ImportMode.LINK && preview.compatibleElements().stream().noneMatch(e -> e.id().equals(entityId)))
                throw new RequestFailure(409,"LINK_TYPE_OR_IDENTITY_CONFLICT");
            String candidateBatch=UUID.randomUUID().toString();
            var reservation=state.reserveImport(new ImportReservation(new ModelElementKey(instance,modelId,EntityKind.ELEMENT,entityId),
                    state.findCanonical(external(target,request.cardId())).orElseGet(() -> new CanonicalObject("urn:uuid:"+UUID.randomUUID(),preview.externalType())),external(target,request.cardId()),
                    preview.archimateType(),preview.mappingVersion(),candidateBatch,request.mode().name(),preview.externalType()));
            if (request.mode() == ImportMode.LINK && !reservation.model().entityId().equals(request.elementId()))
                throw new RequestFailure(409,"ALREADY_LINKED_TO_ANOTHER_ELEMENT");
            var operations=operations(reservation,ImportMode.valueOf(reservation.bindingMode()),preview.fields());
            var plan=json.createObjectNode().put("fingerprint",digest(operations)).put("mode",reservation.bindingMode())
                    .put("headRevision",preview.headRevision()).put("mappingVersion",preview.mappingVersion()).put("previewDigest",preview.digest()).put("schemaVersion",1);
            plan.set("operations",operations);
            var stored=reservation.state() == BindingState.ACTIVE ? new StateDocument(instance,DocumentKind.JOB,"unused",0,plan)
                    : ensureDocument("import-plan:"+reservation.opBatchId(),plan,"fingerprint");
            return finishImport(modelId,subject,request,reservation,stored,preview.headRevision(),
                    known.isPresent() || !candidateBatch.equals(reservation.opBatchId()),journal.get());

        } finally { execution.unlock(); }
    }

    private Optional<ImportResult> reuseExisting(String modelId,AuthorizationSubject subject,ImportRequest request,
                                                  IntegrationConfiguration.Target target,String jobId,String fingerprint) {
        var state=runtime.metadata(); String instance=runtime.settings().instanceId();
        var existing=state.findModelBinding(instance,modelId,external(target,request.cardId()));
        if (existing.isEmpty()) return Optional.empty();
        var binding=existing.get(); requireType(target,binding.remoteType());
        var plan=state.load(instance,DocumentKind.JOB,"import-plan:"+binding.opBatchId());
        if (binding.state()!=BindingState.ACTIVE && (plan.isEmpty()
                || !request.previewDigest().equals(plan.get().data().path("previewDigest").asText()))) return Optional.empty();
        var journal=ensureDocument(jobId,requestData(modelId,subject,request,fingerprint,binding.remoteType()),"fingerprint");
        return Optional.of(finishImport(modelId,subject,request,binding,plan.orElse(new StateDocument(instance,DocumentKind.JOB,"unused",0,json.createObjectNode())),
                plan.map(p -> p.data().path("headRevision").asLong(-1)).orElse(-1L),true,journal));
    }

    private ImportResult finishImport(String modelId,AuthorizationSubject subject,ImportRequest request,ReservedImport reservation,
                                      StateDocument plan,long expectedRevision,boolean reused,StateDocument journal) {
        var state=runtime.metadata(); String instance=runtime.settings().instanceId();
        runtime.requireGeneration(modelId,journal.data().path("modelGeneration").asText(null));
        if (reservation.state()==BindingState.ORPHANED) throw new RequestFailure(409,"ORPHANED_BINDING_REQUIRES_REATTACHMENT");
        var target=target(modelId,"HEAD",subject,request.targetKey(),true);
        if (!reservation.external().equals(external(target,request.cardId()))
                || !target.mapping().version().equals(reservation.mappingVersion())) throw new RequestFailure(409,"MAPPING_OR_ORIGIN_CHANGED");
        if (request.mode()==ImportMode.LINK && !reservation.model().entityId().equals(request.elementId()))
            throw new RequestFailure(409,"ALREADY_LINKED_TO_ANOTHER_ELEMENT");
        var current=models.getSnapshot(modelId,"HEAD");
        if (reservation.state() != BindingState.ACTIVE) {
            String marker=property(current,reservation.model().entityId(),CANONICAL_PROPERTY);
            if (marker != null && !marker.equals(reservation.canonical().canonicalId())) throw new RequestFailure(409,"EXISTING_PROVENANCE_CONFLICT");
            var outcome=models.submitExternalImport(new ExternalModelImport(modelId,"HEAD",subject,reservation.opBatchId(),
                    reservation.model().entityId(),reservation.archimateType(),reservation.bindingMode().equals("CREATE"),
                    expectedRevision,plan.data().path("operations"),runtime.requireModelActive(modelId)));
            if (!outcome.accepted()) throw new RequestFailure(409,outcome.code());
            verify(reservation,models.getSnapshot(modelId,"HEAD"),plan.data().path("operations"));
            reservation=state.confirmImport(reservation.model(),reservation.opBatchId());
        } else verify(reservation,current,null);
        var result=new ImportResult(request.requestId(),reservation.model().entityId(),reservation.canonical().canonicalId(),
                request.targetKey(),request.cardId(),reservation.opBatchId(),reservation.acceptedRevision(),reused);
        ObjectNode completed=(ObjectNode)journal.data(); completed.set("result",json.valueToTree(result));
        try { state.save(new StateDocument(instance,DocumentKind.JOB,journal.id(),journal.version(),completed)); }
        catch (IllegalStateException race) {
            var winner=state.load(instance,DocumentKind.JOB,journal.id()).orElseThrow();
            if (!winner.data().has("result")) throw race;
            return json.convertValue(winner.data().path("result"),ImportResult.class);
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).info("External import accepted: user={} model={} canonical={} batch={}",
                subject.userId(),modelId,result.canonicalId(),result.opBatchId());
        return result;
    }

    public BindingStatus status(String modelId,AuthorizationSubject subject,String elementId) {
        requireModel(modelId,"HEAD",subject,false);
        if (!runtime.enabled()) throw new RequestFailure(404,"INTEGRATION_DISABLED");
        var binding=runtime.metadata().findBinding(new ModelElementKey(runtime.settings().instanceId(),modelId,EntityKind.ELEMENT,elementId))
                .orElseThrow(() -> new RequestFailure(404,"BINDING_NOT_FOUND"));
        var target=runtime.settings().targets().stream().filter(t -> t.instanceId().equals(binding.external().targetInstanceId()))
                .findFirst().orElseThrow(() -> new RequestFailure(404,"TARGET_NOT_FOUND"));
        target(modelId,"HEAD",subject,target.key(),false);
        requireType(target,binding.remoteType());
        String state=binding.state().name();
        if (binding.state() == BindingState.ACTIVE) {
            try { verify(binding,models.getSnapshot(modelId,"HEAD"),null); }
            catch (RequestFailure drift) { state="DRIFT"; }
        }
        return new BindingStatus(elementId,binding.canonical().canonicalId(),target.key(),binding.external().resourceId(),state,binding.mappingVersion());
    }

    private IntegrationConfiguration.Target target(String modelId,String ref,AuthorizationSubject subject,String key,boolean write) {
        requireModel(modelId,ref,subject,write);
        if (!runtime.enabled()) throw new RequestFailure(404,"INTEGRATION_DISABLED");
        runtime.requireModelActive(modelId);
        var target=runtime.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> new RequestFailure(404,"TARGET_NOT_FOUND"));
        if (!readable(target,subject) || write && !target.catalogAccess().importers().contains(subject.userId()))
            throw new RequestFailure(403,"CATALOG_ACCESS_DENIED");
        return target;
    }
    private void requireModel(String modelId,String ref,AuthorizationSubject subject,boolean write) {
        required(modelId);
        if (!"HEAD".equals(ref)) throw new RequestFailure(409,"MODEL_REFERENCE_READ_ONLY");
        authorization.requireSubjectAllowed(subject,write ? AuthorizationAction.EXTERNAL_OBJECT_IMPORT : AuthorizationAction.EXTERNAL_CATALOG_READ,modelId,ref);
        if (models.findModelAccessControl(modelId).isEmpty()) throw new RequestFailure(404,"MODEL_NOT_FOUND");
    }
    private boolean readable(IntegrationConfiguration.Target target,AuthorizationSubject subject) {
        return target.catalogAccess().approved() && target.catalogAccess().readers().contains(subject.userId());
    }
    private void requireType(IntegrationConfiguration.Target target,String type) {
        if (!target.catalogAccess().cardTypes().contains(type)) throw new RequestFailure(404,"OBJECT_TYPE_NOT_AVAILABLE");
    }
    private ExternalResourceKey external(IntegrationConfiguration.Target target,String cardId) {
        return new ExternalResourceKey("turboea",target.instanceId(),ResourceKind.CARD,cardId);
    }
    private List<FieldValue> importFields(IntegrationConfiguration.Target target,ExternalRepositoryConnector.ExternalObject card,String canonical) {
        var result=new ArrayList<FieldValue>();
        for (var entry:new TreeMap<>(target.mapping().fieldsFor(card.type(),canonical)).entrySet()) {
            var field=entry.getValue();
            if (!target.catalogAccess().fields().contains(field.remoteField()) || field.authority() == Authority.LOCAL_MODEL_ONLY
                    || field.authority() == Authority.UNMAPPED || field.authority() == Authority.SHARED_PROPOSAL) continue;
            JsonNode value=switch(field.remoteField()) {
                case "name" -> json.valueToTree(card.name());
                case "description" -> json.valueToTree(card.description());
                case "status" -> json.valueToTree(card.status());
                case "lifecycle" -> card.lifecycle();
                default -> card.attributes() == null ? null : card.attributes().get(field.remoteField().substring("attributes.".length()));
            };
            if (value == null || value.isNull()) continue;
            boolean valid=switch(field.valueType()) {
                case STRING -> value.isTextual(); case NUMBER -> value.isNumber(); case BOOLEAN -> value.isBoolean();
                case OBJECT -> value.isObject(); case ARRAY -> value.isArray();
            };
            if (!valid) throw new RequestFailure(409,"FIELD_VALUE_TYPE_CONFLICT");
            if (entry.getKey().equals("property:"+CANONICAL_PROPERTY)) throw new RequestFailure(409,"RESERVED_PROVENANCE_PROPERTY");
            result.add(new FieldValue(entry.getKey(),safeText(value.isTextual() ? value.asText() : value.toString(),
                    entry.getKey().equals("name") ? 512 : 16384),field.authority().name()));
        }
        if (result.size()>32 || result.stream().noneMatch(f -> f.field().equals("name"))) throw new RequestFailure(409,"IMPORT_NAME_MAPPING_REQUIRED");
        return List.copyOf(result);
    }
    private ArrayNode operations(ReservedImport binding,ImportMode mode,List<FieldValue> fields) {
        var ops=json.createArrayNode(); String id=binding.model().entityId();
        if (mode == ImportMode.CREATE) {
            var element=ops.addObject().put("type","CreateElement").putObject("element").put("id",id).put("archimateType",binding.archimateType());
            for (var field:fields) if (!field.field().startsWith("property:")) element.put(field.field(),field.value());
            for (var field:fields) if (field.field().startsWith("property:"))
                ops.addObject().put("type","SetProperty").put("targetId",id).put("key",field.field().substring(9)).put("value",field.value());
            String folder=switch(binding.archimateType()) {
                case "ApplicationComponent" -> "folder:root-application";
                case "BusinessActor","BusinessProcess" -> "folder:root-business";
                case "Capability" -> "folder:root-strategy";
                default -> throw new RequestFailure(409,"UNSUPPORTED_MODEL_FOLDER");
            };
            ops.addObject().put("type","MoveElementToFolder").put("elementId",id).put("folderId",folder);
        }
        ops.addObject().put("type","SetProperty").put("targetId",id).put("key",CANONICAL_PROPERTY).put("value",binding.canonical().canonicalId());
        return ops;
    }
    private StateDocument ensureDocument(String id,ObjectNode data,String fingerprintKey) {
        var state=runtime.metadata(); String instance=runtime.settings().instanceId();
        var existing=state.load(instance,DocumentKind.JOB,id);
        if (existing.isEmpty()) {
            try { return state.save(new StateDocument(instance,DocumentKind.JOB,id,0,data)); }
            catch (IllegalStateException race) { existing=state.load(instance,DocumentKind.JOB,id); if (existing.isEmpty()) throw race; }
        }
        if (!existing.get().data().path(fingerprintKey).equals(data.path(fingerprintKey))) throw new RequestFailure(409,"IMPORT_INTENT_CONFLICT");
        return existing.get();
    }
    private ObjectNode requestData(String modelId,AuthorizationSubject subject,ImportRequest request,String fingerprint,String externalType) {
        var data=json.createObjectNode().put("schemaVersion",1).put("fingerprint",fingerprint).put("sourceModelId",modelId)
                .put("externalType",externalType).put("userId",subject.userId()).put("modelGeneration",runtime.requireModelActive(modelId));
        data.set("request",json.valueToTree(request));
        return data;
    }
    private void verify(ReservedImport binding,JsonNode snapshot,JsonNode operations) {
        JsonNode element=null;
        for (var row:snapshot.path("elements")) if (binding.model().entityId().equals(row.path("id").asText())) element=row;
        if (element == null || !binding.archimateType().equals(element.path("archimateType").asText())
                || !binding.bindingMode().equals("PUBLISHED") && !binding.canonical().canonicalId().equals(property(snapshot,binding.model().entityId(),CANONICAL_PROPERTY)))
            throw new RequestFailure(409,"BINDING_DRIFT_OR_DELETED");
        if (operations != null) for (var op:operations) {
            if (op.path("type").asText().equals("CreateElement")) for (String field:List.of("name","documentation")) {
                if (op.path("element").has(field) && !op.path("element").path(field).equals(element.path(field)))
                    throw new RequestFailure(409,"ACCEPTED_FIELD_DIVERGED");
            }
            if (op.path("type").asText().equals("SetProperty") && !op.path("value").asText().equals(property(snapshot,binding.model().entityId(),op.path("key").asText())))
                throw new RequestFailure(409,"ACCEPTED_FIELD_DIVERGED");
            if (op.path("type").asText().equals("MoveElementToFolder")) {
                boolean found=false;
                for (var member:snapshot.path("elementFolderMembers"))
                    if (op.path("elementId").equals(member.path("elementId")) && op.path("folderId").equals(member.path("folderId"))) found=true;
                if (!found) throw new RequestFailure(409,"ACCEPTED_FOLDER_DIVERGED");
            }
        }
    }
    private String property(JsonNode snapshot,String id,String key) {
        for (var entry:snapshot.path("propertyState")) if (id.equals(entry.path("targetId").asText()) && key.equals(entry.path("key").asText())
                && entry.path("type").asText().equals("SetProperty")) return entry.path("value").asText();
        return null;
    }
    private String safeText(String value,int limit) {
        if (value == null) return null;
        if (value.length()>limit || value.chars().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\t' && c!='\r'))
            throw new RequestFailure(409,"EXTERNAL_TEXT_INVALID_OR_TOO_LARGE");
        return value;
    }
    private String digest(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(node.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
