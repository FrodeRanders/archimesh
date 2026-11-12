package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

public class CanonicalAuthorityTestResource extends InboundTestResource {
    @Override public Map<String,String> start() {
        var result=super.start();
        try {
            var json=new ObjectMapper(); var path=Path.of(result.get("app.integrations.configuration"));
            var root=(ObjectNode)json.readTree(Files.readString(path)); var target=(ObjectNode)root.path("targets").get(0);
            ((ObjectNode)target.path("mapping")).putObject("canonicalAuthorities")
                    .putObject("urn:"+root.path("instanceId").asText()+":override")
                    .put("name","ARCHIMESH_MASTER").put("documentation","TURBO_EA_MASTER");
            ((com.fasterxml.jackson.databind.node.ArrayNode)target.path("catalogAccess").path("fields")).add("description");
            ((com.fasterxml.jackson.databind.node.ArrayNode)target.path("inboundAccess").path("fields")).add("description");
            Files.writeString(path,root.toString()); return result;
        } catch (Exception failure) { stop(); throw new IllegalStateException(failure); }
    }
}
