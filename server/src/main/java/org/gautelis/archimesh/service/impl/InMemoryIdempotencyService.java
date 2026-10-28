package org.gautelis.archimesh.service.impl;

import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.service.IdempotencyService;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryIdempotencyService implements IdempotencyService {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryIdempotencyService.class);
    private record BatchKey(String modelId, String opBatchId) { }
    private final Map<BatchKey, RevisionRange> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<RevisionRange> findRange(String modelId, String opBatchId) {
        Optional<RevisionRange> result = Optional.ofNullable(cache.get(key(modelId, opBatchId)));
        result.ifPresent(revisionRange -> LOG.debug("Idempotency hit: modelId={} opBatchId={} range={}..{}",
                modelId, opBatchId, revisionRange.from(), revisionRange.to()));
        return result;
    }

    @Override
    public void remember(String modelId, String opBatchId, RevisionRange range) {
        cache.putIfAbsent(key(modelId, opBatchId), range);
    }

    @Override
    public void clearModel(String modelId) {
        cache.keySet().removeIf(key -> key.modelId().equals(modelId));
        LOG.info("Idempotency cache cleared for modelId={}", modelId);
    }

    private BatchKey key(String modelId, String opBatchId) {
        return new BatchKey(modelId, opBatchId);
    }
}
