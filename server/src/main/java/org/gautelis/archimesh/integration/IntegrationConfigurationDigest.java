package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Target configuration arrays represent sets; their iteration order must not revoke approvals after restart. */
public final class IntegrationConfigurationDigest {
    private IntegrationConfigurationDigest() { }
    public static String target(IntegrationConfiguration.Target target) { return PublicationJson.digest(sorted(new ObjectMapper().valueToTree(target))); }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) { var result=JsonNodeFactory.instance.objectNode(); value.fields().forEachRemaining(e -> result.set(e.getKey(),sorted(e.getValue()))); return result; }
        if (value.isArray()) { var items=new ArrayList<JsonNode>(); value.forEach(v -> items.add(sorted(v))); items.sort(Comparator.comparing(JsonNode::toString)); var result=JsonNodeFactory.instance.arrayNode(); items.forEach(result::add); return result; }
        return value;
    }
}
