package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** M4 supports mapped card fields only; relationships and geometry are never synthesized. */
public final class InboundFields {
    private InboundFields() { }
    public static Map<String,TurboEaMapping.FieldMapping> mappings(IntegrationConfiguration.Target target,String type) {
        return mappings(target,type,null);
    }
    public static Map<String,TurboEaMapping.FieldMapping> mappings(IntegrationConfiguration.Target target,String type,String canonical) {
        var result=normalMappings(target,type,canonical);
        if (result.isEmpty()) throw new Failure(409,"INBOUND_FIELDS_NOT_APPROVED");
        return result;
    }
    public static Map<String,TurboEaMapping.FieldMapping> normalMappings(IntegrationConfiguration.Target target,String type) {
        return normalMappings(target,type,null);
    }
    public static Map<String,TurboEaMapping.FieldMapping> normalMappings(IntegrationConfiguration.Target target,String type,String canonical) {
        if (!target.inboundAccess().cardTypes().contains(type)) throw new Failure(409,"INBOUND_TYPE_NOT_APPROVED");
        var result=new TreeMap<String,TurboEaMapping.FieldMapping>();
        target.mapping().fieldsFor(type,canonical).forEach((local,field) -> {
            if (field.authority()==InformationAuthorityPolicy.Authority.TURBO_EA_MASTER && target.inboundAccess().fields().contains(field.remoteField())) result.put(local,field);
        });
        if (result.size()>32) throw new Failure(409,"INBOUND_FIELDS_NOT_APPROVED");
        return Collections.unmodifiableMap(result);
    }
    public static ObjectNode remote(ObjectMapper json,IntegrationConfiguration.Target target,ExternalRepositoryConnector.ExternalObject card) {
        return remote(json,target,card,null);
    }
    public static ObjectNode remote(ObjectMapper json,IntegrationConfiguration.Target target,ExternalRepositoryConnector.ExternalObject card,String canonical) {
        return remote(json,card,mappings(target,card.type(),canonical));
    }
    public static ObjectNode remote(ObjectMapper json,ExternalRepositoryConnector.ExternalObject card,Map<String,TurboEaMapping.FieldMapping> mappings) {
        var result=json.createObjectNode();
        mappings.forEach((local,field) -> {
            JsonNode value=switch(field.remoteField()) {
                case "name" -> json.valueToTree(card.name()); case "description" -> json.valueToTree(card.description());
                case "status" -> json.valueToTree(card.status()); case "lifecycle" -> card.lifecycle();
                default -> {
                    if (!field.remoteField().startsWith("attributes.")) throw new Failure(409,"UNSUPPORTED_INBOUND_FIELD");
                    yield card.attributes()==null ? null : card.attributes().get(field.remoteField().substring(11));
                }
            };
            if (value==null || value.isNull()) {
                result.set(local,local.startsWith("property:") ? json.nullNode() : json.getNodeFactory().textNode(""));
            } else {
                boolean valid=switch(field.valueType()) { case STRING -> value.isTextual(); case NUMBER -> value.isNumber(); case BOOLEAN -> value.isBoolean(); case OBJECT -> value.isObject(); case ARRAY -> value.isArray(); };
                if (!valid || value.isArray() && java.util.stream.StreamSupport.stream(value.spliterator(),false).anyMatch(v -> !v.isTextual())) throw new Failure(409,"INBOUND_FIELD_TYPE_CONFLICT");
                String text=value.isTextual() ? value.asText() : PublicationJson.field(value).toString();
                if (text.length()>(local.equals("name") ? 512 : 16384) || local.equals("name") && text.isBlank() || text.indexOf('\0')>=0)
                    throw new Failure(409,"INBOUND_FIELD_VALUE_INVALID");
                result.put(local,text);
            }
        });
        if (result.toString().length()>65536) throw new Failure(413,"INBOUND_VALUES_TOO_LARGE");
        return result;
    }
    public static ObjectNode local(ObjectMapper json,JsonNode snapshot,String elementId,Set<String> fields) {
        JsonNode element=null;
        for (var row:snapshot.path("elements")) if (row.path("id").asText().equals(elementId)) element=row;
        if (element==null) throw new Failure(409,"LOCAL_ELEMENT_MISSING");
        var result=json.createObjectNode();
        for (String field:new TreeSet<>(fields)) {
            JsonNode value=null;
            if (field.startsWith("property:")) {
                for (var row:snapshot.path("propertyState")) if (row.path("targetId").asText().equals(elementId) && row.path("key").asText().equals(field.substring(9)) && row.path("type").asText().equals("SetProperty")) value=row.get("value");
            } else value=element.get(field);
            result.set(field,value==null || value.isNull() ? field.startsWith("property:") ? json.nullNode() : json.getNodeFactory().textNode("") : value);
        }
        return result;
    }
    public static JsonNode element(JsonNode snapshot,String id) {
        for (var element:snapshot.path("elements")) if (id.equals(element.path("id").asText())) return element;
        throw new Failure(409,"LOCAL_ELEMENT_MISSING");
    }
}
