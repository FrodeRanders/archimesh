package org.gautelis.archimesh.plugin.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ui.EnterpriseRepositoryDialog;

public final class EnterpriseRepositoryHandler extends AbstractHandler {
    @Override public Object execute(ExecutionEvent event) throws ExecutionException {
        var plugin=ArchimeshPlugin.getInstance();
        if (!isEnabled() || plugin==null) return null;
        try { new EnterpriseRepositoryDialog(HandlerUtil.getActiveShell(event),plugin.getSessionManager()).open(); }
        catch (java.io.IOException invalid) { MessageDialog.openError(HandlerUtil.getActiveShell(event),"Enterprise Repository",invalid.getMessage()); }
        return null;
    }
    @Override public boolean isEnabled() {
        var plugin=ArchimeshPlugin.getInstance();
        if (plugin==null || plugin.getSessionManager()==null) return false;
        var session=plugin.getSessionManager();
        return session.isConnected() && session.getAttachedModel()!=null && !session.isCurrentReferenceReadOnly();
    }
}
