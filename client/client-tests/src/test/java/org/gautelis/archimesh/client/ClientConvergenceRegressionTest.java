package org.gautelis.archimesh.client;

import com.archimatetool.model.*;
import org.gautelis.archimesh.plugin.emf.*;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.junit.jupiter.api.*;
import java.lang.reflect.Method;

class ClientConvergenceRegressionTest {
    private record Client(ArchimeshSessionManager session, RemoteOpApplier applier, IArchimateModel model) { }

    private Client client() throws Exception {
        var session = new ArchimeshSessionManager();
        var model = IArchimateFactory.eINSTANCE.createArchimateModel();
        var folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setType(FolderType.BUSINESS);
        model.getFolders().add(folder);
        var field = ArchimeshSessionManager.class.getDeclaredField("attachedModel");
        field.setAccessible(true); field.set(session, model);
        return new Client(session, new RemoteOpApplier(session), model);
    }

    private void invoke(RemoteOpApplier applier, String method, String json) throws Exception {
        Method target = RemoteOpApplier.class.getDeclaredMethod(method, String.class);
        target.setAccessible(true); target.invoke(applier, json);
    }

    private IArchimateElement element(Client client) {
        return (IArchimateElement) client.model.getFolder(FolderType.BUSINESS).getElements().getFirst();
    }

    private String update(String value, long clock) {
        return "{\"type\":\"UpdateElement\",\"elementId\":\"elem:e1\",\"patch\":{\"name\":\"" + value
                + "\"},\"causal\":{\"lamport\":" + clock + ",\"clientId\":\"" + value + "\"}}";
    }

    @Test
    void optimisticEditsAndTheirOwnEchoesConvergeOnBothClients() throws Exception {
        Client a = client(), b = client();
        String create = "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:e1\",\"archimateType\":\"BusinessActor\",\"name\":\"initial\"}}";
        invoke(a.applier, "applyOp", create); invoke(b.applier, "applyOp", create);
        element(a).setName("A"); element(b).setName("B");
        String opA = update("A", 100), opB = update("B", 200);
        a.applier.recordLocalOperations("{\"payload\":{\"ops\":[" + opA + "]}}");
        b.applier.recordLocalOperations("{\"payload\":{\"ops\":[" + opB + "]}}");
        for (Client client : new Client[]{a, b}) {
            invoke(client.applier, "applyOp", opA);
            invoke(client.applier, "applyOp", opB);
            Assertions.assertEquals("B", element(client).getName());
        }
    }

    @Test
    void snapshotRestoresPropertiesClocksAndDeleteProtection() throws Exception {
        Client client = client();
        String snapshot = """
            {"payload":{"headRevision":3,"snapshot":{
              "elements":[{"id":"elem:e1","archimateType":"BusinessActor","name":"current"}],
              "fieldClocks":[{"targetId":"elem:e1","field":"name","causal":{"lamport":50,"clientId":"server"}}],
              "propertyState":[{"type":"SetProperty","targetId":"elem:e1","key":"owner","value":"Alice","causal":{"lamport":40,"clientId":"server"}}],
              "tombstones":[{"targetId":"elem:deleted","causal":{"lamport":60,"clientId":"server"}}]
            }}}
            """;
        invoke(client.applier, "applySnapshotEnvelopeInternal", snapshot);
        invoke(client.applier, "applyOp", update("stale", 49));
        invoke(client.applier, "applyOp", "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:deleted\",\"archimateType\":\"BusinessActor\"},\"causal\":{\"lamport\":59}}");
        Assertions.assertEquals("current", element(client).getName());
        Assertions.assertEquals("Alice", element(client).getProperties().getFirst().getValue());
        Assertions.assertEquals(1, client.model.getFolder(FolderType.BUSINESS).getElements().size());
        Assertions.assertEquals(3, client.session.getLastKnownRevision());
    }

    @Test
    void outgoingClockAdvancesBeyondObservedRemoteClock() throws Exception {
        Client client = client();
        String create = "{\"type\":\"CreateElement\",\"element\":{\"id\":\"elem:e1\",\"archimateType\":\"BusinessActor\"},\"causal\":{\"lamport\":9000000000000}}";
        invoke(client.applier, "applyOp", create);
        String submit = new OpMapper().toUpdateElementSubmitOps(element(client), "test", 0, "u", "s", true, false);
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submit, "payload"));
        String op = SimpleJson.readArrayObjectElements(payload, "ops").getFirst();
        String causal = SimpleJson.asJsonObject(SimpleJson.readRawField(op, "causal"));
        Assertions.assertTrue(SimpleJson.readLongField(causal, "lamport") > 9000000000000L);
    }

    @Test
    void folderCreationSeedsItsRegisterAndRejectsOlderRename() throws Exception {
        Client client = client();
        invoke(client.applier, "applyOp", """
            {"type":"CreateFolder","folder":{"id":"folder:child","folderType":"USER","parentFolderId":"folder:root-business","name":"Newer"},"causal":{"lamport":200,"clientId":"server"}}
            """);
        invoke(client.applier, "applyOp", """
            {"type":"UpdateFolder","folderId":"folder:child","patch":{"name":"Stale"},"causal":{"lamport":100,"clientId":"server"}}
            """);
        Assertions.assertEquals("Newer", client.model.getFolder(FolderType.BUSINESS).getFolders().getFirst().getName());
    }
}
