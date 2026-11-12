package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping.FieldMapping;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** A central edit changes only explicitly mapped fields. Complex proposed values arrive as lossless JSON text. */
public final class CentralFields {
    private CentralFields() { }
    public static Map<String,FieldMapping> mappings(IntegrationConfiguration.Target target,String type) {
        if (!target.centralEditAccess().enabled() || !target.centralEditAccess().cardTypes().contains(type)) throw new Failure(403,"CENTRAL_TYPE_NOT_APPROVED");
        var result=new TreeMap<String,FieldMapping>();
        target.mapping().fields().getOrDefault(type,Map.of()).forEach((local,field) -> {
            if (target.centralEditAccess().fields().containsKey(field.remoteField()) && (field.authority()==InformationAuthorityPolicy.Authority.TURBO_EA_MASTER || field.authority()==InformationAuthorityPolicy.Authority.SHARED_PROPOSAL)) result.put(local,field);
        });
        if (result.isEmpty() || result.size()>32) throw new Failure(409,"CENTRAL_FIELDS_NOT_APPROVED");
        return Map.copyOf(result);
    }
    public static Set<String> shared(IntegrationConfiguration.Target target,String type,Set<String> selected) {
        var result=new TreeSet<String>(); mappings(target,type).forEach((local,mapping) -> { if (selected.contains(local) && mapping.authority()==InformationAuthorityPolicy.Authority.SHARED_PROPOSAL) result.add(local); }); return result;
    }
    public static ObjectNode proposed(ObjectMapper json,IntegrationConfiguration.Target target,String type,Map<String,JsonNode> input) {
        if (input==null || input.isEmpty() || input.size()>8) throw new Failure(400,"CHOOSE_CENTRAL_FIELDS");
        var mappings=mappings(target,type); var result=json.createObjectNode();
        for (String local:new TreeSet<>(input.keySet())) {
            var mapping=mappings.get(local); if (mapping==null) throw new Failure(403,"CENTRAL_FIELD_NOT_APPROVED");
            JsonNode value=input.get(local);
            if (value==null) throw new Failure(400,"CENTRAL_VALUE_REQUIRED");
            if (!value.isNull() && mapping.valueType()!=org.gautelis.archimesh.integration.turboea.TurboEaMapping.ValueType.STRING) {
                if (!value.isTextual() || value.asText().length()>16384) throw new Failure(400,"TYPED_VALUE_REQUIRES_JSON_TEXT");
                try { value=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(value.asText()); }
                catch (java.io.IOException invalid) { throw new Failure(400,"INVALID_CENTRAL_TYPED_VALUE"); }
            }
            validate(local,mapping,value); result.set(local,PublicationJson.field(value));
        }
        if (result.toString().length()>32768) throw new Failure(413,"CENTRAL_VALUES_TOO_LARGE");
        return result;
    }
    public static void validate(String local,FieldMapping mapping,JsonNode value) {
        if (value==null || value.isMissingNode()) throw new Failure(400,"CENTRAL_VALUE_REQUIRED");
        if (!value.isNull()) {
            boolean valid=switch(mapping.valueType()) { case STRING -> value.isTextual(); case NUMBER -> value.isNumber(); case BOOLEAN -> value.isBoolean(); case OBJECT -> value.isObject(); case ARRAY -> value.isArray(); };
            if (!valid || value.isArray() && java.util.stream.StreamSupport.stream(value.spliterator(),false).anyMatch(v -> !v.isTextual())) throw new Failure(400,"CENTRAL_FIELD_TYPE_CONFLICT");
            if (value.toString().length()>16384 || value.isTextual() && value.asText().indexOf('\0')>=0) throw new Failure(400,"CENTRAL_VALUE_INVALID");
        }
        if (mapping.remoteField().equals("name") && (value.isNull() || !value.isTextual() || value.asText().isBlank() || value.asText().length()>512)) throw new Failure(400,"CENTRAL_NAME_REQUIRED");
    }
    public static ObjectNode remote(ObjectMapper json,IntegrationConfiguration.Target target,ExternalRepositoryConnector.ExternalObject card,Set<String> selected) {
        var result=json.createObjectNode(); var mappings=mappings(target,card.type());
        for (String local:new TreeSet<>(selected)) {
            var mapping=mappings.get(local); if (mapping==null) throw new Failure(403,"CENTRAL_FIELD_NOT_APPROVED");
            JsonNode value=switch(mapping.remoteField()) {
                case "name" -> json.valueToTree(card.name()); case "description" -> json.valueToTree(card.description()); case "lifecycle" -> card.lifecycle();
                default -> card.attributes()==null ? null : card.attributes().get(mapping.remoteField().substring(11));
            };
            if (value==null) value=json.nullNode(); validate(local,mapping,value); result.set(local,PublicationJson.field(value));
        }
        if (result.toString().length()>32768) throw new Failure(413,"CENTRAL_VALUES_TOO_LARGE");
        return result;
    }
    public static ObjectNode localRepresentation(ObjectMapper json,JsonNode typed) {
        var result=json.createObjectNode();
        typed.fields().forEachRemaining(entry -> {
            var value=entry.getValue();
            if (value.isNull()) result.set(entry.getKey(),entry.getKey().startsWith("property:") ? json.nullNode() : json.getNodeFactory().textNode(""));
            else result.put(entry.getKey(),value.isTextual() ? value.asText() : PublicationJson.field(value).toString());
        }); return result;
    }
    public static ObjectNode payload(ObjectMapper json,IntegrationConfiguration.Target target,String type,JsonNode proposed,ExternalRepositoryConnector.ExternalObject card) {
        var result=json.createObjectNode(); ObjectNode attributes=null; var mappings=mappings(target,type);
        for (String local:keys(proposed)) {
            var mapping=mappings.get(local); validate(local,mapping,proposed.path(local)); String field=mapping.remoteField();
            if (field.startsWith("attributes.")) {
                if (attributes==null) { attributes=card.attributes()==null ? json.createObjectNode() : (ObjectNode)card.attributes().deepCopy(); result.set("attributes",attributes); }
                attributes.set(field.substring(11),proposed.path(local));
            } else result.set(field,proposed.path(local));
        }
        return result;
    }
    public static String display(JsonNode value) { return value==null || value.isNull() ? "(not set)" : value.isTextual() ? value.asText() : PublicationJson.field(value).toString(); }
    public static Map<String,String> displayMap(JsonNode values) { var result=new TreeMap<String,String>(); values.fields().forEachRemaining(e -> result.put(e.getKey(),display(e.getValue()))); return Map.copyOf(result); }
    public static Set<String> keys(JsonNode object) { var result=new TreeSet<String>(); object.fieldNames().forEachRemaining(result::add); return result; }
}
