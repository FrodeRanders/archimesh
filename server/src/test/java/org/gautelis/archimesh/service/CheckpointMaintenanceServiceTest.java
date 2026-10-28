package org.gautelis.archimesh.service;

import org.gautelis.archimesh.model.CheckpointMaintenanceResult;
import org.gautelis.archimesh.model.ModelCatalogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

class CheckpointMaintenanceServiceTest {
    private final List<String> attempts = new ArrayList<>();
    private final Lease lease = new Lease();

    private CheckpointMaintenanceService worker() {
        var worker = new CheckpointMaintenanceService();
        worker.enabled = true;
        worker.modelsPerRun = 2;
        worker.delivery = lease;
        worker.repository = (Neo4jRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Neo4jRepository.class},(proxy,method,args) -> {
                    if (!method.getName().equals("listModelCatalog")) throw new UnsupportedOperationException(method.getName());
                    return List.of(new ModelCatalogEntry("c","C",1),new ModelCatalogEntry("a","A",1),new ModelCatalogEntry("b","B",1));
                });
        worker.service = new ArchimeshService() {
            @Override
            public synchronized CheckpointMaintenanceResult maintainCheckpoints(String id,long threshold,int keep) {
                attempts.add(id);
                Assertions.assertEquals(1000,threshold);
                Assertions.assertEquals(2,keep);
                if (id.equals("a")) throw new IllegalStateException("damaged model");
                return new CheckpointMaintenanceResult(id,false,1,0,0,0);
            }
        };
        return worker;
    }

    @Test
    void disabledWorkerAndLostLeaseDoNotTouchModels() {
        var worker = worker();
        worker.enabled = false;
        worker.start(); worker.runOnce();
        Assertions.assertTrue(attempts.isEmpty());
        worker.enabled = true; lease.valid = false;
        worker.runOnce();
        Assertions.assertTrue(attempts.isEmpty());
        lease.valid = true;
        worker.runOnce();
        Assertions.assertEquals(List.of("a","b"),attempts,"lease failure must not cancel later passes");
    }

    @Test
    void boundedPassesRotateFairlyAndIsolateModelFailure() {
        var worker = worker();
        worker.runOnce(); worker.runOnce(); worker.runOnce();
        Assertions.assertEquals(List.of("a","b","c","a","b","c"),attempts);
    }

    @Test
    void leaseLossDuringPassStopsBeforeNextModel() {
        var worker = worker();
        worker.service = new ArchimeshService() {
            @Override
            public synchronized CheckpointMaintenanceResult maintainCheckpoints(String id,long threshold,int keep) {
                attempts.add(id); lease.valid = false;
                return new CheckpointMaintenanceResult(id,false,1,0,0,0);
            }
        };
        worker.runOnce();
        Assertions.assertEquals(List.of("a"),attempts);
        lease.valid = true; worker.runOnce();
        Assertions.assertEquals(List.of("a","b"),attempts);
    }

    @Test
    void invalidPolicyFailsBeforeStartingScheduler() {
        var worker = worker();
        worker.retainCheckpoints = 0;
        Assertions.assertThrows(IllegalArgumentException.class,worker::start);
        Assertions.assertTrue(attempts.isEmpty());
    }

    @jakarta.enterprise.inject.Vetoed
    private static class Lease extends CommitDeliveryService {
        boolean valid = true;
        @Override public void requireWriterLease() {
            if (!valid) throw new IllegalStateException("lease lost");
        }
    }
}
