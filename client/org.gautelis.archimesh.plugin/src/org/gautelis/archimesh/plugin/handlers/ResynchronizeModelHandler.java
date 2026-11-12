package org.gautelis.archimesh.plugin.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Forces a cold snapshot rebuild for the active attached model.
 */
public class ResynchronizeModelHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        IArchimateModel model = getActiveModel(event);
        if(model == null) {
            model = sessionManager.getAttachedModel();
        }
        if(model == null) {
            MessageDialog.openInformation(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model",
                    "No active Archimesh model is attached.");
            return null;
        }

        String wsBaseUrl = sessionManager.getCurrentWsBaseUrl();
        String modelId = sessionManager.getCurrentModelId();
        String modelRef = sessionManager.getCurrentModelRef();
        if(wsBaseUrl == null || wsBaseUrl.isBlank() || modelId == null || modelId.isBlank()) {
            MessageDialog.openInformation(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model",
                    "The active model is not connected to a Archimesh server.");
            return null;
        }

        boolean confirmed = MessageDialog.openConfirm(
                HandlerUtil.getActiveShell(event),
                "Resynchronize Model",
                "This will clear the local Archimesh projection and pull a fresh snapshot from the server.\n\nContinue?");
        if(!confirmed) {
            return null;
        }

        sessionManager.connect(wsBaseUrl, modelId, modelRef, true);
        if(!sessionManager.isConnected()) {
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The Archimesh connection could not be re-established."
                            : sessionManager.getLastUserHint());
            return null;
        }

        // The incoming snapshot replaces the local projection under RemoteApplyGuard.
        // Clearing here would capture local deletes and publish them to the server.
        sessionManager.attachModel(model);
        ArchimeshPlugin.logInfo("Forced model resynchronization modelId=" + modelId + " ref=" + modelRef);
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return false;
        }
        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        return sessionManager.isConnected() && sessionManager.getAttachedModel() != null;
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
