package org.gautelis.archimesh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.service.KafkaPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class KafkaPublisherImpl implements KafkaPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaPublisherImpl.class);

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @ConfigProperty(name = "kafka.topic.prefix", defaultValue = "archimesh.model")
    String topicPrefix;

    @Inject
    ObjectMapper objectMapper;

    Producer<String, String> producer;

    @PostConstruct
    void init() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("acks", "all");
        properties.put("retries", "3");
        properties.put("delivery.timeout.ms", "120000");
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(properties);
        LOG.info("Kafka publisher ready at {}", bootstrapServers);
    }

    @PreDestroy
    void close() {
        if (producer != null) {
            producer.close();
            LOG.info("Kafka publisher closed");
        }
    }

    @Override
    public CompletableFuture<Void> publishOps(String modelId, JsonNode opBatch) {
        return publish(modelId, "ops", opBatch);
    }

    @Override
    public void publishLockEvent(String modelId, Object lockEvent) {
        publish(modelId, "locks", lockEvent);
    }

    @Override
    public void publishPresence(String modelId, Object presenceEvent) {
        publish(modelId, "presence", presenceEvent);
    }

    private CompletableFuture<Void> publish(String modelId, String kind, Object payload) {
        if (producer == null) {
            LOG.warn("Kafka producer unavailable; skipping {} publish for model {}", kind, modelId);
            return CompletableFuture.failedFuture(new IllegalStateException("Kafka producer unavailable"));
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            String topic = KafkaTopics.publishTopic(topicPrefix, modelId, kind);
            String value = objectMapper.writeValueAsString(payload);
            LOG.debug("Kafka publish queued: topic={} modelId={} kind={}", topic, modelId, kind);
            producer.send(new ProducerRecord<>(topic, modelId, value), (metadata, exception) -> {
                if (exception != null) {
                    LOG.warn("Kafka publish failed: topic={}", topic, exception);
                    future.completeExceptionally(exception);
                } else {
                    future.complete(null);
                }
            });
        } catch (Exception e) {
            LOG.warn("Kafka publish serialization failed: modelId={} kind={}", modelId, kind, e);
            future.completeExceptionally(e);
        }
        return future;
    }
}
