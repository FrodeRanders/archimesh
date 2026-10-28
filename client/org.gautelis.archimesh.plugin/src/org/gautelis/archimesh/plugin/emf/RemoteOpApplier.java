package org.gautelis.archimesh.plugin.emf;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.notation.NotationDeserializer;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IFolderContainer;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies remote operations into the local EMF model.
 * Dependency-ordered ops are retried from a bounded deferred queue.
 */
public class RemoteOpApplier {
    private static final long MAX_DEFERRED_AGE_MILLIS = TimeUnit.MINUTES.toMillis(3);
    private static final int MAX_DEFERRED_QUEUE_SIZE = 512;
    private static final int DEFERRED_RETRY_INTERVAL_MS = 200;

    private record DeferredOp(String opJson, long firstSeenAtMillis, int attempts) {}

    private final ArchimeshSessionManager sessionManager;
    private final NotationDeserializer notationDeserializer = new NotationDeserializer();
    private final List<DeferredOp> deferredOps = new ArrayList<>();
    private final Map<String, FieldClock> elementFieldClocks = new HashMap<>();
    private final Map<String, CausalClock> elementTombstones = new HashMap<>();
    private final Map<String, CausalClock> propertyClocks = new HashMap<>();
    private final Map<String, CausalClock> propertyTombstones = new HashMap<>();
    private final Map<String, PropertySetMemberClock> propertySetMemberClocks = new HashMap<>();
    private final Map<String, ViewObjectChildMemberClock> viewObjectChildMemberClocks = new HashMap<>();
    private final Map<String, FieldClock> viewObjectNotationClocks = new HashMap<>();
    private final Map<String, FieldClock> connectionNotationClocks = new HashMap<>();
    private final Map<String, FieldClock> otherFieldClocks = new HashMap<>();
    private final Map<String, CausalClock> entityTombstones = new HashMap<>();
    private boolean deferredRetryScheduled;

    public void recordLocalOperations(String envelope) {
        Runnable record = () -> RemoteApplyGuard.runAsRemoteApply(() -> recordLocalOperationsInternal(envelope));
        if (ArchimeshPlugin.getInstance() == null || Display.getCurrent() != null) record.run();
        else Display.getDefault().asyncExec(record);
    }

