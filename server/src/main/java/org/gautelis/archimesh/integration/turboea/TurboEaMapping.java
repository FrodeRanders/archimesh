package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.integration.ExternalRepositoryConnector.Metamodel;
import org.gautelis.archimesh.integration.InformationAuthorityPolicy;
import org.gautelis.archimesh.integration.InformationAuthorityPolicy.Authority;
import org.gautelis.archimesh.integration.IntegrationIdentity;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Explicit versioned whitelist. Unmapped/hidden/changed semantics are rejected, never guessed. */
public record TurboEaMapping(String version, Map<String,String> cardTypes,
                             Map<String,RelationMapping> relationTypes,
                             Map<String,Map<String,FieldMapping>> fields) {
    public enum ValueType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }
    public record FieldMapping(String remoteField, ValueType valueType, Authority authority) {
        public FieldMapping {
            IntegrationIdentity.required(remoteField);
            if (valueType == null || authority == null) throw new IllegalArgumentException("Field type and authority are required");
        }
    }
    public record RelationMapping(String archimateType, String sourceType, String targetType,
                                  String cardinality, boolean reversed) {
        public RelationMapping {
            IntegrationIdentity.required(archimateType); IntegrationIdentity.required(sourceType);
            IntegrationIdentity.required(targetType); IntegrationIdentity.required(cardinality);
        }
    }
    private static final JsonNode RULES = rules();

    public TurboEaMapping {
        IntegrationIdentity.required(version);
        cardTypes = Map.copyOf(cardTypes); relationTypes = Map.copyOf(relationTypes);
        var copy = new java.util.HashMap<String,Map<String,FieldMapping>>();
        fields.forEach((type, values) -> copy.put(type,Map.copyOf(values)));
        fields = Map.copyOf(copy);
        if (cardTypes.isEmpty()) throw new IllegalArgumentException("At least one card mapping is required");
        cardTypes.forEach((remote,local) -> {
            IntegrationIdentity.required(remote);
            if (!RULES.has(local)) throw new IllegalArgumentException("Unsupported ArchiMate element type in pilot mapping");
        });
        for (var entry : fields.entrySet()) {
            if (!cardTypes.containsKey(entry.getKey())) throw new IllegalArgumentException("Field mapping has no card mapping");
            var remoteFields = new java.util.HashSet<String>();
            entry.getValue().forEach((local,field) -> {
                if (!(Set.of("name","documentation").contains(local)
                        || (local.startsWith("property:") && local.length() > 9)))
                    throw new IllegalArgumentException("Unsupported local model field");
                IntegrationIdentity.required(local);
                if (local.startsWith("property:")) {
                    String key=local.substring(9);
                    if (Set.of("modelId","id","archimateType","name","documentation","updatedRevision").contains(key)
                            || key.endsWith("_lamport") || key.endsWith("_clientId") || key.startsWith("orset:")
                            || key.startsWith("archimesh.external."))
                        throw new IllegalArgumentException("Mapped property collides with model identity, merge metadata or provenance");
                }
                if (Set.of("name","documentation").contains(local) && field.valueType() != ValueType.STRING)
                    throw new IllegalArgumentException("Semantic name/documentation mappings must be strings");
                if (!remoteFields.add(field.remoteField())) throw new IllegalArgumentException("Ambiguous field mapping");
            });
        }
    }

    public String archimateType(String remoteType) {
        String result = cardTypes.get(remoteType);
        if (result == null) throw new IllegalArgumentException("Unmapped external card type");
        return result;
    }

    /** Validate against a fresh target metamodel before any import/publication preview or mutation. */
    public void validate(Metamodel model) {
        for (var entry : cardTypes.entrySet()) {
            var remote = model.cardTypes().get(entry.getKey());
            if (remote == null || remote.hidden()) throw new IllegalArgumentException("Mapped card type is absent or hidden");
            for (var field : fields.getOrDefault(entry.getKey(),Map.of()).values()) {
                if (remoteFieldType(field.remoteField(),remote.fieldsSchema()) != field.valueType())
                    throw new IllegalArgumentException("Mapped field type/cardinality differs from target metamodel");
            }
        }
        for (var entry : relationTypes.entrySet()) {
            var remote = model.relationTypes().get(entry.getKey());
            var mapping = entry.getValue();
            if (remote == null || remote.hidden() || !remote.sourceType().equals(mapping.sourceType())
                    || !remote.targetType().equals(mapping.targetType()) || !remote.cardinality().equals(mapping.cardinality()))
                throw new IllegalArgumentException("Mapped relation type/direction/cardinality differs from target metamodel");
            String source = archimateType(mapping.sourceType()), target = archimateType(mapping.targetType());
            if (mapping.reversed()) { String swap=source; source=target; target=swap; }
            boolean allowed = false;
            for (var type : RULES.path(source).path(target)) if (type.asText().equals(mapping.archimateType())) allowed=true;
            if (!allowed) throw new IllegalArgumentException("Relationship is not allowed by the pinned Archi 5.8 matrix");
        }
    }

    public InformationAuthorityPolicy authorityPolicy() {
        var rules = new ArrayList<InformationAuthorityPolicy.Rule>();
        fields.forEach((type, values) -> values.forEach((local,field) -> rules.add(new InformationAuthorityPolicy.Rule(
                InformationAuthorityPolicy.Scope.TYPE,type,local,field.authority(),version))));
        return new InformationAuthorityPolicy(rules);
    }

    private static ValueType remoteFieldType(String field, JsonNode schema) {
        if (Set.of("name","description","status").contains(field)) return ValueType.STRING;
        if (field.equals("lifecycle")) return ValueType.OBJECT;
        if (!field.startsWith("attributes.") || field.length() < 12)
            throw new IllegalArgumentException("Unsupported remote field path");
        String key = field.substring("attributes.".length());
        ValueType found = null;
        for (var section : schema) {
            if (!section.path("fields").isArray()) throw new IllegalArgumentException("Malformed target fields schema");
            for (var definition : section.path("fields")) {
                if (!key.equals(definition.path("key").asText())) continue;
                if (found != null || definition.path("hidden").asBoolean()) throw new IllegalArgumentException("Ambiguous or hidden target field");
                found = switch (definition.path("type").asText()) {
                    case "text", "textarea", "select", "url", "email", "date" -> ValueType.STRING;
                    case "number", "integer", "percentage", "cost" -> ValueType.NUMBER;
                    case "boolean" -> ValueType.BOOLEAN;
                    case "multiselect" -> ValueType.ARRAY;
                    default -> throw new IllegalArgumentException("Unsupported target field datatype");
                };
            }
        }
        if (found == null) throw new IllegalArgumentException("Mapped attribute is absent in target metamodel");
        return found;
    }

    private static JsonNode rules() {
        try (var input = TurboEaMapping.class.getResourceAsStream("/integrations/archi-5.8-rules.json")) {
            if (input == null) throw new IllegalStateException("Missing pinned Archi relationship rules");
            return new ObjectMapper().readTree(input).path("rules");
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
