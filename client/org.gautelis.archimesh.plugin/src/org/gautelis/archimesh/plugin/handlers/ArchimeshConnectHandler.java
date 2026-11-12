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
 * Opens mesh connection dialog and connects websocket session.
 */
public class ArchimeshConnectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        IArchimateModel model = getActiveModel(event);

        String defaultModelId = sessionManager.getCurrentModelId();
        if((defaultModelId == null || defaultModelId.isBlank()) && model instanceof IIdentifier) {
            defaultModelId = ((IIdentifier)model).getId();
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
        // Attaching Archimesh to an arbitrary active model must start from a server snapshot.
        // Reusing cache revision metadata here can produce empty deltas against a non-matching local model.
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId(), true);
        if(model != null && sessionManager.isConnected()) {
            sessionManager.attachModel(model);
        }
        if(!sessionManager.isConnected()) {
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Archimesh Connection Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The archimesh connection could not be established."
                            : sessionManager.getLastUserHint());
        }

        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        return plugin != null
                && plugin.getSessionManager() != null
                && !plugin.getSessionManager().isConnected();
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
