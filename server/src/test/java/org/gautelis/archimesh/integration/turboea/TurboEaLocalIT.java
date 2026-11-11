package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.integration.IntegrationConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Optional read-only deployment check; creates no accounts, cards, relations or model operations. */
class TurboEaLocalIT {
    @Test void configuredLocalTargetHasCompatibleMetamodelAndCatalog() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_TURBO_EA_IT")));
        var json=new ObjectMapper();
        var config=json.readValue(Path.of("examples/turboea-integration.example.json").toFile(),IntegrationConfiguration.class);
        var target=config.targets().getFirst();
        try (var client=new TurboEaClient(URI.create(System.getenv("TURBO_EA_BASE_URL")),target.expectedApiVersion(),
                () -> System.getenv("TURBO_EA_TOKEN"),Duration.ofSeconds(10),target.maxResponseBytes(),json)) {
            assertEquals(target.expectedApiVersion(),client.healthVersion());
            var metamodel=client.metamodel(); target.mapping().validate(metamodel);
            assertTrue(metamodel.cardTypes().containsKey("Application"));
            var page=client.search(null,"Application",1,2);
            assertEquals(1,page.page()); assertEquals(2,page.pageSize());
            for (var object : page.items()) {
                assertEquals(object.id(),client.getObject(object.id()).id());
                client.relations(object.id());
            }
        }
    }
}
