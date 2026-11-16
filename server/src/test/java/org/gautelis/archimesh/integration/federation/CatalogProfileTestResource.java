package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

public class CatalogProfileTestResource extends DatasetProjectionTestResource {
    private Path profiles;
    @Override protected Map<String,String> additionalConfiguration() throws Exception {
        var json=new ObjectMapper(); var root=(ObjectNode)json.readTree(Files.readString(Path.of("examples/publication-profiles.example.json")));
        var profile=(ObjectNode)root.path("profiles").get(0); profile.put("profileId","catalog-connection");
        ((ArrayNode)profile.path("elements")).remove(1); profile.putArray("derivations");
        profile.putArray("relationships").addObject().put("archimateType","ServingRelationship").put("mappingRef","Serves").putArray("sourceIds");
        ((ObjectNode)profile.path("relationships").get(0)).put("requiresApprovedEndpoints",true);
        profiles=Files.createTempFile("archimesh-catalog-profile-",".json"); Files.writeString(profiles,root.toString());
        return Map.of("app.publication.profiles-enabled","true","app.publication.profiles-configuration",profiles.toString());
    }
    @Override public void stop() { super.stop(); if(profiles!=null) try { Files.deleteIfExists(profiles); } catch(java.io.IOException ignored) { } }
}
