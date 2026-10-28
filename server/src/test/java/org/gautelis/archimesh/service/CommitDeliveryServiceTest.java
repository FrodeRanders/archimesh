package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class CommitDeliveryServiceTest {
    @Test
    void failedPublicationRetainsIntentAndRetriesBeforeLaterBatches() throws Exception {
        var json = new ObjectMapper();
        List<JsonNode> pending = new ArrayList<>(List.of(
                json.createObjectNode().put("modelId", "m").put("opBatchId", "first"),
                json.createObjectNode().put("modelId", "m").put("opBatchId", "second")));
        var repository = (Neo4jRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Neo4jRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "pendingBroadcasts" -> List.copyOf(pending);
                    case "markBroadcastPublished" -> { pending.removeIf(batch -> args[1].equals(batch.path("opBatchId").asText())); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        List<String> attempts = new ArrayList<>();
        var publisher = (KafkaPublisher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{KafkaPublisher.class}, (proxy, method, args) -> {
                    attempts.add(((JsonNode) args[1]).path("opBatchId").asText());
                    return attempts.size() == 1 ? CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))
                            : CompletableFuture.completedFuture(null);
                });
        var delivery = new CommitDeliveryService();
        delivery.repository = repository; delivery.publisher = publisher;
        var valid = CommitDeliveryService.class.getDeclaredField("leaseValid"); valid.setAccessible(true); valid.set(delivery, true);
        delivery.drain();
        Assertions.assertEquals(2, pending.size(), "a failed publish must leave its durable intent pending");
        Assertions.assertEquals(List.of("first"), attempts);
        delivery.drain();
        Assertions.assertTrue(pending.isEmpty());
        Assertions.assertEquals(List.of("first", "first", "second"), attempts);
    }
}
