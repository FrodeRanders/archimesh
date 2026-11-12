package org.gautelis.archimesh.plugin.emf;

import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProperty;

/**
 * Captures local EMF changes and maps them to Archimesh ops.
 */
public class EmfChangeCapture extends EContentAdapter {
    private static final long NOTATION_DEBOUNCE_MS = 180;
    private static final long CONNECTION_CREATE_RETRY_DELAY_MS = 120;
    private static final int CONNECTION_CREATE_MAX_RETRIES = 20;
    private static final long VIEW_OBJECT_CREATE_RETRY_DELAY_MS = 120;
    private static final int VIEW_OBJECT_CREATE_MAX_RETRIES = 20;

    private final OpMapper opMapper = new OpMapper();
    private final ArchimeshSessionManager sessionManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "archimesh-emf-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> pendingNotationUpdates = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingConnectionCreates = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingConnectionCreateAttempts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingViewObjectCreates = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingViewObjectCreateAttempts = new ConcurrentHashMap<>();
    private final java.util.Set<String> knownElementIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> knownRelationshipIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> knownViewIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedElementIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedRelationshipIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedViewIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedConnectionIds = ConcurrentHashMap.newKeySet();
    // Relationship ids that are waiting for a pending connection-create retry.
    // While present, standalone CreateRelationship submission is suppressed so the
    // paired (CreateRelationship + CreateConnection) batch can satisfy server preconditions.
    private final java.util.Set<String> pendingConnectionRelationshipIds = ConcurrentHashMap.newKeySet();
    // Folder ids are tracked locally so rename/move handling can keep referring to immutable ids.
    private final java.util.Set<String> knownFolderIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<IDiagramModelArchimateObject> pendingViewObjectRemovals = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public EmfChangeCapture(ArchimeshSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        seedKnownElementIds();
        seedKnownRelationshipIds();
        seedKnownViewIds();
        seedKnownFolderIds();
    }

    @Override
    public void notifyChanged(Notification notification) {
        super.notifyChanged(notification);
        ArchimeshPlugin.logTrace("EMF event type=" + eventTypeName(notification.getEventType())
                + " notifier=" + className(notification.getNotifier())
                + " feature=" + featureName(notification));

        if(RemoteApplyGuard.isRemoteApply()) {
            // Prevent rebroadcast loops while applying server-originated mutations
            ArchimeshPlugin.logTrace("EMF event ignored: remote apply guard active");
            return;
        }

        boolean hasArchimeshModelContext = sessionManager != null
                && sessionManager.getCurrentModelId() != null
                && !sessionManager.getCurrentModelId().isBlank();
        if(notification.getNotifier() == null
                || sessionManager == null
                || (!sessionManager.isConnected() && !hasArchimeshModelContext)) {
            ArchimeshPlugin.logTrace("EMF event ignored: notifier/session missing or disconnected"
                    + " notifierNull=" + (notification.getNotifier() == null)
                    + " sessionNull=" + (sessionManager == null)
                    + " connected=" + (sessionManager != null && sessionManager.isConnected())
                    + " hasContext=" + hasArchimeshModelContext);
            return;
        }

        if(sessionManager.isCurrentReferenceReadOnly()) {
            ArchimeshPlugin.logTrace("EMF event ignored: active Archimesh ref is read-only ref="
                    + sessionManager.getCurrentModelRef());
            return;
        }

        switch(notification.getEventType()) {
            case Notification.ADD -> handleAdd(notification, notification.getNewValue());
            case Notification.ADD_MANY -> handleAddMany(notification);
            case Notification.REMOVE -> handleRemove(notification, notification.getOldValue());
            case Notification.REMOVE_MANY -> handleRemoveMany(notification);
            case Notification.SET -> handleSet(notification);
            default -> {
                ArchimeshPlugin.logTrace("EMF event ignored: unsupported event type " + notification.getEventType());
            }
        }
    }

