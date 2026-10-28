package org.gautelis.archimesh.service.impl;

import org.gautelis.archimesh.model.RevisionRange;
import org.gautelis.archimesh.service.Neo4jRepository;
import org.gautelis.archimesh.service.RevisionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class InMemoryRevisionService implements RevisionService {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryRevisionService.class);
    private final ConcurrentHashMap<String, AtomicLong> heads = new ConcurrentHashMap<>();

    @Inject
    Neo4jRepository neo4jRepository;

    @Override
    public RevisionRange assignRange(String modelId, int opCount) {
        AtomicLong head = heads.computeIfAbsent(modelId, this::bootstrapHead);
        syncHeadFromRepository(modelId, head);
        if (opCount <= 0) {
            throw new IllegalArgumentException("opCount must be positive");
        }
        long to = head.addAndGet(opCount);
        long from = to - opCount + 1L;
        LOG.debug("Revision assigned: modelId={} opCount={} from={} to={}", modelId, opCount, from, to);
        return new RevisionRange(from, to);
    }

    @Override
    public long headRevision(String modelId) {
        AtomicLong head = heads.computeIfAbsent(modelId, this::bootstrapHead);
        syncHeadFromRepository(modelId, head);
        return head.get();
    }

    @Override
    public void clearModel(String modelId) {
        heads.remove(modelId);
        LOG.info("Revision head cleared for modelId={}", modelId);
    }

    private AtomicLong bootstrapHead(String modelId) {
        long persistedHead = 0L;
        if (neo4jRepository != null) {
            persistedHead = Math.max(0L, neo4jRepository.readHeadRevision(modelId));
        }
        LOG.debug("Revision bootstrap: modelId={} persistedHead={}", modelId, persistedHead);
        return new AtomicLong(persistedHead);
    }

    private void syncHeadFromRepository(String modelId, AtomicLong localHead) {
        if (neo4jRepository == null) {
            return;
        }
        long persistedHead = Math.max(0L, neo4jRepository.readHeadRevision(modelId));
        long current = localHead.get();
        if (persistedHead > current) {
            boolean updated = localHead.compareAndSet(current, persistedHead);
            if (updated) {
                LOG.info("Revision head advanced from persisted state: modelId={} localHead={} persistedHead={}",
                        modelId, current, persistedHead);
            }
        }
    }
}
