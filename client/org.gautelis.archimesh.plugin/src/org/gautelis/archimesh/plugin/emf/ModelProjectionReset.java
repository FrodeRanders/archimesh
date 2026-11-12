package org.gautelis.archimesh.plugin.emf;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;

/**
 * Clears the local Archi projection so a fresh server snapshot can rebuild it.
 */
public final class ModelProjectionReset {

    private ModelProjectionReset() {
    }

    public static void clear(IArchimateModel model) {
        if(model == null) {
            return;
        }
        RemoteApplyGuard.runAsRemoteApply(() -> clearInternal(model));
    }

    private static void clearInternal(IArchimateModel model) {
        List<EObject> views = new ArrayList<>();
        List<EObject> concepts = new ArrayList<>();

        for(var iter = model.eAllContents(); iter.hasNext();) {
            EObject object = iter.next();
            if(object instanceof IDiagramModel) {
                views.add(object);
            }
            else if(object instanceof IArchimateConcept) {
                concepts.add(object);
            }
        }

        for(EObject view : views) {
            if(view instanceof IDiagramModel diagramModel) {
                EditorManager.closeDiagramEditor(diagramModel);
            }
            EcoreUtil.delete(view, true);
        }

        for(EObject concept : concepts) {
            EcoreUtil.delete(concept, true);
        }

        List<IFolder> userFolders = new ArrayList<>();
        for(var iter = model.eAllContents(); iter.hasNext();) {
            EObject object = iter.next();
            if(object instanceof IFolder folder && folder.getType() == FolderType.USER) {
                userFolders.add(folder);
            }
        }
        for(IFolder folder : userFolders) {
            EcoreUtil.delete(folder, true);
        }
    }
}
