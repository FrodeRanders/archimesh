package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class KafkaPublisherImplTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockProducer<String, String> producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());

    private KafkaPublisherImpl publisher() {
        KafkaPublisherImpl publisher = new KafkaPublisherImpl();
        publisher.topicPrefix = "archimesh.model";
        publisher.objectMapper = mapper;
        publisher.producer = producer;
        return publisher;
    }

    @Test
    void allModelsShareTopicsAndEveryEventUsesModelKey() throws Exception {
        var publisher = publisher();
        for (String modelId : List.of("model.a", "model.b")) {
            publisher.publishOps(modelId, mapper.createObjectNode().put("modelId", modelId));
            publisher.publishLockEvent(modelId, Map.of("targets", List.of()));
            publisher.publishPresence(modelId, Map.of("viewId", "view1"));
        }
        assertEquals(List.of("archimesh.model.ops", "archimesh.model.locks", "archimesh.model.presence"),
                producer.history().stream().map(r -> r.topic()).distinct().toList());
        assertEquals(List.of("model.a", "model.a", "model.a", "model.b", "model.b", "model.b"),
                producer.history().stream().map(r -> r.key()).toList());
        assertEquals("view1", mapper.readTree(producer.history().get(2).value()).path("viewId").asText());
    }

    @Test
    void operationFutureWaitsForBrokerAcknowledgmentAndReportsFailure() {
        var publisher = publisher();
        var success = publisher.publishOps("a", mapper.createObjectNode());
        assertFalse(success.isDone());
        producer.completeNext();
        success.join();
        var failure = publisher.publishOps("b", mapper.createObjectNode());
        var cause = new IllegalStateException("broker unavailable");
        producer.errorNext(cause);
        assertSame(cause, assertThrows(CompletionException.class, failure::join).getCause());
    }

    @Test
    void refusesBlankModelKeysBeforeSending() {
        var publisher = publisher();
        for (String modelId : new String[]{null, "", " "}) {
            assertThrows(CompletionException.class, () -> publisher.publishOps(modelId, mapper.createObjectNode()).join());
        }
        assertTrue(producer.history().isEmpty());
    }
}
