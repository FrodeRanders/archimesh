package org.gautelis.archimesh.plugin.handlers;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ui.OpenServerModelDialog;
import org.gautelis.archimesh.plugin.util.ClientConnectionSettings;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Opens a Archimesh-backed model from server and connects immediately.
 */
public class OpenServerModelHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        OpenServerModelDialog dialog = new OpenServerModelDialog(
                HandlerUtil.getActiveShell(event),
                ClientConnectionSettings.wsBaseUrl(sessionManager.getCurrentWsBaseUrl()),
                sessionManager.getCurrentModelId(),
                "",
                sessionManager.getCurrentModelRef(),
                sessionManager.getUserId(),
                sessionManager.getSessionId(),
                sessionManager.getAuthToken());

        if(dialog.open() != Window.OK) {
            return null;
        }

        IArchimateModel model = findAlreadyOpenModel(dialog.getModelId(), dialog.getModelRef());
        boolean reusedExistingModel = model != null;
        if(model == null) {
            model = IArchimateFactory.eINSTANCE.createArchimateModel();
            model.setDefaults();
            if(model instanceof IIdentifier identifier) {
                identifier.setId(dialog.getModelId());
            }
            String modelName = dialog.getModelName();
            String effectiveName = modelName == null || modelName.isBlank()
                    ? "Archimesh: " + dialog.getModelId()
                    : modelName;
            if(!"HEAD".equalsIgnoreCase(dialog.getModelRef())) {
                effectiveName = effectiveName + " @" + dialog.getModelRef();
            }
            model.setName(effectiveName);
            IEditorModelManager.INSTANCE.openModel(model);
        }

        boolean alreadyConnectedSameModel = sessionManager.isConnected()
                && dialog.getWsBaseUrl().equals(sessionManager.getCurrentWsBaseUrl())
                && dialog.getModelId().equals(sessionManager.getCurrentModelId())
                && dialog.getModelRef().equalsIgnoreCase(sessionManager.getCurrentModelRef());
        if(alreadyConnectedSameModel && reusedExistingModel) {
            if(sessionManager.getAttachedModel() != model) {
                sessionManager.attachModel(model);
            }
            ArchimeshPlugin.logInfo("Open Archimesh model skipped reconnect: already connected modelId=" + dialog.getModelId());
            return null;
        }

        sessionManager.setActor(dialog.getUserId(), dialog.getSessionId());
        sessionManager.setAuthToken(dialog.getAuthToken());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId(), dialog.getModelRef(), true);
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            if(reusedExistingModel) {
                ArchimeshPlugin.logInfo("Reused already-open Archimesh model modelId=" + dialog.getModelId());
            }
            ArchimeshPlugin.logInfo("Opened Archimesh model from server modelId=" + dialog.getModelId()
                    + " ref=" + dialog.getModelRef());
        }
        else {
            ArchimeshPlugin.logInfo("Failed to open Archimesh model from server modelId=" + dialog.getModelId()
                    + " ref=" + dialog.getModelRef());
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Open Archimesh Model Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The Archimesh model could not be opened from the server."
                            : sessionManager.getLastUserHint());
        }

        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        return plugin != null && plugin.getSessionManager() != null;
    }

    private IArchimateModel findAlreadyOpenModel(String modelId, String modelRef) {
        File expectedCacheFile = expectedCacheFile(modelId, modelRef);
        for(IArchimateModel candidate : IEditorModelManager.INSTANCE.getModels()) {
            if(candidate == null) {
                continue;
            }
            if(expectedCacheFile != null && candidate.getFile() != null
                    && expectedCacheFile.equals(candidate.getFile())) {
                return candidate;
            }
            if("HEAD".equalsIgnoreCase(modelRef)
                    && candidate instanceof IIdentifier identifier
                    && modelId.equals(identifier.getId())
                    && candidate.getFile() == null) {
                return candidate;
            }
        }
        return null;
    }

    private File expectedCacheFile(String modelId, String modelRef) {
        String cacheKey = (modelId == null ? "" : modelId) + "__" + ((modelRef == null || modelRef.isBlank()) ? "HEAD" : modelRef.trim());
        String safeCacheKey = cacheKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        if(safeCacheKey.isBlank()) {
            return null;
        }
        String userHome = System.getProperty("user.home", "");
        if(userHome.isBlank()) {
            return null;
        }
        return Path.of(userHome, "Archi", "archimesh-cache", safeCacheKey + ".archimate").toFile();
    }
}
