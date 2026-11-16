package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FederationProjectionConfigurationTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode example() throws Exception {
        return (ObjectNode)json.readTree(Files.readString(Path.of("examples/federation-sources.example.json")));
    }
    @Test void existingSourceConfigurationAndDisabledApplicationExtensionRemainCompatible() throws Exception {
        var extended=example(); var value=json.treeToValue(extended,FederationConfiguration.class);
        assertEquals(1,value.applicationProjections().size()); assertFalse(value.applicationProjections().getFirst().approved());
        extended.remove("applicationProjections"); extended.remove("datahubTargets");
        var previous=json.treeToValue(extended,FederationConfiguration.class);
        assertTrue(previous.applicationProjections().isEmpty()); assertTrue(previous.datahubTargets().isEmpty());
    }
    @Test void applicationAndDatasetPrincipalsCannotShareTheSameDataHubCredentialEnvironment() throws Exception {
        var value=example(); ((ObjectNode)value.path("datahubTargets").get(0)).put("tokenEnvironment","DATAHUB_TOKEN");
        assertThrows(Exception.class,() -> json.treeToValue(value,FederationConfiguration.class));
    }
    @Test void sourceSemanticKindAdoptionNativeTypeAndExactApplicationScopeMustBeExplicit() throws Exception {
        for(String invalid:new String[]{"kind","urn","scope","target"}) {
            var value=example(); var projection=(ObjectNode)value.path("applicationProjections").get(0);
            switch(invalid) {
                case "kind" -> ((ObjectNode)value.path("sources").get(1).path("types").path("Application")).put("semanticKind","catalog.dataset");
                case "urn" -> projection.putObject("adoptions").put("replace-with-approved-native-application-card-id","urn:li:dataset:wrong");
                case "scope" -> projection.putArray("nativeIds");
                case "target" -> projection.put("targetKey","unconfigured");
            }
            assertThrows(Exception.class,() -> json.treeToValue(value,FederationConfiguration.class),invalid);
        }
    }
}
