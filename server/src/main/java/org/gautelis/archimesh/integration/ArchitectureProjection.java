package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.PublicationIdentity;
import static org.gautelis.archimesh.integration.PublicationModel.*;

/** Pure snapshot projection. Accepted patches, Kafka payloads and diagram geometry are never projected. */
public final class ArchitectureProjection {
    private final ObjectMapper json;
    public ArchitectureProjection(ObjectMapper json) { this.json=json; }

    public Plan build(String instance,String modelId,IntegrationConfiguration.Target target,PolicySpec policy,JsonNode snapshot,
                      IntegrationStateRepository metadata) {
        if (policy.mode()==Mode.DISABLED) return new Plan(modelId,target.key(),target.instanceId(),policy.ref(),0,target.mapping().version(),List.of());
        long revision=snapshot.path("headRevision").asLong(-1);
        if (revision<1) throw new Failure(409,"ACCEPTED_SNAPSHOT_REQUIRED");
        var elements=new HashMap<String,JsonNode>(); snapshot.path("elements").forEach(e -> elements.put(e.path("id").asText(),e));
        var relationships=new HashMap<String,JsonNode>(); snapshot.path("relationships").forEach(e -> relationships.put(e.path("id").asText(),e));
        var cards=new TreeMap<String,Resource>();
        for (String id:policy.elements()) {
            var element=elements.get(id); if (element==null) { if (deleted(snapshot,id)) continue; throw new Failure(409,"SELECTED_ELEMENT_MISSING"); }
            String localType=element.path("archimateType").asText();
            var key=new ModelElementKey(instance,modelId,EntityKind.ELEMENT,id);
            var bound=metadata.findPublicationIdentity(key);
            var candidates=target.mapping().cardTypes().entrySet().stream().filter(e -> e.getValue().equals(localType)
                    && target.publicationAccess().cardTypes().contains(e.getKey())).map(Map.Entry::getKey).toList();
            String remote=bound.map(b -> b.canonical().kind()).filter(candidates::contains).orElseGet(() -> {
                if (candidates.size()!=1) throw new Failure(409,"AMBIGUOUS_OR_UNMAPPED_CARD_TYPE"); return candidates.getFirst();
            });
            var identity=identity(key,remote,localType,target.mapping().version(),bound);
            if (bound.isEmpty() && property(snapshot,id,ExternalObjectCatalogService.CANONICAL_PROPERTY)!=null)
                throw new Failure(409,"UNREGISTERED_PROVENANCE_REQUIRES_REATTACHMENT");
            var fields=new TreeMap<String,JsonNode>();
            for (var entry:target.mapping().fields().getOrDefault(remote,Map.of()).entrySet()) {
                var mapping=entry.getValue();
                if (mapping.authority()!=InformationAuthorityPolicy.Authority.ARCHIMESH_MASTER
                        || !target.publicationAccess().fields().contains(mapping.remoteField())) continue;
                JsonNode value=entry.getKey().startsWith("property:") ? property(snapshot,id,entry.getKey().substring(9)) : element.get(entry.getKey());
                if (value!=null && value.isTextual() && entry.getKey().startsWith("property:") && mapping.valueType()!=TurboEaMapping.ValueType.STRING) {
                    try { value=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value.asText()); }
                    catch (java.io.IOException invalid) { throw new Failure(409,"INVALID_TYPED_MODEL_PROPERTY"); }
                }
                if (value==null) value=json.nullNode();
                if (!value.isNull()) {
                    boolean valid=switch(mapping.valueType()) {
                        case STRING -> value.isTextual(); case NUMBER -> value.isNumber(); case BOOLEAN -> value.isBoolean();
                        case OBJECT -> value.isObject(); case ARRAY -> value.isArray();
                    };
                    if (!valid || mapping.valueType()==TurboEaMapping.ValueType.ARRAY && java.util.stream.StreamSupport.stream(value.spliterator(),false).anyMatch(v -> !v.isTextual()))
                        throw new Failure(409,"INVALID_TYPED_MODEL_PROPERTY");
                }
                if (mapping.remoteField().startsWith("attributes.") && !target.publicationAccess().completeAttributeReadsApproved())
                    throw new Failure(409,"COMPLETE_ATTRIBUTE_READS_NOT_APPROVED");
                if (mapping.remoteField().equals("name") && (!value.isTextual() || value.asText().isBlank() || value.asText().length()>512))
                    throw new Failure(409,"INVALID_PUBLICATION_NAME");
                fields.put(mapping.remoteField(),PublicationJson.field(value));
            }
            if (fields.size()>32 || json.valueToTree(fields).toString().length()>65536) throw new Failure(413,"PUBLICATION_FIELDS_TOO_LARGE");
            cards.put(id,new Resource(identity,remote,fields,null,null,policy.stewards().contains(id)));
        }
        var resources=new ArrayList<Resource>(cards.values());
        for (String id:policy.relationships()) {
            var relationship=relationships.get(id); if (relationship==null) { if (deleted(snapshot,id)) continue; throw new Failure(409,"SELECTED_RELATION_MISSING"); }
            String sourceId=relationship.path("sourceId").asText(),targetId=relationship.path("targetId").asText();
            if (!cards.containsKey(sourceId) || !cards.containsKey(targetId)) throw new Failure(409,"SELECT_RELATION_ENDPOINTS_FIRST");
            var mappings=target.mapping().relationTypes().entrySet().stream().filter(e -> {
                var m=e.getValue(); if (!target.publicationAccess().relationTypes().contains(e.getKey()) || !m.archimateType().equals(relationship.path("archimateType").asText())) return false;
                return m.sourceType().equals(cards.get(m.reversed() ? targetId : sourceId).remoteType())
                        && m.targetType().equals(cards.get(m.reversed() ? sourceId : targetId).remoteType());
            }).toList();
            if (mappings.size()!=1) throw new Failure(409,"AMBIGUOUS_OR_UNMAPPED_RELATION");
            var mapping=mappings.getFirst(); var key=new ModelElementKey(instance,modelId,EntityKind.RELATIONSHIP,id);
            resources.add(new Resource(identity(key,mapping.getKey(),relationship.path("archimateType").asText(),target.mapping().version(),metadata.findPublicationIdentity(key)),
                    mapping.getKey(),new TreeMap<>(),mapping.getValue().reversed() ? targetId : sourceId,
                    mapping.getValue().reversed() ? sourceId : targetId,policy.stewards().contains(id)));
        }
        var plan=new Plan(modelId,target.key(),target.instanceId(),policy.ref(),revision,target.mapping().version(),resources);
        if (json.valueToTree(plan).toString().length()>180000) throw new Failure(413,"PUBLICATION_PLAN_TOO_LARGE");
        return plan;
    }
    private PublicationIdentity identity(ModelElementKey key,String kind,String type,String mapping,Optional<PublicationIdentity> existing) {
        if (existing.isPresent()) {
            var value=existing.get();
            if (!value.archimateType().equals(type) || !value.mappingVersion().equals(mapping) || !value.canonical().kind().equals(kind))
                throw new Failure(409,"BINDING_MAPPING_MIGRATION_REQUIRED");
            return value;
        }
        String id="urn:uuid:"+UUID.nameUUIDFromBytes(PublicationJson.key("canonical:",key.instanceId(),key.sourceModelId(),key.entityKind().name(),key.entityId()).getBytes(StandardCharsets.UTF_8));
        return new PublicationIdentity(key,new CanonicalObject(id,kind),type,mapping);
    }
    private JsonNode property(JsonNode snapshot,String id,String key) {
        for (var entry:snapshot.path("propertyState")) if (id.equals(entry.path("targetId").asText()) && key.equals(entry.path("key").asText())
                && "SetProperty".equals(entry.path("type").asText())) return entry.get("value");
        return null;
    }
    private boolean deleted(JsonNode snapshot,String id) {
        for (var tombstone:snapshot.path("tombstones")) if (id.equals(tombstone.path("targetId").asText())) return true;
        return false;
    }
}
