package org.gautelis.archimesh.plugin.notation;

import java.util.Iterator;
import org.gautelis.archimesh.plugin.util.SimpleJson;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.INameable;

/**
 * Serializes diagram notation objects to canonical notationJson.
 */
public class NotationSerializer {

    public String serializeViewObject(Object viewObject) {
        if(!(viewObject instanceof IDiagramModelArchimateObject object)) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        IBounds bounds = object.getBounds();
        if(bounds != null) {
            json.append("\"x\":").append(bounds.getX()).append(",");
            json.append("\"y\":").append(bounds.getY()).append(",");
            json.append("\"width\":").append(bounds.getWidth()).append(",");
            json.append("\"height\":").append(bounds.getHeight()).append(",");
        }
        json.append("\"type\":").append(object.getType()).append(",");
        json.append("\"alpha\":").append(object.getAlpha()).append(",");
        json.append("\"lineAlpha\":").append(object.getLineAlpha()).append(",");
        json.append("\"lineWidth\":").append(object.getLineWidth()).append(",");
        json.append("\"lineStyle\":").append(object.getLineStyle()).append(",");
        json.append("\"textAlignment\":").append(object.getTextAlignment()).append(",");
        json.append("\"textPosition\":").append(object.getTextPosition()).append(",");
        json.append("\"gradient\":").append(object.getGradient()).append(",");
        json.append("\"iconVisibleState\":").append(object.getIconVisibleState()).append(",");
        json.append("\"deriveElementLineColor\":").append(object.getDeriveElementLineColor()).append(",");
        json.append("\"fillColor\":").append(jsonValue(object.getFillColor())).append(",");
        json.append("\"lineColor\":").append(jsonValue(object.getLineColor())).append(",");
        json.append("\"font\":").append(jsonValue(object.getFont())).append(",");
        json.append("\"fontColor\":").append(jsonValue(object.getFontColor())).append(",");
        json.append("\"iconColor\":").append(jsonValue(object.getIconColor())).append(",");
        if(object instanceof IIconic iconic) {
            json.append("\"imagePath\":").append(jsonValue(iconic.getImagePath())).append(",");
            json.append("\"imagePosition\":").append(iconic.getImagePosition()).append(",");
        }
        json.append("\"name\":").append(jsonValue(getName(object))).append(",");
        json.append("\"documentation\":").append(jsonValue(getDocumentation(object)));
        json.append("}");
        return json.toString();
    }

    public String serializeConnection(Object connection) {
        if(!(connection instanceof IDiagramModelArchimateConnection modelConnection)) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":").append(modelConnection.getType()).append(",");
        json.append("\"nameVisible\":").append(modelConnection.isNameVisible()).append(",");
        json.append("\"textAlignment\":").append(modelConnection.getTextAlignment()).append(",");
        json.append("\"textPosition\":").append(modelConnection.getTextPosition()).append(",");
        json.append("\"lineWidth\":").append(modelConnection.getLineWidth()).append(",");
        json.append("\"name\":").append(jsonValue(getName(modelConnection))).append(",");
        json.append("\"lineColor\":").append(jsonValue(modelConnection.getLineColor())).append(",");
        json.append("\"font\":").append(jsonValue(modelConnection.getFont())).append(",");
        json.append("\"fontColor\":").append(jsonValue(modelConnection.getFontColor())).append(",");
        json.append("\"documentation\":").append(jsonValue(getDocumentation(modelConnection))).append(",");
        json.append("\"bendpoints\":[");
        Iterator<IDiagramModelBendpoint> it = modelConnection.getBendpoints().iterator();
        while(it.hasNext()) {
            IDiagramModelBendpoint bendpoint = it.next();
            json.append("{")
                    .append("\"startX\":").append(bendpoint.getStartX()).append(",")
                    .append("\"startY\":").append(bendpoint.getStartY()).append(",")
                    .append("\"endX\":").append(bendpoint.getEndX()).append(",")
                    .append("\"endY\":").append(bendpoint.getEndY())
                    .append("}");
            if(it.hasNext()) {
                json.append(",");
            }
        }
        json.append("]");
        json.append("}");
        return json.toString();
    }

    private String getName(Object object) {
        return object instanceof INameable nameable ? nameable.getName() : "";
    }

    private String getDocumentation(Object object) {
        return object instanceof IDocumentable documentable ? documentable.getDocumentation() : "";
    }

    private String jsonValue(Object value) {
        if(value == null) {
            return "null";
        }
        if(value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String escape(String value) {
        return SimpleJson.escapeString(value);
    }
}
