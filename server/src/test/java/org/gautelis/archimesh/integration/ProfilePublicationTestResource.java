package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

public class ProfilePublicationTestResource extends PublicationTestResource {
    private Path catalog;
    @Override protected String cardTypes() { return "[{\"key\":\"Application\",\"fields_schema\":[],\"is_hidden\":false},{\"key\":\"LogicalData\",\"fields_schema\":[],\"is_hidden\":false}]"; }
    @Override protected String relationTypes() { return """
        [{"key":"ApplicationDataAccess","source_type_key":"Application","target_type_key":"LogicalData","cardinality":"n:m","attributes_schema":[{"key":"operations","type":"multiselect","options":[{"key":"READ"},{"key":"WRITE"}]}],"is_hidden":false}]
        """; }
    @Override protected void configureTarget(ObjectNode target) {
        var mapping=(ObjectNode)target.path("mapping");
        mapping.putObject("cardTypes").put("Application","ApplicationComponent").put("LogicalData","DataObject");
        mapping.putObject("relationTypes").putObject("ApplicationDataAccess").put("archimateType","AccessRelationship").put("sourceType","Application").put("targetType","LogicalData").put("cardinality","n:m").put("reversed",false);
        var fields=mapping.putObject("fields");
        for(String type:List.of("Application","LogicalData")) fields.putObject(type).putObject("name").put("remoteField","name").put("valueType","STRING").put("authority","TURBO_EA_MASTER");
        var access=(ObjectNode)target.path("publicationAccess"); access.putArray("cardTypes").add("Application").add("LogicalData");
        access.putArray("relationTypes").add("ApplicationDataAccess"); access.putArray("fields").add("name").add("attributes.operations");
    }
    @Override protected Map<String,String> additionalConfiguration() throws Exception {
        var json=new ObjectMapper(); var root=(ObjectNode)json.readTree(Files.readString(Path.of("examples/publication-profiles.example.json")));
        var profile=(ObjectNode)root.path("profiles").get(0); profile.put("derivationsEnabled",true);
        var data=(ObjectNode)profile.path("elements").get(1); data.putArray("fields"); data.put("allowCreate",false).put("referenceOnly",true);
        ((ArrayNode)root.path("profiles")).add(profile.deepCopy().put("version","2.0.0"));
        catalog=Files.createTempFile("archimesh-profile-it-",".json"); Files.writeString(catalog,root.toString());
        return Map.of("app.publication.profiles-enabled","true","app.publication.profiles-configuration",catalog.toString());
    }
    @Override public void stop() { super.stop(); if(catalog!=null) try { Files.deleteIfExists(catalog); } catch(java.io.IOException ignored) { } }
}
