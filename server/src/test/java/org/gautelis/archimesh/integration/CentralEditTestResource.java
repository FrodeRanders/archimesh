package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

public class CentralEditTestResource extends InboundTestResource {
    @Override public Map<String,String> start() {
        var result=new HashMap<>(super.start());
        try {
            var json=new ObjectMapper(); var path=Path.of(result.get("app.integrations.configuration")); var root=(ObjectNode)json.readTree(Files.readString(path));
            String scope="m5-it-"+UUID.randomUUID(); root.put("instanceId",scope); var target=(ObjectNode)root.path("targets").get(0); target.put("instanceId",scope);
            ((ObjectNode)target.path("mapping")).put("version","m5-fixture-v1"); var fields=(ObjectNode)target.path("mapping").path("fields").path("Application");
            fields.putObject("property:shared").put("remoteField","attributes.shared").put("valueType","STRING").put("authority","SHARED_PROPOSAL");
            fields.putObject("property:budget").put("remoteField","attributes.budget").put("valueType","NUMBER").put("authority","TURBO_EA_MASTER");
            ((ArrayNode)target.path("catalogAccess").path("fields")).add("attributes.budget");
            ((ArrayNode)target.path("inboundAccess").path("fields")).add("attributes.shared").add("attributes.budget");
            ((ArrayNode)target.path("inboundAccess").path("administrators")).add("resolver");
            var central=target.putObject("centralEditAccess").put("enabled",true).put("acceptUnconditionalWriteRisk",true).put("completeAttributeReadsApproved",true);
            central.putArray("requesters").add("architect"); central.putArray("approvers").add("publisher"); central.putArray("cardTypes").add("Application");
            central.putObject("fields").put("name","PROPOSAL").put("attributes.central","WRITE_THROUGH").put("attributes.shared","PROPOSAL").put("attributes.budget","PROPOSAL");
            var publication=target.putObject("publicationAccess").put("enabled",true).put("acceptUnconditionalWriteRisk",true).put("completeAttributeReadsApproved",true);
            publication.putArray("publishers").add("publisher"); publication.putArray("cardTypes").add("Application"); publication.putArray("fields").add("attributes.owner");
            Files.writeString(path,root.toString()); result.put("app.integrations.central-edits.worker-enabled","false"); return result;
        } catch (Exception failure) { stop(); throw new IllegalStateException(failure); }
    }
}
