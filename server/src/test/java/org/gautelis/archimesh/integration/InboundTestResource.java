package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Same persistent HTTP repository, with an independently approved inbound/catalog scope. */
public class InboundTestResource extends PublicationTestResource {
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var path=Path.of(result.get("app.integrations.configuration"));
            var root=(ObjectNode)json.readTree(Files.readString(path)); String scope="m4-it-"+UUID.randomUUID(); root.put("instanceId",scope);
            var target=(ObjectNode)root.path("targets").get(0); target.put("instanceId",scope);
            target.remove("publicationAccess"); var mapping=(ObjectNode)target.path("mapping"); mapping.put("version","m4-fixture-v1");
            var fields=(ObjectNode)mapping.path("fields").path("Application"); ((ObjectNode)fields.path("name")).put("authority","TURBO_EA_MASTER");
            fields.putObject("documentation").put("remoteField","description").put("valueType","STRING").put("authority","ARCHIMESH_MASTER");
            var catalog=target.putObject("catalogAccess").put("approved",true); catalog.putArray("readers").add("architect").add("publisher"); catalog.putArray("importers").add("architect");
            catalog.putArray("cardTypes").add("Application"); catalog.putArray("fields").add("name").add("attributes.central");
            var inbound=target.putObject("inboundAccess").put("enabled",true).put("sseEnabled",false);
            inbound.putArray("administrators").add("publisher"); inbound.putArray("cardTypes").add("Application"); inbound.putArray("fields").add("name").add("attributes.central");
            Files.writeString(path,root.toString()); result.put("app.integrations.inbound.worker-enabled","false"); return result;
        } catch (Exception failed) { stop(); throw new IllegalStateException(failed); }
    }
}