    private void recordLocalOperationsInternal(String envelope) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelope, "payload"));
        for (String op : SimpleJson.readArrayObjectElements(payload, "ops")) {
            String type = SimpleJson.readStringField(op, "type");
            CausalClock clock = parseCausal(op);
            if (type != null && type.startsWith("Create")) {
                String field = switch (type) {
                    case "CreateElement" -> "element";
                    case "CreateRelationship" -> "relationship";
                    case "CreateFolder" -> "folder";
                    case "CreateView" -> "view";
                    case "CreateViewObject" -> "viewObject";
                    case "CreateConnection" -> "connection";
                    default -> null;
                };
                String value = SimpleJson.asJsonObject(SimpleJson.readRawField(op, field));
                String id = SimpleJson.readStringField(value, "id");
                if (id == null) continue;
                if (findPrefixedObject(id) == null) applyOp(op);
                if ("CreateElement".equals(type)) seedElementClock(stripPrefix(id, "elem:"), value, clock);
                else if ("CreateViewObject".equals(type)) seedGeometryClock(stripPrefix(id, "vo:"), SimpleJson.asJsonObject(SimpleJson.readRawField(value, "notationJson")), op);
                else if ("CreateConnection".equals(type)) seedConnectionNotationClock(stripPrefix(id, "conn:"), SimpleJson.asJsonObject(SimpleJson.readRawField(value, "notationJson")), op);
                else for (String key : new String[]{"name", "documentation", "source", "target", "parent", "notation"})
                    otherFieldClocks.computeIfAbsent(id, ignored -> new FieldClock()).set(key, clock);
            } else applyOp(op);
        }
    }


    public RemoteOpApplier(ArchimeshSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Applies a batch of remote operations (OpsBroadcast/CheckoutDelta) into the local EMF model,
     * deferring and retrying ops whose dependencies are not yet present.
     */
    public void applyOpsEnvelope(String envelopeJson) {
        Display display = Display.getDefault();
        Runnable task = () -> RemoteApplyGuard.runAsRemoteApply(() -> applyOpsEnvelopeInternal(envelopeJson));
        if(display != null && !display.isDisposed()) {
            display.asyncExec(task);
        }
        else {
            task.run();
        }
    }

    public void applyCheckoutDeltaEnvelope(String envelope) {
        Runnable task = () -> RemoteApplyGuard.runAsRemoteApply(() -> {
            String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelope, "payload"));
            for (String batch : SimpleJson.readArrayObjectElements(payload, "opBatches")) {
                applyOpsEnvelopeInternal("{\"payload\":{\"opBatch\":" + batch + "}}");
            }
            Long to = SimpleJson.readLongField(payload, "toRevision");
            if (to != null && to == sessionManager.getLastKnownRevision() && deferredOps.isEmpty()) {
                sessionManager.completeCheckout();
            } else sessionManager.sendJoin(null);
        });
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) display.asyncExec(task);
        else task.run();
    }

    /**
     * Rebuilds the entire local EMF model from a CheckoutSnapshot envelope, clearing existing
     * contents and restoring all model-tree entities, folder memberships, and diagram objects.
     */
    public void applySnapshotEnvelope(String envelopeJson) {
        Display display = Display.getDefault();
        Runnable task = () -> RemoteApplyGuard.runAsRemoteApply(() -> applySnapshotEnvelopeInternal(envelopeJson));
        if(display != null && !display.isDisposed()) {
            display.asyncExec(task);
        }
        else {
            task.run();
        }
    }

    private void applySnapshotEnvelopeInternal(String envelopeJson) {
        if(envelopeJson == null || envelopeJson.isBlank()) {
            return;
        }

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            ArchimeshPlugin.logInfo("CheckoutSnapshot missing payload");
            return;
        }

        String snapshot = SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "snapshot"));
        if(snapshot == null) {
            snapshot = payload;
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            ArchimeshPlugin.logInfo("CheckoutSnapshot ignored: no active model attached");
            return;
        }

        clearDeferredOps();
        clearModelContents(model);

        int applied = 0;
        // Restore the model tree first so later membership and diagram ops can resolve stable containers.
        applied += applySnapshotArray(snapshot, "folders", "CreateFolder", "folder");
        applied += applySnapshotArray(snapshot, "elements", "CreateElement", "element");
        applied += applySnapshotArray(snapshot, "relationships", "CreateRelationship", "relationship");
        applied += applySnapshotArray(snapshot, "views", "CreateView", "view");
        applied += applySnapshotFolderMembers(snapshot, "elementFolderMembers", "MoveElementToFolder", "elementId");
        applied += applySnapshotFolderMembers(snapshot, "relationshipFolderMembers", "MoveRelationshipToFolder", "relationshipId");
        applied += applySnapshotFolderMembers(snapshot, "viewFolderMembers", "MoveViewToFolder", "viewId");
        applied += applySnapshotArray(snapshot, "viewObjects", "CreateViewObject", "viewObject");
        applied += applySnapshotViewObjectChildMembers(snapshot);
        applied += applySnapshotArray(snapshot, "connections", "CreateConnection", "connection");

        restoreMergeState(snapshot);
        sessionManager.reapplyQueuedOperations();
        Long head = SimpleJson.readLongField(payload, "headRevision");
        if (head == null) head = SimpleJson.readLongField(snapshot, "headRevision");
        if (head != null) sessionManager.setLastKnownRevision(head);
        sessionManager.completeCheckout();
        ArchimeshPlugin.logInfo("Applied CheckoutSnapshot operations count=" + applied);
    }

    private void applyOpsEnvelopeInternal(String envelopeJson) {
        if(envelopeJson == null || envelopeJson.isBlank()) {
            return;
        }

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
        if(opBatch == null) {
            // Compatibility: some envelopes send the op-batch directly as payload.
            opBatch = asOpBatch(payload);
        }
        if(opBatch == null) {
            opBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "opBatch"));
        }
        if(opBatch == null) {
            opBatch = asOpBatch(envelopeJson);
        }
        if(opBatch == null) {
            ArchimeshPlugin.logInfo("OpsBroadcast missing opBatch payload");
            return;
        }

        String range = SimpleJson.asJsonObject(SimpleJson.readRawField(opBatch, "assignedRevisionRange"));
        Long from = SimpleJson.readLongField(range, "from");
        Long to = SimpleJson.readLongField(range, "to");
        if (to != null && to <= sessionManager.getLastKnownRevision()) return;
        if (from != null && from != sessionManager.getLastKnownRevision() + 1) {
            sessionManager.sendJoin(sessionManager.getLastKnownRevision());
            return;
        }

        List<String> ops = SimpleJson.readArrayObjectElements(opBatch, "ops");
        if(ops.isEmpty()) {
            return;
        }

        int applied = 0;
        for(String op : ops) {
            boolean ok = applyOp(op);
            if(ok) {
                applied++;
            }
            else {
                // Some failures are temporary dependency gaps (out-of-order delivery); defer only those.
                if(shouldDeferAfterFailure(op)) {
                    deferOp(op);
                }
                else {
                    ArchimeshPlugin.logTrace("Remote op ignored/failed: " + summarizeOp(op));
                }
            }
        }
        applied += retryDeferredOps();
        if (to != null && deferredOps.isEmpty()) sessionManager.setLastKnownRevision(to);
        scheduleDeferredRetryIfNeeded();
        if(applied > 0) {
            ArchimeshPlugin.logInfo("Applied " + applied + " remote operation(s)");
        }
    }

    private boolean shouldDeferAfterFailure(String opJson) {
        String type = SimpleJson.readStringField(opJson, "type");
        if(type == null) {
            return false;
        }
        // Only retry failures that are plausibly caused by out-of-order delivery; everything else should fail fast.
        return switch(type) {
            case "CreateFolder" -> shouldDeferCreateFolder(opJson);
            case "MoveFolder" -> shouldDeferMoveFolder(opJson);
            case "MoveElementToFolder", "MoveRelationshipToFolder", "MoveViewToFolder" -> shouldDeferFolderMemberMove(opJson);
            case "CreateViewObject" -> shouldDeferCreateViewObject(opJson);
            case "CreateConnection" -> shouldDeferCreateConnection(opJson);
            case "CreateRelationship" -> shouldDeferCreateRelationship(opJson);
            case "UpdateRelationship" -> shouldDeferUpdateRelationship(opJson);
            case "UpdateViewObjectOpaque" -> shouldDeferViewObjectOpaque(opJson);
            case "UpdateConnectionOpaque" -> shouldDeferConnectionOpaque(opJson);
            case "AddViewObjectChildMember", "RemoveViewObjectChildMember" -> shouldDeferViewObjectChildMember(opJson);
            default -> false;
        };
    }

    private boolean shouldDeferCreateViewObject(String opJson) {
        String viewObjectJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "viewObject"));
        if(viewObjectJson == null) {
            return false;
        }
        String viewObjectId = stripPrefix(SimpleJson.readStringField(viewObjectJson, "id"), "vo:");
        if(viewObjectId == null || findObjectById(viewObjectId) != null) {
            return false;
        }
        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(viewObjectJson, "viewId"), false);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        return view == null || !(representsEObject instanceof IArchimateElement);
    }

    private boolean shouldDeferCreateConnection(String opJson) {
        String connectionJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "connection"));
        if(connectionJson == null) {
            return false;
        }
        String connectionId = stripPrefix(SimpleJson.readStringField(connectionJson, "id"), "conn:");
        if(connectionId == null || findObjectById(connectionId) != null) {
            return false;
        }
        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(connectionJson, "viewId"), false);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        return view == null
                || !(representsEObject instanceof IArchimateRelationship)
                || !(sourceEObject instanceof IConnectable)
                || !(targetEObject instanceof IConnectable);
    }

    private boolean shouldDeferCreateRelationship(String opJson) {
        String relationshipJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "relationship"));
        if(relationshipJson == null) {
            return false;
        }

        String relationshipId = stripPrefix(SimpleJson.readStringField(relationshipJson, "id"), "rel:");
        if(relationshipId == null || findObjectById(relationshipId) != null) {
            return false;
        }

        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "sourceId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "targetId"));
        return !(sourceEObject instanceof IArchimateConcept) || !(targetEObject instanceof IArchimateConcept);
    }

    private boolean shouldDeferUpdateRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        EObject relationshipEObject = relationshipId == null ? null : findObjectById(relationshipId);
        if(!(relationshipEObject instanceof IArchimateRelationship)) {
            return relationshipId != null;
        }

        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        if(patchJson == null) {
            return false;
        }

        if(SimpleJson.hasField(patchJson, "sourceId")) {
            EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(patchJson, "sourceId"));
            if(!(sourceEObject instanceof IArchimateConcept)) {
                return true;
            }
        }
        if(SimpleJson.hasField(patchJson, "targetId")) {
            EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(patchJson, "targetId"));
            if(!(targetEObject instanceof IArchimateConcept)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldDeferViewObjectOpaque(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        return viewObjectId != null && findObjectById(viewObjectId) == null;
    }

    private boolean shouldDeferConnectionOpaque(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        return connectionId != null && findObjectById(connectionId) == null;
    }

    private boolean shouldDeferViewObjectChildMember(String opJson) {
        String parentId = stripPrefix(SimpleJson.readStringField(opJson, "parentViewObjectId"), "vo:");
        String childId = stripPrefix(SimpleJson.readStringField(opJson, "childViewObjectId"), "vo:");
        return (parentId != null && findObjectById(parentId) == null)
                || (childId != null && findObjectById(childId) == null);
    }

    private boolean shouldDeferCreateFolder(String opJson) {
        String folderJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "folder"));
        if(folderJson == null) {
            return false;
        }
        String parentFolderId = SimpleJson.readStringField(folderJson, "parentFolderId");
        // Nested folders may arrive before their parent during replay, so creation can be retried safely.
        return parentFolderId != null && resolveFolder(parentFolderId) == null;
    }

    private boolean shouldDeferMoveFolder(String opJson) {
        String folderId = SimpleJson.readStringField(opJson, "folderId");
        String parentFolderId = SimpleJson.readStringField(opJson, "parentFolderId");
        return (folderId != null && resolveFolder(folderId) == null)
                || (parentFolderId != null && resolveFolder(parentFolderId) == null);
    }

    private boolean shouldDeferFolderMemberMove(String opJson) {
        String type = SimpleJson.readStringField(opJson, "type");
        String targetField = switch(type) {
            case "MoveElementToFolder" -> "elementId";
            case "MoveRelationshipToFolder" -> "relationshipId";
            case "MoveViewToFolder" -> "viewId";
            default -> null;
        };
        if(targetField == null) {
            return false;
        }
        return findPrefixedObject(SimpleJson.readStringField(opJson, targetField)) == null
                || resolveFolder(SimpleJson.readStringField(opJson, "folderId")) == null;
    }

    private synchronized void deferOp(String opJson) {
        for(int i = 0; i < deferredOps.size(); i++) {
            DeferredOp deferred = deferredOps.get(i);
            if(deferred.opJson.equals(opJson)) {
                deferredOps.set(i, new DeferredOp(opJson, deferred.firstSeenAtMillis, deferred.attempts + 1));
                scheduleDeferredRetryIfNeeded();
                return;
            }
        }
        if(deferredOps.size() >= MAX_DEFERRED_QUEUE_SIZE) {
            // Keep retries bounded under persistent unresolved dependencies
            DeferredOp dropped = deferredOps.remove(0);
            ArchimeshPlugin.logTrace("Deferred queue full, dropping oldest remote op: " + summarizeOp(dropped.opJson));
        }
        deferredOps.add(new DeferredOp(opJson, System.currentTimeMillis(), 1));
        ArchimeshPlugin.logTrace("Deferred remote op: " + summarizeOp(opJson));
        scheduleDeferredRetryIfNeeded();
    }

    private synchronized void clearDeferredOps() {
        deferredOps.clear();
    }

    private synchronized int retryDeferredOps() {
        if(deferredOps.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        int applied = 0;
        List<DeferredOp> nextPass = new ArrayList<>();
        for(DeferredOp deferred : deferredOps) {
            if(applyOp(deferred.opJson)) {
                applied++;
                continue;
            }
            long ageMillis = now - deferred.firstSeenAtMillis;
            if(ageMillis >= MAX_DEFERRED_AGE_MILLIS) {
                // Drop stale entries instead of retrying forever on malformed or missing prerequisites
                ArchimeshPlugin.logTrace("Deferred remote op dropped after timeout: "
                        + summarizeOp(deferred.opJson)
                        + " ageMs=" + ageMillis
                        + " attempts=" + deferred.attempts);
                continue;
            }
            nextPass.add(new DeferredOp(deferred.opJson, deferred.firstSeenAtMillis, deferred.attempts + 1));
        }
        deferredOps.clear();
        deferredOps.addAll(nextPass);
        return applied;
    }

    private void scheduleDeferredRetryIfNeeded() {
        Display display = Display.getDefault();
        if(display == null || display.isDisposed()) {
            return;
        }
        synchronized(this) {
            if(deferredOps.isEmpty() || deferredRetryScheduled) {
                return;
            }
            deferredRetryScheduled = true;
        }
        display.timerExec(DEFERRED_RETRY_INTERVAL_MS, () -> {
            synchronized(RemoteOpApplier.this) {
                deferredRetryScheduled = false;
            }
            if(display.isDisposed()) {
                return;
            }
            RemoteApplyGuard.runAsRemoteApply(() -> {
                int applied = retryDeferredOps();
                if(applied > 0) {
                    ArchimeshPlugin.logInfo("Applied " + applied + " deferred remote operation(s)");
                }
                scheduleDeferredRetryIfNeeded();
            });
        });
    }

    private String asOpBatch(String jsonObject) {
        if(jsonObject == null) {
            return null;
        }
        if(SimpleJson.readRawField(jsonObject, "ops") == null) {
            return null;
        }
        if(SimpleJson.readRawField(jsonObject, "opBatchId") == null
                && SimpleJson.readRawField(jsonObject, "assignedRevisionRange") == null) {
            return null;
        }
        return jsonObject;
    }

    private void syncRevisionHint(String opBatchJson) {
        String assignedRange = SimpleJson.asJsonObject(SimpleJson.readRawField(opBatchJson, "assignedRevisionRange"));
        if(assignedRange == null) {
            return;
        }
        Long to = SimpleJson.readLongField(assignedRange, "to");
        if(to != null) {
            // This revision hint is used by local submit rebasing
            sessionManager.setLastKnownRevision(to);
        }
    }

    private boolean applyOp(String opJson) {
        String type = SimpleJson.readStringField(opJson, "type");
        if(type == null) {
            return false;
        }

        parseCausal(opJson); // Every received operation advances the outgoing logical clock.
        String idKey = switch (type) {
            case "CreateRelationship", "UpdateRelationship", "DeleteRelationship" -> "relationship";
            case "CreateView", "UpdateView", "DeleteView" -> "view";
            case "CreateViewObject", "UpdateViewObjectOpaque", "DeleteViewObject" -> "viewObject";
            case "CreateConnection", "UpdateConnectionOpaque", "DeleteConnection" -> "connection";
            default -> null;
        };
        if (idKey != null) {
            String id = type.startsWith("Create")
                    ? SimpleJson.readStringField(SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, idKey)), "id")
                    : SimpleJson.readStringField(opJson, idKey + "Id");
            if (id != null) {
                CausalClock incoming = parseCausal(opJson);
                CausalClock tombstone = entityTombstones.get(id);
                if (!type.startsWith("Delete") && tombstone != null && !wins(incoming, tombstone)) return false;
                if (type.startsWith("Delete") && (tombstone == null || wins(incoming, tombstone))) entityTombstones.put(id, incoming);
                if (type.startsWith("Create")) entityTombstones.remove(id);
            }
        }

        // Unknown types are ignored so older clients tolerate newer server op domains.
        return switch(type) {
            case "CreateFolder" -> applyCreateFolder(opJson);
            case "UpdateFolder" -> applyUpdateFolder(opJson);
            case "DeleteFolder" -> applyDeleteFolder(opJson);
            case "MoveFolder" -> applyMoveFolder(opJson);
            case "MoveElementToFolder" -> applyMoveElementToFolder(opJson);
            case "MoveRelationshipToFolder" -> applyMoveRelationshipToFolder(opJson);
            case "MoveViewToFolder" -> applyMoveViewToFolder(opJson);
            case "CreateElement" -> applyCreateElement(opJson);
            case "UpdateElement" -> applyUpdateElement(opJson);
            case "DeleteElement" -> applyDeleteElement(opJson);
            case "CreateRelationship" -> applyCreateRelationship(opJson);
            case "UpdateRelationship" -> applyUpdateRelationship(opJson);
            case "DeleteRelationship" -> applyDeleteRelationship(opJson);
            case "CreateView" -> applyCreateView(opJson);
            case "UpdateView" -> applyUpdateView(opJson);
            case "DeleteView" -> applyDeleteView(opJson);
            case "CreateViewObject" -> applyCreateViewObject(opJson);
            case "DeleteViewObject" -> applyDeleteViewObject(opJson);
            case "CreateConnection" -> applyCreateConnection(opJson);
            case "DeleteConnection" -> applyDeleteConnection(opJson);
            case "SetProperty" -> applySetProperty(opJson);
            case "UnsetProperty" -> applyUnsetProperty(opJson);
            case "AddPropertySetMember" -> applyAddPropertySetMember(opJson);
            case "RemovePropertySetMember" -> applyRemovePropertySetMember(opJson);
            case "AddViewObjectChildMember" -> applyAddViewObjectChildMember(opJson);
            case "RemoveViewObjectChildMember" -> applyRemoveViewObjectChildMember(opJson);
            case "UpdateViewObjectOpaque" -> applyViewObjectOpaque(opJson);
            case "UpdateConnectionOpaque" -> applyConnectionOpaque(opJson);
            default -> false;
        };
    }

    private boolean applyCreateFolder(String opJson) {
        String folderJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "folder"));
        if(folderJson == null) {
            return false;
        }
        String folderId = SimpleJson.readStringField(folderJson, "id");
        String folderType = SimpleJson.readStringField(folderJson, "folderType");
        String parentFolderId = SimpleJson.readStringField(folderJson, "parentFolderId");
        if(folderId == null || folderId.isBlank()) {
            return false;
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }

        IFolder existing = resolveFolder(folderId);
        if(existing != null) {
            applyFolderFields(existing, folderJson);
            if(!isRootFolderId(folderId)) {
                moveFolder(existing, parentFolderId);
            }
            return true;
        }

        if(isRootFolderId(folderId)) {
            IFolder rootFolder = resolveRootFolder(model, folderId);
            if(rootFolder == null) {
                return false;
            }
            applyFolderFields(rootFolder, folderJson);
            return true;
        }

        IFolder parent = resolveFolder(parentFolderId);
        if(parent == null) {
            return false;
        }
        IFolder created = IArchimateFactory.eINSTANCE.createFolder();
        created.setId(stripPrefix(folderId, "folder:"));
        FolderType type = parseFolderType(folderType);
        created.setType(type == null ? FolderType.USER : type);
        applyFolderFields(created, folderJson);
        parent.getFolders().add(created);
        seedOtherFields(folderId, opJson);
        return true;
    }

    private boolean applyUpdateFolder(String opJson) {
        String folderId = SimpleJson.readStringField(opJson, "folderId");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        IFolder folder = resolveFolder(folderId);
        if(folder == null || patchJson == null) {
            return false;
        }
        if (SimpleJson.hasField(patchJson, "name") && acceptField(folderId, "name", opJson)) setIfPresentName(folder, patchJson, "name");
        if (SimpleJson.hasField(patchJson, "documentation") && acceptField(folderId, "documentation", opJson)) setIfPresentDocumentation(folder, patchJson, "documentation");
        return true;
    }

    private boolean applyDeleteFolder(String opJson) {
        String folderId = SimpleJson.readStringField(opJson, "folderId");
        if(folderId == null || folderId.isBlank() || isRootFolderId(folderId)) {
            return false;
        }
        IFolder folder = resolveFolder(folderId);
        if(folder == null) {
            return true;
        }
        if(!folder.getFolders().isEmpty() || !folder.getElements().isEmpty()) {
            return false;
        }
        EcoreUtil.delete(folder, true);
        return true;
    }

    private boolean applyMoveFolder(String opJson) {
        String folderId = SimpleJson.readStringField(opJson, "folderId");
        String parentFolderId = SimpleJson.readStringField(opJson, "parentFolderId");
        if(folderId == null || folderId.isBlank() || isRootFolderId(folderId)) {
            return false;
        }
        IFolder folder = resolveFolder(folderId);
        if(folder == null) {
            return false;
        }
        return acceptField(folderId, "parent", opJson) && moveFolder(folder, parentFolderId);
    }

    private boolean applyMoveElementToFolder(String opJson) {
        EObject object = findPrefixedObject(SimpleJson.readStringField(opJson, "elementId"));
        if(!(object instanceof IArchimateElement element)) {
            return false;
        }
        return moveFolderMember(element, SimpleJson.readStringField(opJson, "folderId"));
    }

    private boolean applyMoveRelationshipToFolder(String opJson) {
        EObject object = findPrefixedObject(SimpleJson.readStringField(opJson, "relationshipId"));
        if(!(object instanceof IArchimateRelationship relationship)) {
            return false;
        }
        return moveFolderMember(relationship, SimpleJson.readStringField(opJson, "folderId"));
    }

    private boolean applyMoveViewToFolder(String opJson) {
        EObject object = findPrefixedObject(SimpleJson.readStringField(opJson, "viewId"));
        if(!(object instanceof IArchimateDiagramModel view)) {
            return false;
        }
        return moveFolderMember(view, SimpleJson.readStringField(opJson, "folderId"));
    }

    private boolean applyCreateElement(String opJson) {
        String elementJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "element"));
        if(elementJson == null) {
            return false;
        }

        String elementId = stripPrefix(SimpleJson.readStringField(elementJson, "id"), "elem:");
        CausalClock incoming = parseCausal(opJson);
        CausalClock tombstone = elementTombstones.get(elementId);
        if(!CrdtEntityMerge.shouldApplyCreate(toMergeClock(incoming), toMergeClock(tombstone))) {
            ArchimeshPlugin.logTrace("Ignored stale CreateElement id=elem:" + elementId
                    + " due to tombstone incomingLamport=" + incoming.lamport + " tombstoneLamport=" + tombstone.lamport);
            return false;
        }
        if(elementId == null || findObjectById(elementId) != null) {
            return false;
        }

        EObject created = createEObject(SimpleJson.readStringField(elementJson, "archimateType"));
        if(!(created instanceof IArchimateConcept concept)) {
            return false;
        }

        if(concept instanceof IIdentifier identifier) {
            identifier.setId(elementId);
        }
        if(concept instanceof INameable nameable) {
            setIfPresentName(nameable, elementJson, "name");
        }
        if(concept instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, elementJson, "documentation");
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(concept).getElements().add(concept);
        elementTombstones.remove(elementId);
        seedElementClock(elementId, elementJson, incoming);
        ArchimeshPlugin.logTrace("Applied CreateElement id=elem:" + elementId + " name=" + getName(concept));
        return true;
    }

    private boolean applyUpdateElement(String opJson) {
        String elementId = stripPrefix(SimpleJson.readStringField(opJson, "elementId"), "elem:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        CausalClock incoming = parseCausal(opJson);
        CausalClock tombstone = elementTombstones.get(elementId);
        if(!CrdtEntityMerge.shouldApplyUpdate(toMergeClock(incoming), toMergeClock(tombstone))) {
            ArchimeshPlugin.logTrace("Ignored stale UpdateElement id=elem:" + elementId
                    + " due to tombstone incomingLamport=" + incoming.lamport + " tombstoneLamport=" + tombstone.lamport);
            return false;
        }
        EObject eObject = findObjectById(elementId);
        if(!(eObject instanceof IArchimateConcept concept) || patchJson == null) {
            return false;
        }

        FieldClock clock = elementFieldClocks.computeIfAbsent(elementId, id -> new FieldClock());
        if(concept instanceof INameable nameable && SimpleJson.hasField(patchJson, "name")) {
            CausalClock existing = clock.get("name");
            if(wins(incoming, existing)) {
                setIfPresentName(nameable, patchJson, "name");
                clock.set("name", incoming);
            }
            else {
                ArchimeshPlugin.logTrace("Ignored stale element field name for id=elem:" + elementId
                        + " incomingLamport=" + incoming.lamport + " existingLamport=" + existing.lamport);
            }
        }
        if(concept instanceof IDocumentable documentable && SimpleJson.hasField(patchJson, "documentation")) {
            CausalClock existing = clock.get("documentation");
            if(wins(incoming, existing)) {
                setIfPresentDocumentation(documentable, patchJson, "documentation");
                clock.set("documentation", incoming);
            }
            else {
                ArchimeshPlugin.logTrace("Ignored stale element field documentation for id=elem:" + elementId
                        + " incomingLamport=" + incoming.lamport + " existingLamport=" + existing.lamport);
            }
        }
        ArchimeshPlugin.logTrace("Applied UpdateElement id=elem:" + elementId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteElement(String opJson) {
        String elementId = stripPrefix(SimpleJson.readStringField(opJson, "elementId"), "elem:");
        CausalClock incoming = parseCausal(opJson);
        CausalClock existingTombstone = elementTombstones.get(elementId);
        if(CrdtEntityMerge.shouldAdvanceTombstone(toMergeClock(incoming), toMergeClock(existingTombstone))) {
            elementTombstones.put(elementId, incoming);
        }
        elementFieldClocks.remove(elementId);
        EObject eObject = findObjectById(elementId);
        if(eObject == null) {
            ArchimeshPlugin.logTrace("Applied DeleteElement tombstone only id=elem:" + elementId);
            return true;
        }
        if(eObject instanceof IArchimateConcept concept) {
            // Delete all diagram components that reference this concept first.
            // This prevents dangling view objects/connections that later fail model validation on save.
            List<EObject> referencingComponents = new ArrayList<>();
            for(var component : concept.getReferencingDiagramComponents()) {
                if(component instanceof EObject eo) {
                    referencingComponents.add(eo);
                }
            }
            for(EObject referencing : referencingComponents) {
                EcoreUtil.delete(referencing, true);
            }

            // Defensive cleanup: ensure no relationship remains with a missing endpoint.
            List<IArchimateRelationship> relationships = new ArrayList<>(ArchimateModelUtils.getAllRelationshipsForConcept(concept));
            for(IArchimateRelationship relationship : relationships) {
                deleteRelationshipAndReferences(relationship);
            }
        }
        EcoreUtil.delete(eObject, true);
        ArchimeshPlugin.logTrace("Applied DeleteElement id=elem:" + elementId);
        return true;
    }

    private boolean applyCreateRelationship(String opJson) {
        String relationshipJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "relationship"));
        if(relationshipJson == null) {
            return false;
        }

        String relationshipId = stripPrefix(SimpleJson.readStringField(relationshipJson, "id"), "rel:");
        if(relationshipId == null || findObjectById(relationshipId) != null) {
            return false;
        }

        EObject created = createEObject(SimpleJson.readStringField(relationshipJson, "archimateType"));
        if(!(created instanceof IArchimateRelationship relationship)) {
            return false;
        }

        if(relationship instanceof IIdentifier identifier) {
            identifier.setId(relationshipId);
        }

        if(relationship instanceof INameable nameable) {
            setIfPresentName(nameable, relationshipJson, "name");
        }
        if(relationship instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, relationshipJson, "documentation");
        }

        EObject source = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "sourceId"));
        EObject target = findPrefixedObject(SimpleJson.readStringField(relationshipJson, "targetId"));
        if(!(source instanceof IArchimateConcept sourceConcept) || !(target instanceof IArchimateConcept targetConcept)) {
            return false;
        }
        relationship.setSource(sourceConcept);
        relationship.setTarget(targetConcept);

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(relationship).getElements().add(relationship);
        seedOtherFields("rel:" + relationshipId, opJson);
        ArchimeshPlugin.logTrace("Applied CreateRelationship id=rel:" + relationshipId + " name=" + getName(relationship));
        return true;
    }

    private boolean applyUpdateRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        EObject eObject = findObjectById(relationshipId);
        if(!(eObject instanceof IArchimateRelationship relationship) || patchJson == null) {
            return false;
        }

        if(relationship instanceof INameable nameable && acceptField("rel:" + relationshipId, "name", opJson)) {
            setIfPresentName(nameable, patchJson, "name");
        }
        if(relationship instanceof IDocumentable documentable && acceptField("rel:" + relationshipId, "documentation", opJson)) {
            setIfPresentDocumentation(documentable, patchJson, "documentation");
        }

        if(SimpleJson.hasField(patchJson, "sourceId") && acceptField("rel:" + relationshipId, "source", opJson)) {
            EObject source = findPrefixedObject(SimpleJson.readStringField(patchJson, "sourceId"));
            if(!(source instanceof IArchimateConcept sourceConcept)) {
                return false;
            }
            relationship.setSource(sourceConcept);
        }

        if(SimpleJson.hasField(patchJson, "targetId") && acceptField("rel:" + relationshipId, "target", opJson)) {
            EObject target = findPrefixedObject(SimpleJson.readStringField(patchJson, "targetId"));
            if(!(target instanceof IArchimateConcept targetConcept)) {
                return false;
            }
            relationship.setTarget(targetConcept);
        }

        ArchimeshPlugin.logTrace("Applied UpdateRelationship id=rel:" + relationshipId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteRelationship(String opJson) {
        String relationshipId = stripPrefix(SimpleJson.readStringField(opJson, "relationshipId"), "rel:");
        EObject eObject = findObjectById(relationshipId);
        if(eObject == null) {
            return false;
        }
        if(eObject instanceof IArchimateRelationship relationship) {
            deleteRelationshipAndReferences(relationship);
        }
        else {
            EcoreUtil.delete(eObject, true);
        }
        ArchimeshPlugin.logTrace("Applied DeleteRelationship id=rel:" + relationshipId);
        return true;
    }

    private boolean applyCreateView(String opJson) {
        String viewJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "view"));
        if(viewJson == null) {
            return false;
        }

        String viewId = stripPrefix(SimpleJson.readStringField(viewJson, "id"), "view:");
        if(viewId == null || findObjectById(viewId) != null) {
            return false;
        }

        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId(viewId);
        setIfPresentName(view, viewJson, "name");
        if(view instanceof IDocumentable documentable) {
            setIfPresentDocumentation(documentable, viewJson, "documentation");
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return false;
        }
        model.getDefaultFolderForObject(view).getElements().add(view);
        seedOtherFields("view:" + viewId, opJson);
        ArchimeshPlugin.logTrace("Applied CreateView id=view:" + viewId + " name=" + getName(view));
        return true;
    }

    private boolean applyUpdateView(String opJson) {
        String viewId = stripPrefix(SimpleJson.readStringField(opJson, "viewId"), "view:");
        String patchJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "patch"));
        EObject eObject = findObjectById(viewId);
        if(!(eObject instanceof IDiagramModel view) || patchJson == null) {
            return false;
        }

        if(view instanceof INameable nameable && acceptField("view:" + viewId, "name", opJson)) {
            setIfPresentName(nameable, patchJson, "name");
        }
        if(view instanceof IDocumentable documentable && acceptField("view:" + viewId, "documentation", opJson)) {
            setIfPresentDocumentation(documentable, patchJson, "documentation");
        }
        ArchimeshPlugin.logTrace("Applied UpdateView id=view:" + viewId + " patch=" + summarizePatch(patchJson));
        return true;
    }

    private boolean applyDeleteView(String opJson) {
        String viewId = stripPrefix(SimpleJson.readStringField(opJson, "viewId"), "view:");
        EObject eObject = findObjectById(viewId);
        if(eObject == null) {
            return false;
        }
        if(eObject instanceof IDiagramModel diagramModel) {
            // Keep UI consistent: if a deleted view is open in an editor, close it.
            EditorManager.closeDiagramEditor(diagramModel);
        }
        EcoreUtil.delete(eObject, true);
        ArchimeshPlugin.logTrace("Applied DeleteView id=view:" + viewId);
        return true;
    }

    private boolean applyCreateViewObject(String opJson) {
        String viewObjectJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "viewObject"));
        if(viewObjectJson == null) {
            return false;
        }

        String viewObjectId = stripPrefix(SimpleJson.readStringField(viewObjectJson, "id"), "vo:");
        if(viewObjectId == null || findObjectById(viewObjectId) != null) {
            return false;
        }

        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(viewObjectJson, "viewId"), true);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(viewObjectJson, "representsId"));
        if(view == null || !(representsEObject instanceof IArchimateElement archimateElement)) {
            return false;
        }

        IDiagramModelArchimateObject viewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        viewObject.setId(viewObjectId);
        viewObject.setArchimateElement(archimateElement);

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(viewObjectJson, "notationJson"));
        if(notationJson != null) {
            notationDeserializer.applyViewObjectNotation(viewObject, notationJson);
            seedGeometryClock(viewObjectId, notationJson, opJson);
        }
        // Archi edit parts expect bounds to be non-null when the object is added to the view.
        if(viewObject.getBounds() == null) {
            viewObject.setBounds(10, 10, 120, 55);
        }

        view.getChildren().add(viewObject);
        ArchimeshPlugin.logTrace("Applied CreateViewObject id=vo:" + viewObjectId + " represents=" + SimpleJson.readStringField(viewObjectJson, "representsId"));
        return true;
    }

    private boolean applyDeleteViewObject(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        EObject eObject = findObjectById(viewObjectId);
        if(eObject == null) {
            return false;
        }
        if(eObject instanceof IDiagramModelArchimateObject viewObject && viewObject.getArchimateElement() == null) {
            // Do not keep dangling objects in the model. Remove from parent container if possible.
            if(viewObject.eContainer() instanceof IDiagramModelContainer container) {
                container.getChildren().remove(viewObject);
            }
            else {
                EcoreUtil.delete(eObject, true);
            }
            viewObjectNotationClocks.remove(viewObjectId);
            ArchimeshPlugin.logTrace("Removed dangling view object id=vo:" + viewObjectId);
            return true;
        }
        EcoreUtil.delete(eObject, true);
        viewObjectNotationClocks.remove(viewObjectId);
        ArchimeshPlugin.logTrace("Applied DeleteViewObject id=vo:" + viewObjectId);
        return true;
    }

    private void deleteRelationshipAndReferences(IArchimateRelationship relationship) {
        List<IDiagramModelArchimateConnection> referencingConnections =
                new ArrayList<>(relationship.getReferencingDiagramConnections());
        for(IDiagramModelArchimateConnection connection : referencingConnections) {
            if(connection instanceof EObject eo) {
                EcoreUtil.delete(eo, true);
            }
        }
        EcoreUtil.delete(relationship, true);
    }

    private boolean applyCreateConnection(String opJson) {
        String connectionJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "connection"));
        if(connectionJson == null) {
            return false;
        }

        String connectionId = stripPrefix(SimpleJson.readStringField(connectionJson, "id"), "conn:");
        if(connectionId == null || findObjectById(connectionId) != null) {
            return false;
        }

        IDiagramModel view = resolveViewForOp(SimpleJson.readStringField(connectionJson, "viewId"), true);
        EObject representsEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "representsId"));
        EObject sourceEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "sourceViewObjectId"));
        EObject targetEObject = findPrefixedObject(SimpleJson.readStringField(connectionJson, "targetViewObjectId"));
        if(view == null
                || !(representsEObject instanceof IArchimateRelationship relationship)
                || !(sourceEObject instanceof IConnectable source)
                || !(targetEObject instanceof IConnectable target)) {
            return false;
        }

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId(connectionId);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);

        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(connectionJson, "notationJson"));
        if(notationJson != null) {
            notationDeserializer.applyConnectionNotation(connection, notationJson);
            seedConnectionNotationClock(connectionId, notationJson, opJson);
        }
        ArchimeshPlugin.logTrace("Applied CreateConnection id=conn:" + connectionId + " represents=" + SimpleJson.readStringField(connectionJson, "representsId"));
        return true;
    }

    private boolean applyDeleteConnection(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        EObject eObject = findObjectById(connectionId);
        if(eObject == null) {
            return false;
        }
        EcoreUtil.delete(eObject, true);
        connectionNotationClocks.remove(connectionId);
        ArchimeshPlugin.logTrace("Applied DeleteConnection id=conn:" + connectionId);
        return true;
    }

    private boolean applySetProperty(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        if(targetId == null || key == null || key.isBlank()) {
            return false;
        }
        CausalClock incoming = parseCausal(opJson);
        String propertyKey = propertyKey(targetId, key);
        CausalClock tombstone = propertyTombstones.get(propertyKey);
        CausalClock existingClock = propertyClocks.get(propertyKey);
        if(!CrdtPropertyMerge.shouldApplySet(
                toMergeClock(incoming),
                toMergeClock(existingClock),
                toMergeClock(tombstone))) {
            ArchimeshPlugin.logTrace("Ignored stale SetProperty target=" + targetId + " key=" + key
                    + " incomingLamport=" + incoming.lamport
                    + " existingLamport=" + (existingClock == null ? "n/a" : existingClock.lamport)
                    + " tombstoneLamport=" + (tombstone == null ? "n/a" : tombstone.lamport));
            return false;
        }

        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            return false;
        }
        String rawValue = SimpleJson.readRawField(opJson, "value");
        String value = rawValue == null || "null".equals(rawValue) ? null
                : rawValue.startsWith("\"") ? SimpleJson.readStringField(opJson, "value") : rawValue;
        if (eObject instanceof IArchimateDiagramModel diagram && "viewpoint".equals(key)) {
            diagram.setViewpoint(value);
        } else if (eObject instanceof IDiagramModel diagram && "connectionRouterType".equals(key) && value != null) {
            diagram.setConnectionRouterType(Integer.parseInt(value));
        }

        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                property.setValue(value);
                propertyClocks.put(propertyKey, incoming);
                propertyTombstones.remove(propertyKey);
                ArchimeshPlugin.logTrace("Applied SetProperty target=" + targetId + " key=" + key + " value=" + value);
                return true;
            }
        }

        IProperty property = IArchimateFactory.eINSTANCE.createProperty();
        property.setKey(key);
        property.setValue(value);
        properties.getProperties().add(property);
        propertyClocks.put(propertyKey, incoming);
        propertyTombstones.remove(propertyKey);
        ArchimeshPlugin.logTrace("Applied SetProperty target=" + targetId + " key=" + key + " value=" + value + " (new)");
        return true;
    }

    private boolean applyUnsetProperty(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        if(targetId == null || key == null || key.isBlank()) {
            return false;
        }
        CausalClock incoming = parseCausal(opJson);
        String propertyKey = propertyKey(targetId, key);
        CausalClock existingClock = propertyClocks.get(propertyKey);
        CausalClock existingTombstone = propertyTombstones.get(propertyKey);
        if(!CrdtPropertyMerge.shouldApplyUnset(
                toMergeClock(incoming),
                toMergeClock(existingClock),
                toMergeClock(existingTombstone))) {
            ArchimeshPlugin.logTrace("Ignored stale UnsetProperty target=" + targetId + " key=" + key
                    + " incomingLamport=" + incoming.lamport);
            return false;
        }

        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            propertyTombstones.put(propertyKey, incoming);
            propertyClocks.remove(propertyKey);
            ArchimeshPlugin.logTrace("Applied UnsetProperty tombstone-only target=" + targetId + " key=" + key);
            return true;
        }

        IProperty match = null;
        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                match = property;
                break;
            }
        }
        if(match != null) {
            properties.getProperties().remove(match);
            propertyTombstones.put(propertyKey, incoming);
            propertyClocks.remove(propertyKey);
            ArchimeshPlugin.logTrace("Applied UnsetProperty target=" + targetId + " key=" + key);
            return true;
        }
        propertyTombstones.put(propertyKey, incoming);
        propertyClocks.remove(propertyKey);
        ArchimeshPlugin.logTrace("Applied UnsetProperty tombstone target=" + targetId + " key=" + key + " (missing key)");
        return true;
    }

    private boolean applyViewObjectOpaque(String opJson) {
        String viewObjectId = stripPrefix(SimpleJson.readStringField(opJson, "viewObjectId"), "vo:");
        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "notationJson"));
        if(viewObjectId == null || notationJson == null) {
            return false;
        }

        EObject eObject = findObjectById(viewObjectId);
        if(!(eObject instanceof IDiagramModelArchimateObject viewObject)) {
            return false;
        }
        String effectiveNotation = applyViewObjectNotationLww(viewObjectId, viewObject, notationJson, opJson);
        notationDeserializer.applyViewObjectNotation(viewObject, effectiveNotation);
        ArchimeshPlugin.logTrace("Applied UpdateViewObjectOpaque id=vo:" + viewObjectId);
        return true;
    }

    private boolean applyConnectionOpaque(String opJson) {
        String connectionId = stripPrefix(SimpleJson.readStringField(opJson, "connectionId"), "conn:");
        String notationJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "notationJson"));
        if(connectionId == null || notationJson == null) {
            return false;
        }

        EObject eObject = findObjectById(connectionId);
        if(!(eObject instanceof IDiagramModelArchimateConnection connection)) {
            return false;
        }
        String effectiveNotation = applyConnectionNotationLww(connectionId, connection, notationJson, opJson);
        notationDeserializer.applyConnectionNotation(connection, effectiveNotation);
        ArchimeshPlugin.logTrace("Applied UpdateConnectionOpaque id=conn:" + connectionId);
        return true;
    }

    private boolean applyAddPropertySetMember(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        String member = SimpleJson.readStringField(opJson, "member");
        if(targetId == null || key == null || key.isBlank() || member == null || member.isBlank()) {
            return false;
        }
        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            return false;
        }

        seedPropertySetStateFromCurrentValue(targetId, key, properties);
        CausalClock incoming = parseCausal(opJson);
        String memberKey = propertySetMemberKey(targetId, key, member);
        PropertySetMemberClock existing = propertySetMemberClocks.get(memberKey);
        CrdtPropertyMerge.Clock existingAdd = existing != null && !existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        CrdtPropertyMerge.Clock existingRemove = existing != null && existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        if(!CrdtOrSet.shouldApplyAdd(toMergeClock(incoming), existingAdd, existingRemove)) {
            return false;
        }

        propertySetMemberClocks.put(memberKey, new PropertySetMemberClock(incoming, false));
        materializePropertySetValue(targetId, key, properties);
        return true;
    }

    private boolean applyRemovePropertySetMember(String opJson) {
        String targetId = SimpleJson.readStringField(opJson, "targetId");
        String key = SimpleJson.readStringField(opJson, "key");
        String member = SimpleJson.readStringField(opJson, "member");
        if(targetId == null || key == null || key.isBlank() || member == null || member.isBlank()) {
            return false;
        }
        EObject eObject = findPrefixedObject(targetId);
        if(!(eObject instanceof IProperties properties)) {
            return false;
        }

        seedPropertySetStateFromCurrentValue(targetId, key, properties);
        CausalClock incoming = parseCausal(opJson);
        String memberKey = propertySetMemberKey(targetId, key, member);
        PropertySetMemberClock existing = propertySetMemberClocks.get(memberKey);
        CrdtPropertyMerge.Clock existingAdd = existing != null && !existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        CrdtPropertyMerge.Clock existingRemove = existing != null && existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        if(!CrdtOrSet.shouldApplyRemove(toMergeClock(incoming), existingAdd, existingRemove)) {
            return false;
        }

        propertySetMemberClocks.put(memberKey, new PropertySetMemberClock(incoming, true));
        materializePropertySetValue(targetId, key, properties);
        return true;
    }

    private boolean applyAddViewObjectChildMember(String opJson) {
        return applyViewObjectChildMember(opJson, false);
    }

    private boolean applyRemoveViewObjectChildMember(String opJson) {
        return applyViewObjectChildMember(opJson, true);
    }

    private boolean applyViewObjectChildMember(String opJson, boolean deleted) {
        String parentViewObjectId = SimpleJson.readStringField(opJson, "parentViewObjectId");
        String childViewObjectId = SimpleJson.readStringField(opJson, "childViewObjectId");
        if(parentViewObjectId == null || childViewObjectId == null) {
            return false;
        }
        EObject parentObject = findPrefixedObject(parentViewObjectId);
        EObject childObject = findPrefixedObject(childViewObjectId);
        if(!(parentObject instanceof IDiagramModelArchimateObject parent)
                || !(childObject instanceof IDiagramModelArchimateObject child)
                || parent == child) {
            return false;
        }

        CausalClock incoming = parseCausal(opJson);
        String memberKey = viewObjectChildMemberKey(parentViewObjectId, childViewObjectId);
        ViewObjectChildMemberClock existing = viewObjectChildMemberClocks.get(memberKey);
        CrdtPropertyMerge.Clock existingAdd = existing != null && !existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        CrdtPropertyMerge.Clock existingRemove = existing != null && existing.deleted
                ? toMergeClock(existing.clock)
                : null;
        boolean apply = deleted
                ? CrdtOrSet.shouldApplyRemove(toMergeClock(incoming), existingAdd, existingRemove)
                : CrdtOrSet.shouldApplyAdd(toMergeClock(incoming), existingAdd, existingRemove);
        if(!apply) {
            return false;
        }

        viewObjectChildMemberClocks.put(memberKey, new ViewObjectChildMemberClock(incoming, deleted));
        rematerializeViewObjectChildMembership(childViewObjectId);
        return true;
    }

    private void rematerializeViewObjectChildMembership(String childViewObjectId) {
        EObject childObject = findPrefixedObject(childViewObjectId);
        if(!(childObject instanceof IDiagramModelArchimateObject child)) {
            return;
        }

        String suffix = "\u001f" + childViewObjectId;
        String winnerParentId = null;
        CausalClock winnerClock = null;
        for(Map.Entry<String, ViewObjectChildMemberClock> entry : viewObjectChildMemberClocks.entrySet()) {
            if(!entry.getKey().endsWith(suffix) || entry.getValue().deleted) {
                continue;
            }
            int split = entry.getKey().indexOf('\u001f');
            if(split <= 0) {
                continue;
            }
            String candidateParentId = entry.getKey().substring(0, split);
            CausalClock candidateClock = entry.getValue().clock;
            if(winnerClock == null || wins(candidateClock, winnerClock)) {
                winnerParentId = candidateParentId;
                winnerClock = candidateClock;
            }
        }

        if(child.eContainer() instanceof IDiagramModelContainer existingParent) {
            existingParent.getChildren().remove(child);
        }
        if(winnerParentId == null) {
            return;
        }

        EObject parentObject = findPrefixedObject(winnerParentId);
        if(parentObject instanceof IDiagramModelArchimateObject parent && parent != child) {
            parent.getChildren().add(child);
        }
    }

    private EObject findObjectById(String id) {
        IArchimateModel model = sessionManager.getAttachedModel();
        return ArchimateModelUtils.getObjectByID(model, id);
    }

    private IDiagramModel resolveViewForOp(String prefixedViewId, boolean reconcileId) {
        EObject direct = findPrefixedObject(prefixedViewId);
        if(direct instanceof IDiagramModel diagramModel) {
            return diagramModel;
        }

        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null) {
            return null;
        }

        List<IDiagramModel> candidates = new ArrayList<>();
        for(var it = model.eAllContents(); it.hasNext();) {
            EObject object = it.next();
            if(object instanceof IDiagramModel diagramModel) {
                candidates.add(diagramModel);
            }
        }
        if(candidates.isEmpty()) {
            return null;
        }

        IDiagramModel candidate = null;
        if(candidates.size() == 1) {
            candidate = candidates.get(0);
        }
        else {
            IDiagramModel defaultViewCandidate = null;
            for(IDiagramModel view : candidates) {
                if(view instanceof INameable nameable && "Default View".equals(nameable.getName())) {
                    if(defaultViewCandidate != null) {
                        defaultViewCandidate = null;
                        break;
                    }
                    defaultViewCandidate = view;
                }
            }
            candidate = defaultViewCandidate;
        }
        if(candidate == null) {
            return null;
        }

        if(reconcileId && candidate instanceof IIdentifier identifier) {
            String targetId = stripPrefix(prefixedViewId, "view:");
            if(targetId != null
                    && !targetId.isBlank()
                    && !targetId.equals(identifier.getId())
                    && findObjectById(targetId) == null) {
                String oldId = identifier.getId();
                identifier.setId(targetId);
                ArchimeshPlugin.logInfo("Reconciled local view identity old=view:" + oldId + " new=view:" + targetId);
            }
        }
        return candidate;
    }

    private EObject findPrefixedObject(String prefixedId) {
        if(prefixedId == null) {
            return null;
        }
        if(prefixedId.startsWith("folder:")) {
            return resolveFolder(prefixedId);
        }
        if(prefixedId.startsWith("elem:")) {
            return findObjectById(prefixedId.substring("elem:".length()));
        }
        if(prefixedId.startsWith("rel:")) {
            return findObjectById(prefixedId.substring("rel:".length()));
        }
        if(prefixedId.startsWith("view:")) {
            return findObjectById(prefixedId.substring("view:".length()));
        }
        if(prefixedId.startsWith("vo:")) {
            return findObjectById(prefixedId.substring("vo:".length()));
        }
        if(prefixedId.startsWith("conn:")) {
            return findObjectById(prefixedId.substring("conn:".length()));
        }
        return findObjectById(prefixedId);
    }

    private IFolder resolveFolder(String folderId) {
        IArchimateModel model = sessionManager.getAttachedModel();
        if(model == null || folderId == null || folderId.isBlank()) {
            return null;
        }
        if(isRootFolderId(folderId)) {
            return resolveRootFolder(model, folderId);
        }
        EObject folder = findObjectById(stripPrefix(folderId, "folder:"));
        return folder instanceof IFolder typed ? typed : null;
    }

    private IFolder resolveRootFolder(IArchimateModel model, String folderId) {
        FolderType type = folderTypeFromRootId(folderId);
        return type == null || model == null ? null : model.getFolder(type);
    }

    private boolean isRootFolderId(String folderId) {
        return folderId != null && folderId.startsWith("folder:root-");
    }

    private FolderType folderTypeFromRootId(String folderId) {
        if(!isRootFolderId(folderId)) {
            return null;
        }
        String suffix = folderId.substring("folder:root-".length()).replace('-', '_').toUpperCase();
        try {
            return FolderType.valueOf(suffix);
        } catch(IllegalArgumentException ex) {
            return null;
        }
    }

    private FolderType parseFolderType(String raw) {
        if(raw == null || raw.isBlank()) {
            return FolderType.USER;
        }
        try {
            return FolderType.valueOf(raw.trim().toUpperCase());
        } catch(IllegalArgumentException ex) {
            return FolderType.USER;
        }
    }

    private void applyFolderFields(IFolder folder, String json) {
        if(folder == null || json == null) {
            return;
        }
        setIfPresentName(folder, json, "name");
        setIfPresentDocumentation(folder, json, "documentation");
        if(SimpleJson.hasField(json, "folderType")) {
            FolderType type = parseFolderType(SimpleJson.readStringField(json, "folderType"));
            if(type != null) {
                folder.setType(type);
            }
        }
    }

    private boolean moveFolder(IFolder folder, String parentFolderId) {
        if(folder == null) {
            return false;
        }
        IFolder parent = resolveFolder(parentFolderId);
        if(parent == null || parent == folder || isFolderDescendant(parent, folder)) {
            return false;
        }
        if(folder.eContainer() instanceof IFolderContainer existingParent) {
            existingParent.getFolders().remove(folder);
        }
        parent.getFolders().add(folder);
        return true;
    }

    private boolean moveFolderMember(EObject member, String folderId) {
        if(member == null) {
            return false;
        }
        IFolder targetFolder = resolveFolder(folderId);
        if(targetFolder == null) {
            return false;
        }
        if(member.eContainer() instanceof IFolder existingFolder) {
            existingFolder.getElements().remove(member);
        }
        targetFolder.getElements().add(member);
        return true;
    }

    private boolean isFolderDescendant(IFolder candidateParent, IFolder folder) {
        EObject current = candidateParent;
        while(current instanceof IFolder currentFolder) {
            if(currentFolder == folder) {
                return true;
            }
            current = currentFolder.eContainer();
        }
        return false;
    }

    private EObject createEObject(String archimateType) {
        if(archimateType == null || archimateType.isBlank()) {
            return null;
        }
        EClassifier classifier = IArchimatePackage.eINSTANCE.getEClassifier(archimateType);
        if(!(classifier instanceof EClass eClass)) {
            return null;
        }
        return IArchimateFactory.eINSTANCE.create(eClass);
    }

    private void setIfPresentName(INameable nameable, String json, String key) {
        if(SimpleJson.hasField(json, key)) {
            nameable.setName(SimpleJson.readStringField(json, key));
        }
    }

    private void setIfPresentDocumentation(IDocumentable documentable, String json, String key) {
        if(SimpleJson.hasField(json, key)) {
            documentable.setDocumentation(SimpleJson.readStringField(json, key));
        }
    }

    private String getName(Object object) {
        return object instanceof INameable nameable ? nameable.getName() : "";
    }

    private String getDocumentation(Object object) {
        return object instanceof IDocumentable documentable ? documentable.getDocumentation() : "";
    }

    private String propertyKey(String targetId, String key) {
        return (targetId == null ? "" : targetId) + "#" + (key == null ? "" : key);
    }

    private String summarizePatch(String patchJson) {
        if(patchJson == null) {
            return "{}";
        }
        String name = SimpleJson.readStringField(patchJson, "name");
        String sourceId = SimpleJson.readStringField(patchJson, "sourceId");
        String targetId = SimpleJson.readStringField(patchJson, "targetId");
        return "{name=" + name + ",sourceId=" + sourceId + ",targetId=" + targetId + "}";
    }

    private String summarizeOp(String opJson) {
        if(opJson == null) {
            return "null";
        }
        String type = SimpleJson.readStringField(opJson, "type");
        StringBuilder summary = new StringBuilder("type=").append(type);
        String elementId = SimpleJson.readStringField(opJson, "elementId");
        String relationshipId = SimpleJson.readStringField(opJson, "relationshipId");
        String viewId = SimpleJson.readStringField(opJson, "viewId");
        String viewObjectId = SimpleJson.readStringField(opJson, "viewObjectId");
        String connectionId = SimpleJson.readStringField(opJson, "connectionId");
        if(elementId != null) summary.append(" elementId=").append(elementId);
        if(relationshipId != null) summary.append(" relationshipId=").append(relationshipId);
        if(viewId != null) summary.append(" viewId=").append(viewId);
        if(viewObjectId != null) summary.append(" viewObjectId=").append(viewObjectId);
        if(connectionId != null) summary.append(" connectionId=").append(connectionId);
        return summary.toString();
    }

    private String stripPrefix(String value, String prefix) {
        if(value == null) {
            return null;
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private int applySnapshotArray(String snapshotJson, String arrayKey, String opType, String opFieldName) {
        List<String> items = SimpleJson.readArrayObjectElements(snapshotJson, arrayKey);
        int applied = 0;
        for(String item : items) {
            String opJson = "{\"type\":\"" + opType + "\",\"" + opFieldName + "\":" + item + "}";
            if(applyOp(opJson)) {
                applied++;
            }
            else {
                ArchimeshPlugin.logTrace("Snapshot op ignored/failed: " + summarizeOp(opJson));
            }
        }
        return applied;
    }

    private int applySnapshotViewObjectChildMembers(String snapshotJson) {
        List<String> members = SimpleJson.readArrayObjectElements(snapshotJson, "viewObjectChildMembers");
        int applied = 0;
        for(String member : members) {
            String parentViewObjectId = SimpleJson.readStringField(member, "parentViewObjectId");
            String childViewObjectId = SimpleJson.readStringField(member, "childViewObjectId");
            if(parentViewObjectId == null || childViewObjectId == null) {
                continue;
            }
            String opJson = "{"
                    + "\"type\":\"AddViewObjectChildMember\","
                    + "\"parentViewObjectId\":\"" + escapeJson(parentViewObjectId) + "\","
                    + "\"childViewObjectId\":\"" + escapeJson(childViewObjectId) + "\""
                    + "}";
            if(applyOp(opJson)) {
                applied++;
            }
            else {
                ArchimeshPlugin.logTrace("Snapshot op ignored/failed: " + summarizeOp(opJson));
            }
        }
        return applied;
    }

    private int applySnapshotFolderMembers(String snapshotJson, String arrayKey, String opType, String targetKey) {
        List<String> members = SimpleJson.readArrayObjectElements(snapshotJson, arrayKey);
        int applied = 0;
        for(String member : members) {
            String targetId = SimpleJson.readStringField(member, targetKey);
            String folderId = SimpleJson.readStringField(member, "folderId");
            if(targetId == null || folderId == null) {
                continue;
            }
            String opJson = "{"
                    + "\"type\":\"" + escapeJson(opType) + "\","
                    + "\"" + escapeJson(targetKey) + "\":\"" + escapeJson(targetId) + "\","
                    + "\"folderId\":\"" + escapeJson(folderId) + "\""
                    + "}";
            if(applyOp(opJson)) {
                applied++;
            }
            else {
                ArchimeshPlugin.logTrace("Snapshot op ignored/failed: " + summarizeOp(opJson));
            }
        }
        return applied;
    }

    private void restoreMergeState(String snapshot) {
        for (String op : SimpleJson.readArrayObjectElements(snapshot, "propertyState")) applyOp(op);
        for (String entry : SimpleJson.readArrayObjectElements(snapshot, "fieldClocks")) {
            String id = SimpleJson.readStringField(entry, "targetId");
            String field = SimpleJson.readStringField(entry, "field");
            if (id == null || field == null) continue;
            Map<String, FieldClock> clocks = id.startsWith("elem:") ? elementFieldClocks
                    : id.startsWith("vo:") ? viewObjectNotationClocks
                    : id.startsWith("conn:") ? connectionNotationClocks : otherFieldClocks;
            String key = (id.startsWith("elem:") || id.startsWith("vo:") || id.startsWith("conn:"))
                    ? id.substring(id.indexOf(':') + 1) : id;
            clocks.computeIfAbsent(key, ignored -> new FieldClock()).set(field, parseCausal(entry));
        }
        for (String entry : SimpleJson.readArrayObjectElements(snapshot, "tombstones")) {
            String id = SimpleJson.readStringField(entry, "targetId");
            if (id == null) continue;
            CausalClock clock = parseCausal(entry);
            entityTombstones.put(id, clock);
            if (id.startsWith("elem:")) elementTombstones.put(stripPrefix(id, "elem:"), clock);
        }
    }

    private void seedOtherFields(String id, String op) {
        FieldClock clock = otherFieldClocks.computeIfAbsent(id, ignored -> new FieldClock());
        CausalClock incoming = parseCausal(op);
        for (String field : new String[]{"name", "documentation", "source", "target", "parent", "notation"}) {
            if (wins(incoming, clock.get(field))) clock.set(field, incoming);
        }
    }

    private boolean acceptField(String id, String field, String op) {
        FieldClock clock = otherFieldClocks.computeIfAbsent(id, ignored -> new FieldClock());
        CausalClock incoming = parseCausal(op);
        if (!wins(incoming, clock.get(field))) return false;
        clock.set(field, incoming);
        return true;
    }

    private void clearModelContents(IArchimateModel model) {
        otherFieldClocks.clear();
        entityTombstones.clear();
        elementFieldClocks.clear();
        elementTombstones.clear();
        propertyClocks.clear();
        propertyTombstones.clear();
        propertySetMemberClocks.clear();
        viewObjectChildMemberClocks.clear();
        viewObjectNotationClocks.clear();
        connectionNotationClocks.clear();
        ModelProjectionReset.clear(model);
    }

    private void seedGeometryClock(String viewObjectId, String notationJson, String opJson) {
        FieldClock clock = viewObjectNotationClocks.computeIfAbsent(viewObjectId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        seedClockFromNotation(clock, notationJson, incoming);
    }

    private void seedElementClock(String elementId, String json, CausalClock incoming) {
        FieldClock clock = elementFieldClocks.computeIfAbsent(elementId, id -> new FieldClock());
        if(SimpleJson.hasField(json, "name")) {
            clock.set("name", incoming);
        }
        if(SimpleJson.hasField(json, "documentation")) {
            clock.set("documentation", incoming);
        }
    }

    private void seedConnectionNotationClock(String connectionId, String notationJson, String opJson) {
        FieldClock clock = connectionNotationClocks.computeIfAbsent(connectionId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        seedClockFromNotation(clock, notationJson, incoming);
    }

    private void seedClockFromNotation(FieldClock clock, String notationJson, CausalClock incoming) {
        for(String field : extractNotationFields(notationJson)) {
            clock.set(field, incoming);
        }
    }

    private String applyViewObjectNotationLww(String viewObjectId,
                                              IDiagramModelArchimateObject viewObject,
                                              String notationJson,
                                              String opJson) {
        FieldClock clock = viewObjectNotationClocks.computeIfAbsent(viewObjectId, id -> new FieldClock());
        CausalClock incoming = parseCausal(opJson);
        String rewritten = notationJson;

        if(viewObject.getBounds() != null) {
            rewritten = mergeNumberField("x", viewObject.getBounds().getX(), rewritten, incoming, clock);
            rewritten = mergeNumberField("y", viewObject.getBounds().getY(), rewritten, incoming, clock);
            rewritten = mergeNumberField("width", viewObject.getBounds().getWidth(), rewritten, incoming, clock);
            rewritten = mergeNumberField("height", viewObject.getBounds().getHeight(), rewritten, incoming, clock);
        }
        rewritten = mergeNumberField("type", viewObject.getType(), rewritten, incoming, clock);
        rewritten = mergeNumberField("alpha", viewObject.getAlpha(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineAlpha", viewObject.getLineAlpha(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineWidth", viewObject.getLineWidth(), rewritten, incoming, clock);
        rewritten = mergeNumberField("lineStyle", viewObject.getLineStyle(), rewritten, incoming, clock);
        rewritten = mergeNumberField("textAlignment", viewObject.getTextAlignment(), rewritten, incoming, clock);
        rewritten = mergeNumberField("textPosition", viewObject.getTextPosition(), rewritten, incoming, clock);
        rewritten = mergeNumberField("gradient", viewObject.getGradient(), rewritten, incoming, clock);
        rewritten = mergeNumberField("iconVisibleState", viewObject.getIconVisibleState(), rewritten, incoming, clock);
        rewritten = mergeBooleanField("deriveElementLineColor", viewObject.getDeriveElementLineColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("fillColor", viewObject.getFillColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("lineColor", viewObject.getLineColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("font", viewObject.getFont(), rewritten, incoming, clock);
        rewritten = mergeStringField("fontColor", viewObject.getFontColor(), rewritten, incoming, clock);
        rewritten = mergeStringField("iconColor", viewObject.getIconColor(), rewritten, incoming, clock);
        if(viewObject instanceof com.archimatetool.model.IIconic iconic) {
            rewritten = mergeStringField("imagePath", iconic.getImagePath(), rewritten, incoming, clock);
            rewritten = mergeNumberField("imagePosition", iconic.getImagePosition(), rewritten, incoming, clock);
        }
        rewritten = mergeStringField("name", getName(viewObject), rewritten, incoming, clock);
        rewritten = mergeStringField("documentation", getDocumentation(viewObject), rewritten, incoming, clock);
        return rewritten;
    }

    private String applyConnectionNotationLww(String connectionId,
                                              IDiagramModelArchimateConnection connection,
                                      String notationJson,
                                      String opJson) {
        FieldClock clock = connectionNotationClocks.computeIfAbsent(connectionId, id -> new FieldClock());
        CausalClock causal = parseCausal(opJson);
        String rewritten = notationJson;
        rewritten = mergeNumberField("type", connection.getType(), rewritten, causal, clock);
        rewritten = mergeBooleanField("nameVisible", connection.isNameVisible(), rewritten, causal, clock);
        rewritten = mergeNumberField("textAlignment", connection.getTextAlignment(), rewritten, causal, clock);
        rewritten = mergeNumberField("textPosition", connection.getTextPosition(), rewritten, causal, clock);
        rewritten = mergeNumberField("lineWidth", connection.getLineWidth(), rewritten, causal, clock);
        rewritten = mergeStringField("name", getName(connection), rewritten, causal, clock);
        rewritten = mergeStringField("lineColor", connection.getLineColor(), rewritten, causal, clock);
        rewritten = mergeStringField("font", connection.getFont(), rewritten, causal, clock);
        rewritten = mergeStringField("fontColor", connection.getFontColor(), rewritten, causal, clock);
        rewritten = mergeStringField("documentation", getDocumentation(connection), rewritten, causal, clock);
        if(SimpleJson.hasField(rewritten, "bendpoints")) {
            rewritten = mergeRawField("bendpoints", bendpointsJson(connection), rewritten, causal, clock);
        }
        return rewritten;
    }

    private String mergeNumberField(String field,
                                      int currentValue,
                                      String notationJson,
                                      CausalClock incoming,
                                      FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }

        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }

        String rewritten = overwriteNumberField(notationJson, field, currentValue);
        ArchimeshPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeBooleanField(String field,
                                     boolean currentValue,
                                     String notationJson,
                                     CausalClock incoming,
                                     FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteBooleanField(notationJson, field, currentValue);
        ArchimeshPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeStringField(String field,
                                    String currentValue,
                                    String notationJson,
                                    CausalClock incoming,
                                    FieldClock clock) {
        if(!SimpleJson.hasField(notationJson, field)) {
            return notationJson;
        }
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteNullableStringField(notationJson, field, currentValue);
        ArchimeshPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String mergeRawField(String field,
                                 String currentRawJson,
                                 String notationJson,
                                 CausalClock incoming,
                                 FieldClock clock) {
        CausalClock existing = clock.get(field);
        if(wins(incoming, existing)) {
            clock.set(field, incoming);
            return notationJson;
        }
        String rewritten = overwriteRawField(notationJson, field, currentRawJson);
        ArchimeshPlugin.logTrace("Ignored stale notation field " + field + " incomingLamport="
                + incoming.lamport + " incomingClientId=" + incoming.clientId
                + " existingLamport=" + existing.lamport + " existingClientId=" + existing.clientId);
        return rewritten;
    }

    private String overwriteNumberField(String notationJson, String field, int replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)-?\\d+");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + replacementValue));
    }

    private String overwriteBooleanField(String notationJson, String field, boolean replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)(true|false)");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + replacementValue));
    }

    private String overwriteNullableStringField(String notationJson, String field, String replacementValue) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)(null|\\\"(?:\\\\\\\\.|[^\\\"])*\\\")");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + jsonStringOrNull(replacementValue)));
    }

    private String overwriteRawField(String notationJson, String field, String rawJson) {
        Pattern pattern = Pattern.compile("(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)\\[[^\\]]*\\]");
        Matcher matcher = pattern.matcher(notationJson);
        if(!matcher.find()) {
            return notationJson;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + rawJson));
    }

    private CausalClock parseCausal(String opJson) {
        String causalJson = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "causal"));
        Long lamport = SimpleJson.readLongField(causalJson, "lamport");
        String clientId = SimpleJson.readStringField(causalJson, "clientId");
        if (lamport != null) OpMapper.observeLamport(lamport);
        return new CausalClock(lamport == null ? 0L : lamport, clientId == null ? "" : clientId);
    }

    private boolean wins(CausalClock incoming, CausalClock existing) {
        return CrdtPropertyMerge.wins(toMergeClock(incoming), toMergeClock(existing));
    }

    private CrdtPropertyMerge.Clock toMergeClock(CausalClock clock) {
        return clock == null ? null : CrdtPropertyMerge.clock(clock.lamport, clock.clientId);
    }

    private String bendpointsJson(IDiagramModelArchimateConnection connection) {
        StringBuilder json = new StringBuilder("[");
        for(int i = 0; i < connection.getBendpoints().size(); i++) {
            var bendpoint = connection.getBendpoints().get(i);
            json.append("{")
                    .append("\"startX\":").append(bendpoint.getStartX()).append(",")
                    .append("\"startY\":").append(bendpoint.getStartY()).append(",")
                    .append("\"endX\":").append(bendpoint.getEndX()).append(",")
                    .append("\"endY\":").append(bendpoint.getEndY())
                    .append("}");
            if(i < connection.getBendpoints().size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }

    private String jsonStringOrNull(String value) {
        if(value == null) {
            return "null";
        }
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<String> extractNotationFields(String notationJson) {
        List<String> fields = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\\"([A-Za-z0-9_]+)\\\"\\s*:");
        Matcher matcher = pattern.matcher(notationJson == null ? "" : notationJson);
        while(matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static final class FieldClock {
        private static final CausalClock UNKNOWN = new CausalClock(-1L, "");
        private final Map<String, CausalClock> byField = new HashMap<>();

        CausalClock get(String field) {
            return byField.getOrDefault(field, UNKNOWN);
        }

        void set(String field, CausalClock clock) {
            byField.put(field, clock);
        }
    }

    private record CausalClock(long lamport, String clientId) {
    }

    private record PropertySetMemberClock(CausalClock clock, boolean deleted) {
    }

    private record ViewObjectChildMemberClock(CausalClock clock, boolean deleted) {
    }

    private void seedPropertySetStateFromCurrentValue(String targetId, String key, IProperties properties) {
        String prefix = propertySetMemberPrefix(targetId, key);
        boolean alreadySeeded = propertySetMemberClocks.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        if(alreadySeeded) {
            return;
        }
        String current = null;
        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                current = property.getValue();
                break;
            }
        }
        for(String member : parseStringArray(current)) {
            propertySetMemberClocks.put(propertySetMemberKey(targetId, key, member),
                    new PropertySetMemberClock(new CausalClock(-1L, ""), false));
        }
    }

    private void materializePropertySetValue(String targetId, String key, IProperties properties) {
        String prefix = propertySetMemberPrefix(targetId, key);
        List<String> members = new ArrayList<>();
        for(Map.Entry<String, PropertySetMemberClock> entry : propertySetMemberClocks.entrySet()) {
            if(!entry.getKey().startsWith(prefix) || entry.getValue().deleted) {
                continue;
            }
            String member = entry.getKey().substring(prefix.length());
            if(!member.isBlank()) {
                members.add(member);
            }
        }
        members.sort(String::compareTo);

        IProperty match = null;
        for(IProperty property : properties.getProperties()) {
            if(key.equals(property.getKey())) {
                match = property;
                break;
            }
        }

        if(members.isEmpty()) {
            if(match != null) {
                properties.getProperties().remove(match);
            }
            return;
        }

        String value = toJsonStringArray(members);
        if(match == null) {
            IProperty property = IArchimateFactory.eINSTANCE.createProperty();
            property.setKey(key);
            property.setValue(value);
            properties.getProperties().add(property);
            return;
        }
        match.setValue(value);
    }

    private String propertySetMemberPrefix(String targetId, String key) {
        return targetId + "\u001f" + key + "\u001f";
    }

    private String propertySetMemberKey(String targetId, String key, String member) {
        return propertySetMemberPrefix(targetId, key) + member;
    }

    private String viewObjectChildMemberKey(String parentViewObjectId, String childViewObjectId) {
        return parentViewObjectId + "\u001f" + childViewObjectId;
    }

    private List<String> parseStringArray(String rawValue) {
        List<String> values = new ArrayList<>();
        if(rawValue == null) {
            return values;
        }
        String text = rawValue.trim();
        if(text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            return values;
        }

        boolean inString = false;
        boolean escaped = false;
        int start = -1;
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if(!inString) {
                if(c == '"') {
                    inString = true;
                    start = i + 1;
                }
                continue;
            }
            if(escaped) {
                escaped = false;
                continue;
            }
            if(c == '\\') {
                escaped = true;
                continue;
            }
            if(c == '"') {
                if(start >= 0 && start <= i) {
                    values.add(SimpleJson.decodeString(text.substring(start, i)));
                }
                inString = false;
                start = -1;
            }
        }
        return values;
    }

    private String toJsonStringArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < values.size(); i++) {
            if(i > 0) {
                out.append(",");
            }
            out.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        out.append("]");
        return out.toString();
    }

}
