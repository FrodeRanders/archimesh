package org.gautelis.archimesh.client;

import com.archimatetool.model.*;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.gautelis.archimesh.plugin.emf.*;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagramMutationSafetyTest {
    private final List<EmfChangeCapture> captures=new ArrayList<>();
    private record Scene(IArchimateModel model, IArchimateDiagramModel view,
                         IDiagramModelArchimateObject parent, IDiagramModelArchimateObject child,
                         IDiagramModelArchimateObject target, IDiagramModelArchimateConnection connection,
                         RemoteOpApplier applier, List<String> submissions, List<Runnable> deferred) { }

    private Scene scene() {
        var f=IArchimateFactory.eINSTANCE; var model=f.createArchimateModel(); model.setDefaults();
        var view=f.createArchimateDiagramModel(); view.setId("view");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        var objects=new ArrayList<IDiagramModelArchimateObject>();
        for(String id:List.of("parent","child","target")) {
            var element=f.createApplicationComponent(); element.setId("semantic-"+id);
            model.getFolder(FolderType.APPLICATION).getElements().add(element);
            var object=f.createDiagramModelArchimateObject(); object.setId(id); object.setArchimateElement(element);
            view.getChildren().add(object); objects.add(object);
        }
        var relation=f.createAssociationRelationship(); relation.setId("relationship");
        relation.setSource(objects.get(1).getArchimateElement()); relation.setTarget(objects.get(2).getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(relation);
        var connection=f.createDiagramModelArchimateConnection(); connection.setId("connection");
        connection.setArchimateRelationship(relation); connection.connect(objects.get(1),objects.get(2));
        var submissions=new ArrayList<String>(); var deferred=new ArrayList<Runnable>();
        var session=new ArchimeshSessionManager() {
            @Override public String getCurrentModelId() { return "demo"; }
            @Override public IArchimateModel getAttachedModel() { return model; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendSubmitOps(String json) { submissions.add(json); }
        };
        var capture=new EmfChangeCapture(session) {
            @Override protected void deferAfterLocalChange(Runnable task) { deferred.add(task); }
        };
        captures.add(capture); model.eAdapters().add(capture);
        return new Scene(model,view,objects.get(0),objects.get(1),objects.get(2),connection,new RemoteOpApplier(session),submissions,deferred);
    }

    @AfterEach void close() { captures.forEach(EmfChangeCapture::close); }

    private void apply(Scene scene,String op) throws Exception {
        var method=RemoteOpApplier.class.getDeclaredMethod("applyOp",String.class); method.setAccessible(true);
        RemoteApplyGuard.runAsRemoteApply(() -> {
            try { assertEquals(true,method.invoke(scene.applier,op)); }
            catch(ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    @Test void remoteDeletionDisconnectsFiguresWithoutNullingTheirLiveEndpoints() throws Exception {
        var scene=scene();
        scene.connection.eAdapters().add(new AdapterImpl() {
            @Override public void notifyChanged(Notification notification) {
                if((notification.getFeature()==IArchimatePackage.Literals.DIAGRAM_MODEL_CONNECTION__SOURCE
                        || notification.getFeature()==IArchimatePackage.Literals.DIAGRAM_MODEL_CONNECTION__TARGET)
                        && notification.getNewValue()==null) {
                    throw new NullPointerException("Simulated Archi figure refresh with a null endpoint");
                }
            }
        });
        apply(scene,"{\"type\":\"DeleteViewObject\",\"viewObjectId\":\"vo:child\"}");
        assertFalse(scene.view.getChildren().contains(scene.child));
        assertTrue(scene.target.getTargetConnections().isEmpty());
        assertTrue(scene.child.getSourceConnections().isEmpty());
        assertSame(scene.child,scene.connection.getSource());
        assertSame(scene.target,scene.connection.getTarget());
        assertSame(scene.model,scene.child.getArchimateElement().getArchimateModel());
        assertTrue(scene.submissions.isEmpty());
    }

    @Test void nestingAndUnnestingAreMembershipChangesWithoutDeletingTheObject() {
        var scene=scene();
        scene.parent.getChildren().add(scene.child);
        List.copyOf(scene.deferred).forEach(Runnable::run); scene.deferred.clear();
        assertTrue(scene.submissions.stream().anyMatch(json -> json.contains("AddViewObjectChildMember")));
        assertTrue(scene.submissions.stream().noneMatch(json -> json.contains("DeleteViewObject")));
        assertSame(scene.parent,scene.child.eContainer());
        assertSame(scene.model,scene.child.getArchimateElement().getArchimateModel());
        scene.submissions.clear(); scene.view.getChildren().add(scene.child);
        List.copyOf(scene.deferred).forEach(Runnable::run); scene.deferred.clear();
        assertTrue(scene.submissions.stream().anyMatch(json -> json.contains("RemoveViewObjectChildMember")));
        assertTrue(scene.submissions.stream().noneMatch(json -> json.contains("DeleteViewObject")));
        assertSame(scene.view,scene.child.eContainer());
        assertNotNull(scene.connection.getSource()); assertNotNull(scene.connection.getTarget());
    }

    @Test void actualRemovalOfANestedObjectStillPublishesItsDeletionOnce() {
        var scene=scene();
        RemoteApplyGuard.runAsRemoteApply(() -> scene.parent.getChildren().add(scene.child));
        scene.parent.getChildren().remove(scene.child);
        List.copyOf(scene.deferred).forEach(Runnable::run);
        assertEquals(1,scene.submissions.stream().filter(json -> json.contains("DeleteViewObject")).count());
        assertTrue(scene.submissions.stream().anyMatch(json -> json.contains("RemoveViewObjectChildMember")));
    }
}
