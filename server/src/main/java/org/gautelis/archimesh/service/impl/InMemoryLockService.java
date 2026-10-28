package org.gautelis.archimesh.service.impl;

import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.model.LockState;
import org.gautelis.archimesh.service.LockService;
import org.gautelis.archimesh.wire.outbound.LockEventMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryLockService implements LockService {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryLockService.class);
    private final Map<String, LockState> locks = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<String> checkLockConflicts(String modelId, Actor actor, List<String> targets) {
        Instant now = Instant.now();
        for (String target : targets) {
            String key = lockKey(modelId, target);
            LockState lock = locks.get(key);
            if (lock == null) {
                continue;
            }
            if (lock.isExpired(now)) {
                locks.remove(key);
                continue;
            }
            if (!sameOwner(lock.owner(), actor)) {
                return Optional.of("target is locked: " + target);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized LockEventMessage acquire(String modelId, Actor actor, List<String> targets, long ttlMs) {
        Optional<String> conflict = checkLockConflicts(modelId, actor, targets);
        Instant now = Instant.now();
        if (conflict.isPresent()) {
            LOG.info("Lock denied: modelId={} actor={}/{} targetCount={} reason={}",
                    modelId, actor.userId(), actor.sessionId(), targets.size(), conflict.get());
            return new LockEventMessage(UUID.randomUUID().toString(), "LockDenied", actor, targets, now, ttlMs, conflict.get());
        }

        ttlMs = Math.max(1000L, Math.min(60_000L, ttlMs));
        Instant expiresAt = now.plusMillis(ttlMs);
        for (String target : targets) {
            locks.put(lockKey(modelId, target), new LockState(target, actor, ttlMs, expiresAt));
        }
        LOG.debug("Lock granted: modelId={} actor={}/{} targetCount={} ttlMs={}",
                modelId, actor.userId(), actor.sessionId(), targets.size(), ttlMs);

        return new LockEventMessage(UUID.randomUUID().toString(), "LockGranted", actor, targets, now, ttlMs, null);
    }

    @Override
    public synchronized LockEventMessage release(String modelId, Actor actor, List<String> targets) {
        for (String target : targets) {
            String key = lockKey(modelId, target);
            LockState existing = locks.get(key);
            if (existing != null && sameOwner(existing.owner(), actor)) {
                locks.remove(key);
            }
        }
        LOG.debug("Lock released: modelId={} actor={}/{} targetCount={}",
                modelId, actor.userId(), actor.sessionId(), targets.size());

        return new LockEventMessage(UUID.randomUUID().toString(), "LockReleased", actor, targets, Instant.now(), 0, null);
    }

    @Override
    public void clearModel(String modelId) {
        String prefix = modelId + "::";
        locks.keySet().removeIf(key -> key.startsWith(prefix));
        LOG.info("Locks cleared for modelId={}", modelId);
    }

    private String lockKey(String modelId, String target) {
        return modelId + "::" + target;
    }

    private boolean sameOwner(Actor a, Actor b) {
        return a != null && b != null && safeEquals(a.userId(), b.userId()) && safeEquals(a.sessionId(), b.sessionId());
    }

    private boolean safeEquals(String a, String b) {
        return Objects.equals(a, b);
    }
}
