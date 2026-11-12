import com.archimatetool.model.*;
import com.archimatetool.model.util.ArchimateResourceFactory;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.Diagnostician;

/** Uses the installed Archi model, not a reimplementation of its XML loader. */
public class ValidateArchi {
    public static void main(String[] args) throws Exception {
        IArchimatePackage.eINSTANCE.eClass();
        var set = ArchimateResourceFactory.createResourceSet();
        var resource = set.getResource(URI.createFileURI(Path.of(args[0]).toAbsolutePath().toString()), true);
        if (!resource.getErrors().isEmpty()) throw new IllegalStateException(resource.getErrors().toString());
        var model = (IArchimateModel) resource.getContents().getFirst();
        var elements = new ArrayList<IArchimateElement>();
        var relationships = new ArrayList<IArchimateRelationship>();
        var iterator = model.eAllContents();
        while (iterator.hasNext()) {
            var object = iterator.next();
            if (object instanceof IArchimateRelationship relationship) {
                if (relationship.getSource() == null || relationship.getTarget() == null || relationship.getSource().eIsProxy() || relationship.getTarget().eIsProxy())
                    throw new IllegalStateException("Unresolved relationship " + relationship.getId());
                relationships.add(relationship);
            } else if (object instanceof IArchimateElement element) elements.add(element);
        }
        var diagnostic = Diagnostician.INSTANCE.validate(model);
        if (diagnostic.getSeverity() >= 4) throw new IllegalStateException(diagnostic.toString());
        if (elements.size() != 26 || relationships.size() != 49)
            throw new IllegalStateException("Unexpected semantic graph size");
        if (args.length == 2) {
            var factory = IArchimateFactory.eINSTANCE;
            var view = factory.createArchimateDiagramModel();
            view.setId("northstar-synthesized-view-v1");
            view.setName("Synthesized order processing (demo)");
            view.setDocumentation("Derived from semantic relationships. Layout is disposable; the original fixture has no views.");
            model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
            var objects = new LinkedHashMap<String, IDiagramModelArchimateObject>();
            var rows = new HashMap<String, Integer>();
            for (var element : elements) {
                String type = element.eClass().getName();
                int row = switch(type) {
                    case "ApplicationComponent" -> 0;
                    case "ApplicationFunction" -> 1;
                    case "DataObject" -> 2;
                    case "ApplicationService" -> 3;
                    default -> -1;
                };
                if (row < 0) continue;
                if (type.equals("ApplicationComponent") && !element.getName().equals("Northstar Order Management") && !element.getName().equals("Northstar Trade Portal")) continue;
                int column = rows.merge(type,1,Integer::sum) - 1;
                var object = factory.createDiagramModelArchimateObject();
                object.setId("view-object-" + element.getId());
                object.setArchimateElement(element);
                object.setBounds(30 + 240 * column, 30 + row * 190, 200, 80);
                view.getChildren().add(object);
                objects.put(element.getId(),object);
            }
            int connectionCount = 0;
            for (var relationship : relationships) {
                var source = objects.get(relationship.getSource().getId());
                var target = objects.get(relationship.getTarget().getId());
                if (source == null || target == null) continue;
                var connection = factory.createDiagramModelArchimateConnection();
                connection.setId("view-connection-" + relationship.getId());
                connection.setArchimateRelationship(relationship);
                connection.connect(source,target);
                connectionCount++;
            }
            resource.setURI(URI.createFileURI(Path.of(args[1]).toAbsolutePath().toString()));
            resource.save(null);
            var reload = ArchimateResourceFactory.createResourceSet().getResource(resource.getURI(),true);
            if (!reload.getErrors().isEmpty()) throw new IllegalStateException(reload.getErrors().toString());
            System.out.println("Synthesized Archi view: " + objects.size() + " objects, " + connectionCount + " connections");
        }
        System.out.println("Archi EMF validation passed: " + elements.size() + " elements, " + relationships.size() + " relationships");
    }
}
