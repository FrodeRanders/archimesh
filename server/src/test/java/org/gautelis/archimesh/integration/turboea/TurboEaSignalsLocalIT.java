package org.gautelis.archimesh.integration.turboea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in: synthetic card writes on a disposable instance with an SSE-capable account. */
class TurboEaSignalsLocalIT {
    @Test void cookieAuthenticatedSseInvalidatesAfterVerifiedCardPatch() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_TURBO_EA_SIGNAL_IT")));
        var json=new ObjectMapper(); var base=URI.create(System.getenv("TURBO_EA_BASE_URL"));
        try (var client=new TurboEaClient(base,"2.194.0",() -> System.getenv("TURBO_EA_TOKEN"),Duration.ofSeconds(10),2097152,json);
             var stream=new TurboEaSignals(base,() -> System.getenv("TURBO_EA_TOKEN"),json,Duration.ofSeconds(5));
             var worker=Executors.newSingleThreadExecutor()) {
            assertEquals("2.194.0",client.healthVersion());
            var card=client.createObject(json.createObjectNode().put("type","Application").put("name","M4 synthetic SSE fixture").put("external_id","urn:uuid:"+UUID.randomUUID()).put("strict_attributes",true));
            var ids=new LinkedBlockingQueue<String>();
            var listening=worker.submit(() -> stream.listen(ids::add,Duration.ofSeconds(15)));
            String observed=null;
            for (int i=0;i<20 && observed==null;i++) {
                client.patchObject(card.id(),json.createObjectNode().put("name","M4 verified central update "+i));
                String id=ids.poll(500,TimeUnit.MILLISECONDS); if (card.id().equals(id)) observed=id;
                if (listening.isDone() && observed==null) { listening.get(); break; }
            }
            assertEquals(card.id(),observed,"SSE must carry only the invalidation ID; polling reads the authoritative card");
            assertTrue(client.getObject(card.id()).name().startsWith("M4 verified central update"));
            stream.close(); worker.shutdownNow();
        }
    }
}
