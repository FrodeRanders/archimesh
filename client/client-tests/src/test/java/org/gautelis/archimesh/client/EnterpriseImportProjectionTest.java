package org.gautelis.archimesh.client;

import com.archimatetool.model.*;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.gautelis.archimesh.plugin.emf.*;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseImportProjectionTest {
    @Test void acceptedImportIsOneRealElementInTheModelTreeAndCanAppearInTwoViews() throws Exception {
        var session=new ArchimeshSessionManager(); var model=IArchimateFactory.eINSTANCE.createArchimateModel();
        for (var type:new FolderType[]{FolderType.APPLICATION,FolderType.DIAGRAMS}) {
            var folder=IArchimateFactory.eINSTANCE.createFolder(); folder.setType(type); model.getFolders().add(folder);
        }
        var attached=ArchimeshSessionManager.class.getDeclaredField("attachedModel"); attached.setAccessible(true); attached.set(session,model);
        var unguarded=new AtomicBoolean();
        model.eAdapters().add(new EContentAdapter() {
            @Override public void notifyChanged(Notification notification) { super.notifyChanged(notification); if (!RemoteApplyGuard.isRemoteApply()) unguarded.set(true); }
        });
        var apply=new RemoteOpApplier(session);
        String imported="""
                {"payload":{"opBatch":{"assignedRevisionRange":{"from":1,"to":3},"ops":[
                  {"type":"CreateElement","element":{"id":"elem:central","archimateType":"ApplicationComponent","name":"Central app"},"causal":{"lamport":1,"clientId":"external"}},
                  {"type":"MoveElementToFolder","elementId":"elem:central","folderId":"folder:root-application","causal":{"lamport":2,"clientId":"external"}},
                  {"type":"SetProperty","targetId":"elem:central","key":"archimesh.external.canonicalId","value":"urn:uuid:central","causal":{"lamport":3,"clientId":"external"}}
                ]}}}
                """;
        invokeGuarded(apply,imported); invokeGuarded(apply,imported);
        var folder=model.getFolder(FolderType.APPLICATION); assertEquals(1,folder.getElements().size());
        var element=(IArchimateElement)folder.getElements().getFirst(); assertTrue(element instanceof IApplicationComponent);
        assertEquals("urn:uuid:central",element.getProperties().getFirst().getValue());
        invokeGuarded(apply,"""
                {"payload":{"opBatch":{"assignedRevisionRange":{"from":4,"to":7},"ops":[
                  {"type":"CreateView","view":{"id":"view:diagram-one","name":"First"}},
                  {"type":"CreateView","view":{"id":"view:diagram-two","name":"Second"}},
                  {"type":"CreateViewObject","viewObject":{"id":"vo:object-one","viewId":"view:diagram-one","representsId":"elem:central","notationJson":{"x":10,"y":20}}},
                  {"type":"CreateViewObject","viewObject":{"id":"vo:object-two","viewId":"view:diagram-two","representsId":"elem:central","notationJson":{"x":30,"y":40}}}
                ]}}}
                """);
        var first=(IArchimateDiagramModel)model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        var second=(IArchimateDiagramModel)model.getFolder(FolderType.DIAGRAMS).getElements().get(1);
        assertSame(element,((IDiagramModelArchimateObject)first.getChildren().getFirst()).getArchimateElement());
        assertSame(element,((IDiagramModelArchimateObject)second.getChildren().getFirst()).getArchimateElement());
        invokeGuarded(apply,"""
                {"payload":{"opBatch":{"assignedRevisionRange":{"from":8,"to":8},"ops":[
                  {"type":"UpdateElement","elementId":"elem:central","patch":{"name":"Updated central app"},"causal":{"lamport":100,"clientId":"external-sync:fixture"}}
                ]}}}
                """);
        assertEquals("Updated central app",element.getName());
        assertEquals("Updated central app",((IDiagramModelArchimateObject)first.getChildren().getFirst()).getArchimateElement().getName());
        assertEquals("Updated central app",((IDiagramModelArchimateObject)second.getChildren().getFirst()).getArchimateElement().getName());
        assertEquals(1,folder.getElements().size()); assertFalse(unguarded.get());
    }
    @Test void remotelyImportedElementCanBePlacedInTwoViewsWithoutRecreatingOrEditingIt() throws Exception {
        var model=IArchimateFactory.eINSTANCE.createArchimateModel(); model.setDefaults();
        var submissions=new java.util.ArrayList<String>();
        var session=new ArchimeshSessionManager() {
            @Override public IArchimateModel getAttachedModel() { return model; }
            @Override public String getCurrentModelId() { return "demo"; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendSubmitOps(String json) { submissions.add(json); }
        };
        var capture=new EmfChangeCapture(session); model.eAdapters().add(capture);
        var applier=new RemoteOpApplier(session);
        try {
            invokeGuarded(applier,"""
                {"payload":{"opBatch":{"assignedRevisionRange":{"from":1,"to":3},"ops":[
                {"type":"CreateElement","element":{"id":"elem:imported","archimateType":"ApplicationComponent","name":"Find stuck orders"}},
                {"type":"MoveElementToFolder","elementId":"elem:imported","folderId":"folder:root-application"},
                {"type":"SetProperty","targetId":"elem:imported","key":"archimesh.external.canonicalId","value":"urn:uuid:imported"}
                ]}}}
                """);
            assertTrue(submissions.isEmpty());
            var element=(IArchimateElement)model.getFolder(FolderType.APPLICATION).getElements().getFirst();
            for(int i=0;i<2;i++) {
                var view=IArchimateFactory.eINSTANCE.createArchimateDiagramModel();view.setId("view-"+i);
                model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
                submissions.clear();
                var object=IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();object.setId("object-"+i);object.setArchimateElement(element);
                view.getChildren().add(object);
                assertEquals(1,submissions.size());
                String payload=SimpleJson.asJsonObject(SimpleJson.readRawField(submissions.getFirst(),"payload"));
                var ops=SimpleJson.readArrayObjectElements(payload,"ops");
                assertEquals(1,ops.size(),"Placing a committed object must not emit semantic writes: "+submissions);
                assertEquals("CreateViewObject",SimpleJson.readStringField(ops.getFirst(),"type"));
                assertEquals("elem:imported",SimpleJson.readStringField(SimpleJson.asJsonObject(SimpleJson.readRawField(ops.getFirst(),"viewObject")),"representsId"));
                assertSame(element,object.getArchimateElement());
            }
            assertEquals(1,model.getFolder(FolderType.APPLICATION).getElements().size());
        } finally { model.eAdapters().remove(capture);capture.close(); }
    }

    private void invokeGuarded(RemoteOpApplier apply,String envelope) throws Exception {
        Method method=RemoteOpApplier.class.getDeclaredMethod("applyOp",String.class); method.setAccessible(true);
        String payload=SimpleJson.asJsonObject(SimpleJson.readRawField(envelope,"payload"));
        String batch=SimpleJson.asJsonObject(SimpleJson.readRawField(payload,"opBatch"));
        // Exercise the semantic apply layer without starting SWT's native UI from the JUnit worker.
        RemoteApplyGuard.runAsRemoteApply(() -> {
            for (String op:SimpleJson.readArrayObjectElements(batch,"ops"))
                try { method.invoke(apply,op); } catch (Exception error) { throw new AssertionError(error); }
        });
    }
}
