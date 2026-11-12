package org.gautelis.archimesh.client;

import com.archimatetool.model.*;
import org.gautelis.archimesh.plugin.emf.EmfChangeCapture;
import org.gautelis.archimesh.plugin.emf.ModelProjectionReset;
import org.gautelis.archimesh.plugin.emf.RemoteApplyGuard;
import org.gautelis.archimesh.plugin.emf.RemoteOpApplier;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModelProjectionResetTest {
    @Test void repeatedDemoSnapshotsRestoreAllConceptsWithoutSubmittingLocalOperations() throws Exception {
        var model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        List<String> submissions = new ArrayList<>();
        var session = new ArchimeshSessionManager() {
            @Override public String getCurrentModelId() { return "northstar-order-management-demo-v1"; }
            @Override public IArchimateModel getAttachedModel() { return model; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendSubmitOps(String json) { submissions.add(json); }
        };
        var capture = new EmfChangeCapture(session);
        model.eAdapters().add(capture);
        var applier = new RemoteOpApplier(session);
        var apply = RemoteOpApplier.class.getDeclaredMethod("applySnapshotEnvelopeInternal",String.class);
        apply.setAccessible(true);
        Path fixture = Path.of("../../examples/order-management/models/archimesh-model.json");
        String snapshot = SimpleJson.readRawField(Files.readString(fixture), "snapshot");
        String envelope = "{\"type\":\"CheckoutSnapshot\",\"payload\":{\"headRevision\":176,\"snapshot\":" + snapshot + "}}";
        try {
            for(int round=0; round<2; round++) {
                RemoteApplyGuard.runAsRemoteApply(() -> {
                    try { apply.invoke(applier,envelope); }
                    catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                int elements=0, relationships=0;
                for(var iter=model.eAllContents(); iter.hasNext();) {
                    var object=iter.next();
                    if(object instanceof IArchimateElement) elements++;
                    if(object instanceof IArchimateRelationship relation) {
                        relationships++;
                        assertNotNull(relation.getSource()); assertNotNull(relation.getTarget());
                    }
                }
                assertEquals(26,elements); assertEquals(49,relationships);
                assertEquals(176,session.getLastKnownRevision());
                assertTrue(submissions.isEmpty(), "Receiving a snapshot must never publish model changes");
            }
        } finally {
            model.eAdapters().remove(capture); capture.close();
        }
    }

    @Test void resettingLocalProjectionNeverSubmitsDeletesOrEndpointEdits() {
        var model = IArchimateFactory.eINSTANCE.createArchimateModel();
        var application = IArchimateFactory.eINSTANCE.createFolder();
        application.setType(FolderType.APPLICATION); model.getFolders().add(application);
        var relations = IArchimateFactory.eINSTANCE.createFolder();
        relations.setType(FolderType.RELATIONS); model.getFolders().add(relations);
        var source = IArchimateFactory.eINSTANCE.createApplicationComponent(); source.setId("source");
        var target = IArchimateFactory.eINSTANCE.createApplicationComponent(); target.setId("target");
        application.getElements().add(source); application.getElements().add(target);
        var relation = IArchimateFactory.eINSTANCE.createAssociationRelationship(); relation.setId("relation");
        relation.setSource(source); relation.setTarget(target); relations.getElements().add(relation);
        List<String> submissions = new ArrayList<>();
        var session = new ArchimeshSessionManager() {
            @Override public String getCurrentModelId() { return "test-model"; }
            @Override public IArchimateModel getAttachedModel() { return model; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendSubmitOps(String json) { submissions.add(json); }
        };
        var capture = new EmfChangeCapture(session);
        model.eAdapters().add(capture);
        try {
            // This must be safe even when the caller has not entered RemoteApplyGuard.
            ModelProjectionReset.clear(model);
            assertTrue(application.getElements().isEmpty());
            assertTrue(relations.getElements().isEmpty());
            assertTrue(submissions.isEmpty(), "Projection reset must not alter the shared server model: " + submissions);
            // Protection must end when reset returns, so intentional local edits still work.
            var local = IArchimateFactory.eINSTANCE.createApplicationComponent(); local.setId("new-local");
            application.getElements().add(local);
            assertFalse(submissions.isEmpty(), "Local edit capture must remain enabled after reset");
        } finally {
            model.eAdapters().remove(capture); capture.close();
        }
    }
}
