package org.gautelis.archimesh.plugin.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ui.CentralEditDialog;

public final class CentralEditHandler extends AbstractHandler {
    @Override public boolean isEnabled() { var plugin=ArchimeshPlugin.getInstance(); if (plugin==null || plugin.getSessionManager()==null) return false; var session=plugin.getSessionManager(); return session.isConnected() && session.getAttachedModel()!=null && !session.isCurrentReferenceReadOnly(); }
    @Override public Object execute(ExecutionEvent event) throws ExecutionException { var plugin=ArchimeshPlugin.getInstance(); if (!isEnabled() || plugin==null) return null; try { new CentralEditDialog(HandlerUtil.getActiveShell(event),plugin.getSessionManager()).open(); } catch (java.io.IOException failure) { MessageDialog.openError(HandlerUtil.getActiveShell(event),"Central change",failure.getMessage()); } return null; }
}
