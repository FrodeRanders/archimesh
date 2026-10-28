package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.UUID;

@QuarkusTest
@TestProfile(CheckpointMaintenanceIT.Profile.class)
class CheckpointMaintenanceIT {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("app.compaction.retain-revisions","0","app.compaction.max-batches-per-run","1",
                    "app.checkpoints.maintenance.enabled","true","app.checkpoints.maintenance.interval-seconds","3600");
        }
    }

    @Inject ArchimeshService service;
    @Inject Neo4jRepository repository;
    private String model;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setUp() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")));
        model = "checkpoint-maintenance-"+UUID.randomUUID();
        service.registerModel(model,"Scheduled maintenance regression");
    }
    @AfterEach void cleanUp() { if (model != null) service.deleteModel(model,true); }

    private void submit(String id) throws Exception {
        var ops = json.createArrayNode().add(json.readTree("{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:"+id+"\",\"archimateType\":\"BusinessActor\"}}"));
        service.onSubmitOps(model,new SubmitOpsMessage(repository.readHeadRevision(model),id,null,ops));
        repository.markBroadcastPublished(model,id);
    }

    @Test void policyThresholdPruningResumeAndCheckpointRetentionWorkTogether() throws Exception {
        submit("one");
        var below = service.maintainCheckpoints(model,2,1);
        Assertions.assertFalse(below.checkpointCreated());
        Assertions.assertTrue(repository.latestCheckpoint(model).isEmpty());
        submit("two");
        var first = service.maintainCheckpoints(model,2,1);
        Assertions.assertTrue(first.checkpointCreated());
        Assertions.assertEquals(2,first.checkpointRevision());
        Assertions.assertEquals(1,first.prunedBatchCount(),"one batch per model pass");
        var resume = service.maintainCheckpoints(model,2,1);
        Assertions.assertFalse(resume.checkpointCreated());
        Assertions.assertEquals(1,resume.prunedBatchCount());
        Assertions.assertTrue(repository.loadOpBatches(model,1,2).isEmpty());
        submit("three"); submit("four");
        var newer = service.maintainCheckpoints(model,2,1);
        Assertions.assertTrue(newer.checkpointCreated());
        Assertions.assertEquals(4,newer.checkpointRevision());
        Assertions.assertEquals(1,newer.deletedCheckpointCount());
        service.maintainCheckpoints(model,2,1);
        var expected = repository.loadSnapshot(model);
        Assertions.assertEquals(4,service.rebuildMaterializedState(model).checkpointRevision());
        Assertions.assertEquals(expected,repository.loadSnapshot(model));
        Assertions.assertEquals(4,repository.loadReceipts(model,4).size());
    }
}
