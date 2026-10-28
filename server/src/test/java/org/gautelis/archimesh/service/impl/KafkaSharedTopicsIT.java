package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KafkaSharedTopicsIT {
    @Test
    void manyModelsShareFixedPartitionsWithOrderedAndIsolatedFanOut() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        String broker = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String prefix = "archimesh.shared-it-" + UUID.randomUUID();
        List<String> topics = KafkaTopics.subscription(prefix);
        int models = 160;
        var mapper = new ObjectMapper();
        var sessions = new KafkaConsumerImplTest.RecordingSessions();
        var fanOut = KafkaConsumerImplTest.consumer(sessions);
        fanOut.topicPrefix = prefix;

        try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker))) {
            try {
                // Multiple partitions exercise key affinity as well as bounded topic growth.
                admin.createTopics(topics.stream().map(t -> new NewTopic(t, 3, (short) 1)).toList())
                        .all().get(30, TimeUnit.SECONDS);
                var publisher = new KafkaPublisherImpl();
                publisher.bootstrapServers = broker;
                publisher.topicPrefix = prefix;
                publisher.objectMapper = mapper;
                publisher.init();
                try {
                    // Interleave 160 models, preserving send order only within each model.
                    for (int sequence = 0; sequence < 2; sequence++) {
                        for (int model = 0; model < models; model++) {
                            String id = "model." + model;
                            var batch = mapper.createObjectNode().put("modelId", id).put("sequence", sequence);
                            batch.putArray("ops");
                            publisher.publishOps(id, batch).get(30, TimeUnit.SECONDS);
                            if (sequence == 0) {
                                publisher.publishLockEvent(id, Map.of("targets", List.of()));
                                publisher.publishPresence(id, Map.of("viewId", "view-" + model));
                            }
                        }
                    }
                } finally {
                    publisher.close(); // Flush the asynchronous lock/presence sends too.
                }

                Map<String, Integer> partitionByTopicAndModel = new HashMap<>();
                Map<String, Integer> opsSeen = new HashMap<>();
                Map<String, Integer> locksSeen = new HashMap<>();
                Map<String, Integer> presenceSeen = new HashMap<>();
                var props = Map.<String, Object>of("bootstrap.servers", broker, "group.id", "shared-it-" + UUID.randomUUID(),
                        "auto.offset.reset", "earliest", "enable.auto.commit", false,
                        "key.deserializer", StringDeserializer.class.getName(), "value.deserializer", StringDeserializer.class.getName());
                try (var consumer = new KafkaConsumer<String, String>(props)) {
                    consumer.subscribe(topics);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (sessions.messages.size() < models * 4 && System.nanoTime() < deadline) {
                        for (var record : consumer.poll(Duration.ofMillis(250))) {
                            String id = record.key();
                            assertNotNull(id);
                            String kind = KafkaTopics.kind(prefix, record.topic());
                            Integer priorPartition = partitionByTopicAndModel.putIfAbsent(record.topic() + "/" + id, record.partition());
                            if (priorPartition != null) assertEquals(priorPartition.intValue(), record.partition());
                            switch (kind) {
                                case "ops" -> {
                                    var batch = mapper.readTree(record.value());
                                    assertEquals(id, batch.path("modelId").asText());
                                    assertEquals(opsSeen.getOrDefault(id, 0).intValue(), batch.path("sequence").asInt());
                                    opsSeen.merge(id, 1, Integer::sum);
                                }
                                case "locks" -> locksSeen.merge(id, 1, Integer::sum);
                                case "presence" -> presenceSeen.merge(id, 1, Integer::sum);
                                default -> fail("Unexpected topic " + record.topic());
                            }
                            int before = sessions.models.size();
                            fanOut.handleRecord(record.topic(), record.key(), record.value());
                            assertEquals(before + 1, sessions.models.size());
                            assertEquals(id, sessions.models.getLast(), "Fan-out must stay within the keyed model");
                        }
                    }
                }
                assertEquals(models * 4, sessions.messages.size());
                assertEquals(models, opsSeen.size());
                assertTrue(opsSeen.values().stream().allMatch(n -> n == 2));
                assertEquals(models, locksSeen.size());
                assertEquals(models, presenceSeen.size());
                assertTrue(locksSeen.values().stream().allMatch(n -> n == 1));
                assertTrue(presenceSeen.values().stream().allMatch(n -> n == 1));
                Set<String> matching = admin.listTopics().names().get(10, TimeUnit.SECONDS).stream()
                        .filter(t -> t.startsWith(prefix + ".")).collect(java.util.stream.Collectors.toSet());
                assertEquals(Set.copyOf(topics), matching, "Model count must not create additional topics");
                var descriptions = admin.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS);
                assertEquals(9, descriptions.values().stream().mapToInt(d -> d.partitions().size()).sum());
            } finally {
                // Delete only this test's topics, including after a partial failure.
                var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
                var owned = topics.stream().filter(existing::contains).toList();
                if (!owned.isEmpty()) admin.deleteTopics(owned).all().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