    private void handleAddMany(Notification notification) {
        Object newValue = notification.getNewValue();
        if(newValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleAdd(notification, item);
            }
            return;
        }
        ArchimeshPlugin.logTrace("ADD_MANY ignored: payload is not a collection (" + className(newValue) + ")");
    }

    private void handleRemoveMany(Notification notification) {
        Object oldValue = notification.getOldValue();
        if(oldValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleRemove(notification, item);
            }
            return;
        }
        ArchimeshPlugin.logTrace("REMOVE_MANY ignored: payload is not a collection (" + className(oldValue) + ")");
    }

    private void handleAdd(Notification notification, Object newValue) {
        String feature = featureName(notification);
        Object notifier = notification.getNotifier();
        if("folders".equals(feature) && newValue instanceof IFolder folder) {
            // Model-tree folders are synchronized explicitly; relying on default Archi folders would drift.
            handleFolderAdded(notifier, folder);
            return;
        }
        if("elements".equals(feature) && notifier instanceof IFolder folder) {
            // Placement changes are separate ops so object identity never depends on its current folder path.
            if(newValue instanceof IArchimateElement element) {
                if(isNewLocalElement(element)) {
                    rememberKnownElement(element);
                    rememberSubmittedElement(element);
                    send(opMapper.toCreateElementInFolderSubmitOps(
                            element,
                            folder,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "CreateElementInFolder");
                    return;
                }
                send(opMapper.toMoveElementToFolderSubmitOps(
                        element,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveElementToFolder");
                return;
            }
            if(newValue instanceof IArchimateRelationship relationship) {
                if(isNewLocalRelationship(relationship)) {
                    rememberKnownRelationship(relationship);
                    rememberSubmittedRelationship(relationship);
                    String relId = relationship.getId();
                    if(relId != null && !relId.isBlank() && pendingConnectionRelationshipIds.contains(relId)) {
                        ArchimeshPlugin.logTrace("CreateRelationshipInFolder deferred for pending connection retry relId=" + relId);
                        return;
                    }
                    send(opMapper.toCreateRelationshipInFolderSubmitOps(
                            relationship,
                            folder,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "CreateRelationshipInFolder");
                    return;
                }
                send(opMapper.toMoveRelationshipToFolderSubmitOps(
                        relationship,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveRelationshipToFolder");
                return;
            }
            if(newValue instanceof IArchimateDiagramModel view) {
                if(isNewLocalView(view)) {
                    rememberKnownView(view);
                    rememberSubmittedView(view);
                    send(opMapper.toCreateViewInFolderSubmitOps(
                            view,
                            folder,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "CreateViewInFolder");
                    return;
                }
                send(opMapper.toMoveViewToFolderSubmitOps(
                        view,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveViewToFolder");
                return;
            }
        }
        if("children".equals(feature)
                && notifier instanceof IDiagramModelArchimateObject parentViewObject
                && newValue instanceof IDiagramModelArchimateObject childViewObject) {
            send(opMapper.toAddViewObjectChildMemberSubmitOps(
                    "vo:" + parentViewObject.getId(),
                    "vo:" + childViewObject.getId(),
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "AddViewObjectChildMember");
            return;
        }

        if(newValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId != null && property.getKey() != null && !property.getKey().isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        property.getKey(),
                        property.getValue(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(add)");
            }
            return;
        }

        if(newValue instanceof IArchimateElement element) {
            if(hasSubmittedElement(element)) {
                return;
            }
            send(opMapper.toCreateElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateElement");
            rememberKnownElement(element);
            rememberSubmittedElement(element);
            return;
        }
        if(newValue instanceof IArchimateRelationship relationship) {
            if(hasSubmittedRelationship(relationship)) {
                return;
            }
            rememberKnownRelationship(relationship);
            rememberSubmittedRelationship(relationship);
            String relId = relationship.getId();
            if(relId != null && !relId.isBlank() && pendingConnectionRelationshipIds.contains(relId)) {
                // Defer: a pending connection-create retry will emit this relationship as
                // part of a paired (CreateRelationship + CreateConnection) batch, which
                // satisfies server same-batch precondition checks.
                ArchimeshPlugin.logTrace("CreateRelationship deferred for pending connection retry relId=" + relId);
                return;
            }
            send(opMapper.toCreateRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateRelationship");
            return;
        }
        if(newValue instanceof IArchimateDiagramModel view) {
            if(hasSubmittedView(view)) {
                return;
            }
            send(opMapper.toCreateViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateView");
            rememberKnownView(view);
            rememberSubmittedView(view);
            return;
        }
        if(newValue instanceof IDiagramModelArchimateObject viewObject) {
            trySendViewObjectCreate(viewObject, "add");
            return;
        }
        if(newValue instanceof IDiagramModelArchimateConnection connection) {
            trySendConnectionCreate(connection, "add");
            return;
        }
        ArchimeshPlugin.logTrace("ADD ignored: no mapping for value type " + className(newValue));
    }

    private void handleRemove(Notification notification, Object oldValue) {
        String feature = featureName(notification);
        Object notifier = notification.getNotifier();
        if("folders".equals(feature) && oldValue instanceof IFolder folder) {
            handleFolderRemoved(folder);
            return;
        }
        if("children".equals(feature)
                && notifier instanceof IDiagramModelArchimateObject parentViewObject
                && oldValue instanceof IDiagramModelArchimateObject childViewObject) {
            send(opMapper.toRemoveViewObjectChildMemberSubmitOps(
                    "vo:" + parentViewObject.getId(),
                    "vo:" + childViewObject.getId(),
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "RemoveViewObjectChildMember");
            deferViewObjectDeletion(childViewObject);
            return;
        }

        if("children".equals(feature)
                && notifier instanceof IDiagramModelContainer container
                && oldValue instanceof IDiagramModelArchimateObject childViewObject) {
            EObject owner = container instanceof EObject eo ? eo : null;
            EObject parentObject = owner != null && owner.eContainer() instanceof EObject parent ? parent : null;
            if(parentObject instanceof IDiagramModelArchimateObject parentViewObject) {
                send(opMapper.toRemoveViewObjectChildMemberSubmitOps(
                        "vo:" + parentViewObject.getId(),
                        "vo:" + childViewObject.getId(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "RemoveViewObjectChildMember(container)");
                deferViewObjectDeletion(childViewObject);
                return;
            }
        }

        if(oldValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            String key = opMapper.key(property);
            if(targetId != null && key != null && !key.isBlank()) {
                send(opMapper.toUnsetPropertySubmitOps(
                        targetId,
                        key,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "UnsetProperty(remove)");
            }
            return;
        }

        if(oldValue instanceof IArchimateElement element) {
            forgetKnownElement(element);
            forgetSubmittedElement(element);
            send(opMapper.toDeleteElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteElement");
            return;
        }
        if(oldValue instanceof IArchimateRelationship relationship) {
            forgetKnownRelationship(relationship);
            forgetSubmittedRelationship(relationship);
            String relId = relationship.getId();
            if(relId != null && !relId.isBlank()) {
                pendingConnectionRelationshipIds.remove(relId);
            }
            send(opMapper.toDeleteRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteRelationship");
            return;
        }
        if(oldValue instanceof IArchimateDiagramModel view) {
            forgetKnownView(view);
            forgetSubmittedView(view);
            send(opMapper.toDeleteViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteView");
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateObject viewObject) {
            deferViewObjectDeletion(viewObject);
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateConnection connection) {
            clearPendingConnectionCreate("conn-create:" + connection.getId());
            cleanupPendingConnectionRelationship(connection);
            cancelPending("conn:" + connection.getId());
            forgetSubmittedConnection(connection);
            send(opMapper.toDeleteConnectionSubmitOps(
                    connection,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteConnection");
            return;
        }
        ArchimeshPlugin.logTrace("REMOVE ignored: no mapping for value type " + className(oldValue));
    }

    private void handleSet(Notification notification) {
        Object notifier = notification.getNotifier();
        String featureName = featureName(notification);

        if(notifier instanceof IArchimateRelationship relationship) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            boolean includeEndpoints = "source".equals(featureName) || "target".equals(featureName);
            if(includeName || includeDocumentation || includeEndpoints) {
                send(opMapper.toUpdateRelationshipSubmitOps(
                        relationship,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation,
                        includeEndpoints),
                        "UpdateRelationship");
                if(includeEndpoints) {
                    for(IDiagramModelArchimateComponent component : relationship.getReferencingDiagramComponents()) {
                        if(component instanceof IDiagramModelArchimateConnection connection) {
                            trySendConnectionCreate(connection, "relationship-endpoint-set");
                        }
                    }
                }
            } else {
                ArchimeshPlugin.logTrace("SET ignored for relationship feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IArchimateElement element) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateElementSubmitOps(
                        element,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateElement");
            } else {
                ArchimeshPlugin.logTrace("SET ignored for element feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IFolder folder) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateFolderSubmitOps(
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateFolder");
            } else {
                ArchimeshPlugin.logTrace("SET ignored for folder feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModel view) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateViewSubmitOps(
                        view,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateView");
                return;
            }
            if("viewpoint".equals(featureName) && notifier instanceof IArchimateDiagramModel archimateView && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "viewpoint",
                        archimateView.getViewpoint(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(viewpoint)");
                return;
            }
            if("connectionRouterType".equals(featureName) && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "connectionRouterType",
                        view.getConnectionRouterType(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(connectionRouterType)");
            } else {
                ArchimeshPlugin.logTrace("SET ignored for view feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModelArchimateObject viewObject) {
            // Notation edits are noisy; debounce into one opaque update
            scheduleNotationUpdate("vo:" + viewObject.getId(),
                    () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                            viewObject,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateViewObjectOpaque"));
            return;
        }

        if(notifier instanceof IDiagramModelArchimateConnection connection) {
            if("source".equals(featureName)
                    || "target".equals(featureName)
                    || "archimateConcept".equals(featureName)
                    || "archimateRelationship".equals(featureName)) {
                trySendConnectionCreate(connection, "connection-set-" + featureName);
            }
            scheduleNotationUpdate("conn:" + connection.getId(),
                    () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                            connection,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateConnectionOpaque"));
            return;
        }

        if(notifier instanceof IBounds bounds) {
            EObject container = bounds.eContainer();
            if(container instanceof IDiagramModelArchimateObject viewObject) {
                scheduleNotationUpdate("vo:" + viewObject.getId(),
                        () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                                viewObject,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateViewObjectOpaque(bounds)"));
            }
            return;
        }

        if(notifier instanceof IDiagramModelBendpoint bendpoint) {
            EObject container = bendpoint.eContainer();
            if(container instanceof IDiagramModelConnection dmc && dmc instanceof IDiagramModelArchimateConnection connection) {
                scheduleNotationUpdate("conn:" + connection.getId(),
                        () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                                connection,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateConnectionOpaque(bendpoints)"));
            }
            return;
        }

        if(notifier instanceof IProperty property) {
            Object owner = property.eContainer();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId == null) {
                ArchimeshPlugin.logTrace("SET property ignored: could not resolve owner targetId");
                return;
            }
            String feature = featureName(notification);
            if("key".equals(feature)) {
                Object oldKey = notification.getOldValue();
                if(oldKey instanceof String oldKeyText && !oldKeyText.isBlank()) {
                    send(opMapper.toUnsetPropertySubmitOps(
                            targetId,
                            oldKeyText,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UnsetProperty(key-changed)");
                }
            }
            String key = opMapper.key(property);
            if(key != null && !key.isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        key,
                        opMapper.value(property),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(update)");
            } else {
                ArchimeshPlugin.logTrace("SET property ignored: key is blank");
            }
            return;
        }
        ArchimeshPlugin.logTrace("SET ignored: no mapping for notifier type " + className(notifier) + " feature=" + featureName);
    }

    private String featureName(Notification notification) {
        Object feature = notification.getFeature();
        if(feature instanceof EStructuralFeature structuralFeature) {
            return structuralFeature.getName();
        }
        return "";
    }

    private void send(String submitOpsJson, String opLabel) {
        if(submitOpsJson == null || submitOpsJson.isBlank()) {
            ArchimeshPlugin.logTrace("Submit skipped for opLabel=" + opLabel + " because mapped payload is empty");
            return;
        }
        sessionManager.sendSubmitOps(submitOpsJson);
        ArchimeshPlugin.logInfo("Submitted " + summarizeSubmitOps(submitOpsJson, opLabel) + " from local EMF capture");
    }

    private String summarizeSubmitOps(String submitOpsJson, String fallbackLabel) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submitOpsJson, "payload"));
        if(payload == null) {
            return fallbackLabel;
        }
        String opBatchId = SimpleJson.readStringField(payload, "opBatchId");
        var ops = SimpleJson.readArrayObjectElements(payload, "ops");
        if(ops.isEmpty()) {
            return fallbackLabel + " opBatchId=" + opBatchId;
        }
        if(ops.size() == 1) {
            return summarizeOp(ops.get(0), fallbackLabel, opBatchId);
        }
        return fallbackLabel + " opBatchId=" + opBatchId + " ops=" + summarizeBatchOps(ops);
    }

    private String summarizeBatchOps(java.util.List<String> ops) {
        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < ops.size(); i++) {
            if(i > 0) {
                out.append(", ");
            }
            String type = SimpleJson.readStringField(ops.get(i), "type");
            out.append(type == null ? "unknown" : type);
        }
        out.append(']');
        return out.toString();
    }

    private String summarizeOp(String opJson, String fallbackLabel, String opBatchId) {
        String type = SimpleJson.readStringField(opJson, "type");
        if(type == null || type.isBlank()) {
            return fallbackLabel + " opBatchId=" + opBatchId;
        }
        return switch(type) {
            case "MoveElementToFolder" -> type + " elementId=" + SimpleJson.readStringField(opJson, "elementId")
                    + " folderId=" + SimpleJson.readStringField(opJson, "folderId")
                    + " opBatchId=" + opBatchId;
            case "MoveRelationshipToFolder" -> type + " relationshipId=" + SimpleJson.readStringField(opJson, "relationshipId")
                    + " folderId=" + SimpleJson.readStringField(opJson, "folderId")
                    + " opBatchId=" + opBatchId;
            case "MoveViewToFolder" -> type + " viewId=" + SimpleJson.readStringField(opJson, "viewId")
                    + " folderId=" + SimpleJson.readStringField(opJson, "folderId")
                    + " opBatchId=" + opBatchId;
            case "CreateFolder" -> {
                String folder = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "folder"));
                yield type + " folderId=" + SimpleJson.readStringField(folder, "id")
                        + " parentFolderId=" + SimpleJson.readStringField(folder, "parentFolderId")
                        + " opBatchId=" + opBatchId;
            }
            case "MoveFolder" -> type + " folderId=" + SimpleJson.readStringField(opJson, "folderId")
                    + " parentFolderId=" + SimpleJson.readStringField(opJson, "parentFolderId")
                    + " opBatchId=" + opBatchId;
            case "CreateViewObject" -> {
                String viewObject = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "viewObject"));
                yield type + " viewObjectId=" + SimpleJson.readStringField(viewObject, "id")
                        + " viewId=" + SimpleJson.readStringField(viewObject, "viewId")
                        + " representsId=" + SimpleJson.readStringField(viewObject, "representsId")
                        + " opBatchId=" + opBatchId;
            }
            case "CreateElement" -> {
                String element = SimpleJson.asJsonObject(SimpleJson.readRawField(opJson, "element"));
                yield type + " elementId=" + SimpleJson.readStringField(element, "id")
                        + " opBatchId=" + opBatchId;
            }
            default -> type + " opBatchId=" + opBatchId;
        };
    }

    private void trySendViewObjectCreate(IDiagramModelArchimateObject viewObject, String reason) {
        if(viewObject == null) {
            return;
        }
        String id = viewObject.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        if(!isViewObjectCreateReady(viewObject)) {
            ArchimeshPlugin.logTrace("CreateViewObject deferred: incomplete ids reason=" + reason + " voId=" + id);
            scheduleViewObjectCreateRetry(viewObject, reason);
            return;
        }
        clearPendingViewObjectCreate("vo-create:" + id);
        send(mapViewObjectCreateSubmitOps(viewObject), "CreateViewObject");
    }

    private String mapViewObjectCreateSubmitOps(IDiagramModelArchimateObject viewObject) {
        IArchimateElement element = viewObject == null ? null : viewObject.getArchimateElement();
        if(element != null && isNewLocalElement(element)) {
            rememberKnownElement(element);
            rememberSubmittedElement(element);
            return opMapper.toCreateViewObjectWithElementSubmitOps(
                    viewObject,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId());
        }
        return opMapper.toCreateViewObjectSubmitOps(
                viewObject,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId());
    }

    private void scheduleViewObjectCreateRetry(IDiagramModelArchimateObject viewObject, String reason) {
        String id = viewObject.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        String key = "vo-create:" + id;
        if(pendingViewObjectCreates.containsKey(key)) {
            return;
        }
        int attempt = pendingViewObjectCreateAttempts.merge(key, 1, Integer::sum);
        if(attempt > VIEW_OBJECT_CREATE_MAX_RETRIES) {
            clearPendingViewObjectCreate(key);
            ArchimeshPlugin.logInfo("CreateViewObject retry dropped after max attempts voId=" + id + " reason=" + reason);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingViewObjectCreates.remove(key);
            trySendViewObjectCreate(viewObject, reason + "-retry-" + attempt);
        }, VIEW_OBJECT_CREATE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        pendingViewObjectCreates.put(key, future);
    }

    private void clearPendingViewObjectCreate(String key) {
        ScheduledFuture<?> previous = pendingViewObjectCreates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
        pendingViewObjectCreateAttempts.remove(key);
    }

    private boolean isViewObjectCreateReady(IDiagramModelArchimateObject viewObject) {
        return hasId(viewObject)
                && hasId(viewObject.getDiagramModel())
                && hasId(viewObject.getArchimateElement());
    }

    private void trySendConnectionCreate(IDiagramModelArchimateConnection connection, String reason) {
        if(connection == null) {
            return;
        }
        String id = connection.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        if(hasSubmittedConnection(connection)) {
            clearPendingConnectionCreate("conn-create:" + id);
            cleanupPendingConnectionRelationship(connection);
            return;
        }
        if(!isConnectionCreateReady(connection)) {
            ArchimeshPlugin.logTrace("CreateConnection(+Relationship) deferred: incomplete ids reason=" + reason + " connId=" + id);
            scheduleConnectionCreateRetry(connection, reason);
            return;
        }
        clearPendingConnectionCreate("conn-create:" + id);
        cleanupPendingConnectionRelationship(connection);
        String submitJson = mapConnectionCreateSubmitOps(connection);
        send(submitJson, "CreateConnection");
        if(submitJson != null && !submitJson.isBlank()) {
            rememberSubmittedConnection(connection);
        }
    }

    private void cleanupPendingConnectionRelationship(IDiagramModelArchimateConnection connection) {
        if(connection == null) {
            return;
        }
        IArchimateRelationship relationship = connection.getArchimateRelationship();
        if(relationship != null && relationship.getId() != null) {
            pendingConnectionRelationshipIds.remove(relationship.getId());
        }
    }

    private String mapConnectionCreateSubmitOps(IDiagramModelArchimateConnection connection) {
        // Always emit the paired (CreateRelationship + CreateConnection) batch.
        // Even when the relationship was already submitted standalone, the server
        // treats duplicate CreateRelationship as idempotent (MERGE in Cypher) and
        // same-batch references satisfy the CreateConnection representsId precondition
        // without depending on the relationship having been persisted to materialized state.
        return opMapper.toCreateConnectionWithRelationshipSubmitOps(
                connection,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId());
    }

    private void scheduleConnectionCreateRetry(IDiagramModelArchimateConnection connection, String reason) {
        String id = connection.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        String key = "conn-create:" + id;
        if(pendingConnectionCreates.containsKey(key)) {
            return;
        }
        int attempt = pendingConnectionCreateAttempts.merge(key, 1, Integer::sum);
        if(attempt > CONNECTION_CREATE_MAX_RETRIES) {
            clearPendingConnectionCreate(key);
            // Retries exhausted: remove from pending set and fall back to standalone relationship
            IArchimateRelationship relationship = connection.getArchimateRelationship();
            if(relationship != null && relationship.getId() != null) {
                String relId = relationship.getId();
                if(pendingConnectionRelationshipIds.remove(relId)) {
                    ArchimeshPlugin.logInfo("CreateConnection retries exhausted connId=" + id
                            + " relId=" + relId + " reason=" + reason
                            + " — submitting relationship standalone, connection is orphaned");
                    rememberKnownRelationship(relationship);
                    rememberSubmittedRelationship(relationship);
                    send(opMapper.toCreateRelationshipSubmitOps(
                            relationship,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "CreateRelationship(fallback-after-connection-retry-exhausted)");
                }
            }
            return;
        }

        // Register the relationship as pending so handleAdd skips standalone submission
        if(attempt == 1) {
            IArchimateRelationship relationship = connection.getArchimateRelationship();
            if(relationship != null && relationship.getId() != null) {
                pendingConnectionRelationshipIds.add(relationship.getId());
            }
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingConnectionCreates.remove(key);
            trySendConnectionCreate(connection, reason + "-retry-" + attempt);
        }, CONNECTION_CREATE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        pendingConnectionCreates.put(key, future);
    }

    private void clearPendingConnectionCreate(String key) {
        ScheduledFuture<?> previous = pendingConnectionCreates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
        pendingConnectionCreateAttempts.remove(key);
    }

    private boolean isConnectionCreateReady(IDiagramModelArchimateConnection connection) {
        if(!hasId(connection)) {
            return false;
        }
        if(!hasId(connection.getDiagramModel())) {
            return false;
        }
        if(!hasId(asIdentifier(connection.getSource()))) {
            return false;
        }
        if(!hasId(asIdentifier(connection.getTarget()))) {
            return false;
        }
        var relationship = connection.getArchimateRelationship();
        if(!hasId(relationship)) {
            return false;
        }
        if(!hasId(relationship.getSource())) {
            return false;
        }
        if(!hasId(relationship.getTarget())) {
            return false;
        }
        return true;
    }

    private boolean hasId(IIdentifier value) {
        return value != null && value.getId() != null && !value.getId().isBlank();
    }

    private IIdentifier asIdentifier(Object value) {
        return value instanceof IIdentifier identifier ? identifier : null;
    }

    private void rememberKnownElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            knownElementIds.add(element.getId());
        }
    }

    private void forgetKnownElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            knownElementIds.remove(element.getId());
        }
    }

    private void rememberSubmittedElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            submittedElementIds.add(element.getId());
        }
    }

    private void forgetSubmittedElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            submittedElementIds.remove(element.getId());
        }
    }

    private boolean hasSubmittedElement(IArchimateElement element) {
        return element != null
                && element.getId() != null
                && !element.getId().isBlank()
                && submittedElementIds.contains(element.getId());
    }

    private boolean isNewLocalElement(IArchimateElement element) {
        return element != null
                && element.getId() != null
                && !element.getId().isBlank()
                && !knownElementIds.contains(element.getId());
    }

    private void rememberKnownRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            knownRelationshipIds.add(relationship.getId());
        }
    }

    private void forgetKnownRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            knownRelationshipIds.remove(relationship.getId());
        }
    }

    private boolean isNewLocalRelationship(IArchimateRelationship relationship) {
        return relationship != null
                && relationship.getId() != null
                && !relationship.getId().isBlank()
                && !knownRelationshipIds.contains(relationship.getId());
    }

    private void rememberKnownView(IArchimateDiagramModel view) {
        if(view != null && view.getId() != null && !view.getId().isBlank()) {
            knownViewIds.add(view.getId());
        }
    }

    private void forgetKnownView(IArchimateDiagramModel view) {
        if(view != null && view.getId() != null && !view.getId().isBlank()) {
            knownViewIds.remove(view.getId());
        }
    }

    private void rememberSubmittedView(IArchimateDiagramModel view) {
        if(view != null && view.getId() != null && !view.getId().isBlank()) {
            submittedViewIds.add(view.getId());
        }
    }

    private void forgetSubmittedView(IArchimateDiagramModel view) {
        if(view != null && view.getId() != null && !view.getId().isBlank()) {
            submittedViewIds.remove(view.getId());
        }
    }

    private boolean hasSubmittedView(IArchimateDiagramModel view) {
        return view != null
                && view.getId() != null
                && !view.getId().isBlank()
                && submittedViewIds.contains(view.getId());
    }

    private boolean isNewLocalView(IArchimateDiagramModel view) {
        return view != null
                && view.getId() != null
                && !view.getId().isBlank()
                && !knownViewIds.contains(view.getId());
    }

    private void rememberSubmittedRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            submittedRelationshipIds.add(relationship.getId());
        }
    }

    private void forgetSubmittedRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            submittedRelationshipIds.remove(relationship.getId());
        }
    }

    private boolean hasSubmittedRelationship(IArchimateRelationship relationship) {
        return relationship != null
                && relationship.getId() != null
                && !relationship.getId().isBlank()
                && submittedRelationshipIds.contains(relationship.getId());
    }

    private void rememberSubmittedConnection(IDiagramModelArchimateConnection connection) {
        if(connection != null && connection.getId() != null && !connection.getId().isBlank()) {
            submittedConnectionIds.add(connection.getId());
        }
    }

    private void forgetSubmittedConnection(IDiagramModelArchimateConnection connection) {
        if(connection != null && connection.getId() != null && !connection.getId().isBlank()) {
            submittedConnectionIds.remove(connection.getId());
        }
    }

    private boolean hasSubmittedConnection(IDiagramModelArchimateConnection connection) {
        return connection != null
                && connection.getId() != null
                && !connection.getId().isBlank()
                && submittedConnectionIds.contains(connection.getId());
    }

    private void scheduleNotationUpdate(String key, Runnable task) {
        cancelPending(key);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            }
            finally {
                pendingNotationUpdates.remove(key);
            }
        }, NOTATION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingNotationUpdates.put(key, future);
    }

    private void cancelPending(String key) {
        ScheduledFuture<?> previous = pendingNotationUpdates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
    }

    private void deferViewObjectDeletion(IDiagramModelArchimateObject object) {
        if(!pendingViewObjectRemovals.add(object)) return;
        String modelId = sessionManager.getCurrentModelId();
        IArchimateModel model = sessionManager.getAttachedModel();
        deferAfterLocalChange(() -> {
            pendingViewObjectRemovals.remove(object);
            // REMOVE is also emitted by reparenting. Inspect the completed local change,
            // including when a nested-connections dialog runs a modal SWT event loop.
            if(closed || object.getDiagramModel() != null
                    || !java.util.Objects.equals(modelId, sessionManager.getCurrentModelId())
                    || model != sessionManager.getAttachedModel()) return;
            cancelPending("vo:" + object.getId());
            send(opMapper.toDeleteViewObjectSubmitOps(object, modelId,
                    sessionManager.getLastKnownRevision(), sessionManager.getUserId(), sessionManager.getSessionId()),
                    "DeleteViewObject");
        });
    }

    protected void deferAfterLocalChange(Runnable task) {
        if(ArchimeshPlugin.getInstance() == null) { task.run(); return; }
        var display = org.eclipse.swt.widgets.Display.getDefault();
        if(display != null && !display.isDisposed()) display.asyncExec(task);
    }

    public void close() {
        closed = true;
        pendingViewObjectRemovals.clear();
        pendingNotationUpdates.values().forEach(f -> f.cancel(false));
        pendingNotationUpdates.clear();
        pendingConnectionCreates.values().forEach(f -> f.cancel(false));
        pendingConnectionCreates.clear();
        pendingConnectionCreateAttempts.clear();
        pendingViewObjectCreates.values().forEach(f -> f.cancel(false));
        pendingViewObjectCreates.clear();
        pendingViewObjectCreateAttempts.clear();
        knownElementIds.clear();
        knownRelationshipIds.clear();
        knownViewIds.clear();
        submittedElementIds.clear();
        submittedRelationshipIds.clear();
        submittedViewIds.clear();
        submittedConnectionIds.clear();
        pendingConnectionRelationshipIds.clear();
        scheduler.shutdownNow();
    }

    private String eventTypeName(int eventType) {
        return switch(eventType) {
            case Notification.ADD -> "ADD";
            case Notification.ADD_MANY -> "ADD_MANY";
            case Notification.REMOVE -> "REMOVE";
            case Notification.REMOVE_MANY -> "REMOVE_MANY";
            case Notification.SET -> "SET";
            default -> String.valueOf(eventType);
        };
    }

    private String className(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }

    private void handleFolderAdded(Object notifier, IFolder folder) {
        if(folder == null || isRootFolder(folder)) {
            return;
        }
        String folderId = opMapper.folderId(folder);
        if(folderId == null || folderId.isBlank()) {
            return;
        }
        String parentFolderId = resolveParentFolderId(notifier, folder);
        if(knownFolderIds.add(folderId)) {
            send(opMapper.toCreateFolderSubmitOps(
                    folder,
                    parentFolderId,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateFolder");
            return;
        }
        send(opMapper.toMoveFolderSubmitOps(
                folder,
                parentFolderId,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId()),
                "MoveFolder");
    }

    private void handleFolderRemoved(IFolder folder) {
        if(folder == null || isRootFolder(folder)) {
            return;
        }
        if(folder.eContainer() instanceof IFolder || folder.eContainer() instanceof IArchimateModel) {
            return;
        }
        knownFolderIds.remove(opMapper.folderId(folder));
        send(opMapper.toDeleteFolderSubmitOps(
                folder,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId()),
                "DeleteFolder");
    }

    private String resolveParentFolderId(Object notifier, IFolder folder) {
        if(notifier instanceof IFolder parentFolder) {
            return opMapper.folderId(parentFolder);
        }
        if(notifier instanceof IArchimateModel) {
            return null;
        }
        return opMapper.parentFolderId(folder);
    }

    private boolean isRootFolder(IFolder folder) {
        return folder != null
                && folder.getType() != null
                && folder.getType() != FolderType.USER
                && folder.eContainer() instanceof IArchimateModel;
    }

    private void seedKnownFolderIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IFolder folder && !isRootFolder(folder)) {
                knownFolderIds.add(opMapper.folderId(folder));
            }
        }
    }

    private void seedKnownElementIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IArchimateElement element && element.getId() != null && !element.getId().isBlank()) {
                knownElementIds.add(element.getId());
            }
        }
    }

    private void seedKnownRelationshipIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IArchimateRelationship relationship
                    && relationship.getId() != null
                    && !relationship.getId().isBlank()) {
                knownRelationshipIds.add(relationship.getId());
            }
        }
    }

    private void seedKnownViewIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IArchimateDiagramModel view && view.getId() != null && !view.getId().isBlank()) {
                knownViewIds.add(view.getId());
            }
        }
    }
}
