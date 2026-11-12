package org.gautelis.archimesh.plugin.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ui.ArchimeshConnectDialog;
import org.gautelis.archimesh.plugin.util.ClientConnectionSettings;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Switches the active local model to server-backed Archimesh mode.
 */
public class SwitchActiveModelServerBackedHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        IArchimateModel model = getActiveModel(event);
        if(model == null) {
            ArchimeshPlugin.logInfo("Switch active model skipped: no active model found");
            return null;
        }

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        String defaultModelId = sessionManager.getCurrentModelId();
        if((defaultModelId == null || defaultModelId.isBlank()) && model instanceof IIdentifier identifier) {
            defaultModelId = identifier.getId();
        }

        ArchimeshConnectDialog dialog = new ArchimeshConnectDialog(
                HandlerUtil.getActiveShell(event),
                ClientConnectionSettings.wsBaseUrl(sessionManager.getCurrentWsBaseUrl()),
                defaultModelId,
                sessionManager.getUserId(),
                sessionManager.getSessionId(),
                sessionManager.getAuthToken());
        if(dialog.open() != Window.OK) {
            return null;
        }

        sessionManager.setActor(dialog.getUserId(), dialog.getSessionId());
        sessionManager.setAuthToken(dialog.getAuthToken());
        sessionManager.setServerBackedSession(true);
        // Attaching Archimesh to an existing local model must always bootstrap from a
        // fresh server snapshot. Rejoining from cached revision state can leave stale or
        // extra local content in place until a manual resynchronization is triggered.
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId(), true);
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            ArchimeshPlugin.logInfo("Active model switched to server-backed archimesh modelId=" + dialog.getModelId());
        }
        else {
            ArchimeshPlugin.logInfo("Failed switching active model to server-backed modelId=" + dialog.getModelId());
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Switch To Archimesh Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The active model could not be switched to the archimesh server."
                            : sessionManager.getLastUserHint());
        }
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        return plugin != null && plugin.getSessionManager() != null;
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
