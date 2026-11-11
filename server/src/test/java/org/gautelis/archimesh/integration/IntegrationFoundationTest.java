package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.InformationAuthorityPolicy.*;
import static org.gautelis.archimesh.integration.ExternalRepositoryConnector.*;
import static org.gautelis.archimesh.integration.turboea.TurboEaMapping.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationFoundationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void mappedPropertiesCannotOverwriteEntityIdentityOrMergeMetadata() {
        for (String key:List.of("modelId","id","archimateType","name","name_lamport","name_clientId","orset:members","archimesh.external.canonicalId"))
            assertThrows(IllegalArgumentException.class,() -> new TurboEaMapping("v1",Map.of("Application","ApplicationComponent"),Map.of(),
                    Map.of("Application",Map.of("property:"+key,new FieldMapping("name",ValueType.STRING,Authority.TURBO_EA_MASTER)))));
    }

    @Test void missingIdentityComponentsAndKindCollisionsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new ModelElementKey("instance"," ",EntityKind.ELEMENT,"elem:a"));
        assertThrows(IllegalArgumentException.class,() -> new ModelElementKey("instance","model",EntityKind.ELEMENT,"rel:a"));
        assertThrows(IllegalArgumentException.class,() -> new ExternalResourceKey("turboea",null,ResourceKind.CARD,"a"));
        assertThrows(IllegalArgumentException.class,() -> new ImportReservation(
                new ModelElementKey("i","m",EntityKind.ELEMENT,"elem:a"), new CanonicalObject("urn:a","application"),
                new ExternalResourceKey("turboea","target",ResourceKind.RELATION,"r"),"ApplicationComponent","v1","batch"));
    }

    @Test void authorityDefaultsToUnmappedAndCanonicalOverrideWins() {
        var policy = new InformationAuthorityPolicy(List.of(
                new Rule(Scope.TYPE,"Application","name",Authority.TURBO_EA_MASTER,"v1"),
                new Rule(Scope.CANONICAL,"urn:a","name",Authority.SHARED_PROPOSAL,"v2")));
        assertEquals(Authority.SHARED_PROPOSAL,policy.authority("urn:a","Application","name"));
        assertEquals(Authority.TURBO_EA_MASTER,policy.authority("urn:b","Application","name"));
        assertEquals(Authority.UNMAPPED,policy.authority(null,"Application","documentation"));
        assertThrows(IllegalArgumentException.class,() -> new InformationAuthorityPolicy(List.of(
                new Rule(Scope.TYPE,"Application","name",Authority.UNMAPPED,"v1"),
                new Rule(Scope.TYPE,"Application","name",Authority.TURBO_EA_MASTER,"v2"))));
    }

    @Test void mappingValidatesCaseSensitiveKeysAttributeTypesAndHiddenTypes() throws Exception {
        var mapping = new TurboEaMapping("v1",Map.of("Application","ApplicationComponent"),Map.of(),
                Map.of("Application",Map.of("name",new FieldMapping("name",ValueType.STRING,Authority.TURBO_EA_MASTER),
                        "property:owners",new FieldMapping("attributes.owners",ValueType.ARRAY,Authority.TURBO_EA_MASTER))));
        var schema = json.readTree("[{\"fields\":[{\"key\":\"owners\",\"type\":\"multiselect\"}]}]");
        var model = new Metamodel(Map.of("Application",new CardType("Application",schema,false)),Map.of());
        mapping.validate(model);
        assertEquals("ApplicationComponent",mapping.archimateType("Application"));
        assertThrows(IllegalArgumentException.class,() -> mapping.archimateType("application"));
        assertThrows(IllegalArgumentException.class,() -> mapping.validate(new Metamodel(Map.of(),Map.of())));
        assertThrows(IllegalArgumentException.class,() -> mapping.validate(new Metamodel(
                Map.of("Application",new CardType("Application",schema,true)),Map.of())));
        var changed = json.readTree("[{\"fields\":[{\"key\":\"owners\",\"type\":\"text\"}]}]");
        assertThrows(IllegalArgumentException.class,() -> mapping.validate(new Metamodel(
                Map.of("Application",new CardType("Application",changed,false)),Map.of())));
        assertEquals(Authority.UNMAPPED,mapping.authorityPolicy().authority(null,"Application","documentation"));
        assertThrows(IllegalArgumentException.class,() -> new TurboEaMapping("v1",Map.of("Application","InventedArchiClass"),Map.of(),Map.of()));
    }

    @Test void relationDirectionCardinalityAndArchiMatrixAreValidated() {
        var cards = Map.of("Organization","BusinessActor","BusinessProcess","BusinessProcess");
        var metamodel = new Metamodel(Map.of("Organization",new CardType("Organization",json.createArrayNode(),false),
                "BusinessProcess",new CardType("BusinessProcess",json.createArrayNode(),false)),
                Map.of("Performs",new RelationType("Performs","Organization","BusinessProcess","n:m",json.createArrayNode(),false)));
        var valid = new RelationMapping("AssignmentRelationship","Organization","BusinessProcess","n:m",false);
        new TurboEaMapping("v1",cards,Map.of("Performs",valid),Map.of()).validate(metamodel);
        for (var invalid : List.of(
                new RelationMapping("AssignmentRelationship","Organization","BusinessProcess","1:1",false),
                new RelationMapping("AssignmentRelationship","Organization","BusinessProcess","n:m",true),
                new RelationMapping("AccessRelationship","Organization","BusinessProcess","n:m",false)))
            assertThrows(IllegalArgumentException.class,() -> new TurboEaMapping("v1",cards,Map.of("Performs",invalid),Map.of()).validate(metamodel));
    }

    @Test void disabledRuntimeIgnoresInvalidConfigAndAllocatesNoClientsOrDrivers() {
        var runtime = new IntegrationRuntime(); runtime.enabled=false;
        runtime.configuration=Optional.of("/nonexistent/integration-secret-config.json");
        runtime.start();
        assertFalse(runtime.enabled());
        assertThrows(IllegalStateException.class,() -> runtime.connector("development"));
        assertThrows(IllegalStateException.class,runtime::metadata); runtime.stop();
    }

    @Test void enabledStartupValidatesConfigurationWithoutContactingRemoteOrNeo4j() throws Exception {
        var path = Files.createTempFile("archimesh-integration-config-",".json");
        String config = """
                {"schemaVersion":1,"instanceId":"archimesh-test","targets":[{
                  "key":"development","instanceId":"turboea-test","baseUrl":"http://127.0.0.1:1",
                  "tokenEnvironment":"PATH","expectedApiVersion":"2.194.0","timeoutMillis":500,"maxResponseBytes":4096,
                  "mapping":{"version":"v1","cardTypes":{"Application":"ApplicationComponent"},"relationTypes":{},"fields":{}}
                }]}
                """;
        var runtime = new IntegrationRuntime(); runtime.enabled=true; runtime.json=json; runtime.configuration=Optional.of(path.toString());
        try {
            Files.writeString(path,config); runtime.start();
            assertNotNull(runtime.connector("development"));
            assertThrows(IllegalArgumentException.class,() -> runtime.connector("unknown"));
            runtime.stop();
            Files.writeString(path,config.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"unexpected\":\"private-value\""));
            var error = assertThrows(IllegalStateException.class,runtime::start);
            assertFalse(error.toString().contains("private-value")); assertNull(error.getCause());
        } finally { runtime.stop(); Files.deleteIfExists(path); }
    }
}
