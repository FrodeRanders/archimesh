package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in: creates synthetic resources. Run only against a disposable target. */
class TurboEaPublicationLocalIT {
    @Test void writesAndReadsBackCardsAndStructuralRelationOnDisposableDeployment() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_TURBO_EA_WRITE_IT")));
        var json=new ObjectMapper();
        try (var client=new TurboEaClient(URI.create(System.getenv("TURBO_EA_BASE_URL")),"2.194.0",() -> System.getenv("TURBO_EA_TOKEN"),Duration.ofSeconds(15),2097152,json)) {
            assertEquals("2.194.0",client.healthVersion());
            var identity="urn:uuid:"+UUID.randomUUID();
            var a=client.createObject(json.createObjectNode().put("type","Application").put("name","M3 synthetic A").put("external_id",identity).put("strict_attributes",true));
            var b=client.createObject(json.createObjectNode().put("type","Application").put("name","M3 synthetic B").put("external_id","urn:uuid:"+UUID.randomUUID()).put("strict_attributes",true));
            assertEquals(identity,client.getObject(a.id()).externalId());
            client.patchObject(a.id(),json.createObjectNode().put("name","M3 published winner").put("description","Approved local projection"));
            var updated=client.getObject(a.id()); assertEquals("M3 published winner",updated.name()); assertEquals(identity,updated.externalId()); assertEquals("ACTIVE",updated.status());
            var type=client.metamodel().relationTypes().values().stream().filter(r -> r.sourceType().equals("Application") && r.targetType().equals("Application") && !r.hidden()).findFirst().orElseThrow();
            var relation=client.createRelation(type.key(),a.id(),b.id());
            assertTrue(client.relations(a.id()).stream().anyMatch(r -> r.id().equals(relation.id()) && r.sourceId().equals(a.id()) && r.targetId().equals(b.id())));
            // Reconciliation's equal-value branch performs reads only; no second POST or tuple upsert.
            assertEquals("M3 published winner",client.getObject(a.id()).name());
        }
    }
}
