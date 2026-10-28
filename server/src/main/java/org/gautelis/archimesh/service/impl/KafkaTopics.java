package org.gautelis.archimesh.service.impl;

import java.util.List;

/** A fixed set of topics, with model identity carried by each record key. */
final class KafkaTopics {
    private KafkaTopics() {
    }

    static String publishTopic(String prefix, String modelId, String kind) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Kafka modelId must not be blank");
        }
        return prefix + "." + kind;
    }

    static List<String> subscription(String prefix) {
        return List.of(prefix + ".ops", prefix + ".locks", prefix + ".presence");
    }

    static String kind(String prefix, String topic) {
        String start = prefix + ".";
        if (topic == null || !topic.startsWith(start)) {
            return null;
        }
        String suffix = topic.substring(start.length());
        return switch (suffix) {
            case "ops", "locks", "presence" -> suffix;
            default -> null;
        };
    }
}
