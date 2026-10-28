package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.Session;
import org.gautelis.archimesh.model.AdminActiveSession;
import org.gautelis.archimesh.service.SessionRegistry;
import org.gautelis.archimesh.wire.ServerEnvelope;
import org.gautelis.archimesh.wire.outbound.OpsBroadcastMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerImplTest {
    private final RecordingSessions sessions = new RecordingSessions();
    private final KafkaConsumerImpl consumer = consumer(sessions);

    static KafkaConsumerImpl consumer(RecordingSessions sessions) {
        KafkaConsumerImpl consumer = new KafkaConsumerImpl();
        consumer.topicPrefix = "archimesh.model";
        consumer.objectMapper = new ObjectMapper();
        consumer.sessionRegistry = sessions;
        return consumer;
    }

    @Test
    void routesInterleavedModelsByKeyIncludingModelIdsWithDots() {
        consumer.handleRecord("archimesh.model.ops", "model.a", "{\"modelId\":\"model.a\",\"ops\":[]}");
        consumer.handleRecord("archimesh.model.ops", "model.b", "{\"modelId\":\"model.b\",\"ops\":[]}");
        consumer.handleRecord("archimesh.model.ops", "model.a", "{\"modelId\":\"model.a\",\"ops\":[]}");
        assertEquals(List.of("model.a", "model.b", "model.a"), sessions.models);
        assertTrue(sessions.messages.stream().allMatch(m -> m.type().equals("OpsBroadcast")));
        assertInstanceOf(OpsBroadcastMessage.class, sessions.messages.getFirst().payload());
    }

    @Test
    void routesLockAndPresencePayloadsWithoutModelId() {
        consumer.handleRecord("archimesh.model.locks", "a", "{\"targets\":[]}");
        consumer.handleRecord("archimesh.model.presence", "b", "{\"viewId\":\"v1\"}");
        assertEquals(List.of("a", "b"), sessions.models);
        assertEquals(List.of("LockEvent", "PresenceBroadcast"), sessions.messages.stream().map(ServerEnvelope::type).toList());
    }

    @Test
    void rejectsMissingKeysAndConflictingPayloadIdentity() {
        for (String key : new String[]{null, "", " "}) {
            consumer.handleRecord("archimesh.model.ops", key, "{\"modelId\":\"a\"}");
        }
        consumer.handleRecord("archimesh.model.ops", "a", "{\"modelId\":\"b\"}");
        consumer.handleRecord("archimesh.model.presence", "a", "{\"modelId\":\"b\"}");
        assertTrue(sessions.messages.isEmpty());
    }

    @Test
    void ignoresOldTopicsAndUnsupportedTopics() {
        for (String topic : new String[]{"archimesh.model.a.ops", "other.ops", "archimesh.model.unknown", null}) {
            consumer.handleRecord(topic, "a", "{}");
        }
        assertTrue(sessions.messages.isEmpty());
        assertEquals(List.of("archimesh.model.ops", "archimesh.model.locks", "archimesh.model.presence"),
                KafkaTopics.subscription("archimesh.model"));
    }

    @Test
    void malformedRecordsDoNotPreventFollowingDelivery() {
        for (String payload : new String[]{"broken", "null", "[]", "42"}) {
            consumer.handleRecord("archimesh.model.ops", "a", payload);
        }
        consumer.handleRecord("archimesh.model.ops", "b", "{\"ops\":[]}");
        assertEquals(List.of("b"), sessions.models);
    }

    static class RecordingSessions implements SessionRegistry {
        final List<String> models = new ArrayList<>();
        final List<ServerEnvelope> messages = new ArrayList<>();

        @Override public void broadcast(String modelId, ServerEnvelope message) { models.add(modelId); messages.add(message); }
        @Override public void register(String modelId, Session session) { }
        @Override public void unregister(String modelId, Session session) { }
        @Override public void send(Session session, ServerEnvelope message) { }
        @Override public int sessionCount(String modelId) { return 0; }
        @Override public Set<String> activeModelIds() { return Set.of(); }
        @Override public List<AdminActiveSession> activeSessions(String modelId) { return List.of(); }
    }
}
