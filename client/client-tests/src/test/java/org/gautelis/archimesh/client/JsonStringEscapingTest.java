package org.gautelis.archimesh.client;

import com.archimatetool.model.IArchimateFactory;
import org.gautelis.archimesh.plugin.emf.OpMapper;
import org.gautelis.archimesh.plugin.notation.NotationSerializer;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonStringEscapingTest {
    private static final String TEXT = "First line\nSecond\r\n\t\"quoted\" C:\\demo Åäö ☃";

    @Test void everyJsonControlCharacterRoundTripsWithoutRawControls() {
        StringBuilder original = new StringBuilder(TEXT);
        for(char c=0; c<32; c++) original.append(c);
        String escaped = SimpleJson.escapeString(original.toString());
        assertTrue(escaped.chars().noneMatch(c -> c<32));
        assertEquals(original.toString(),SimpleJson.readStringField("{\"value\":\""+escaped+"\"}","value"));
    }

    @Test void diagramCreationPreservesMultilineConceptDocumentationAndActorIdentity() {
        var factory=IArchimateFactory.eINSTANCE;
        var element=factory.createApplicationComponent(); element.setId("app"); element.setName(TEXT); element.setDocumentation(TEXT);
        var view=factory.createArchimateDiagramModel(); view.setId("view");
        var object=factory.createDiagramModelArchimateObject(); object.setId("object"); object.setArchimateElement(element);
        view.getChildren().add(object);
        String json=new OpMapper().toCreateViewObjectWithElementSubmitOps(object,"demo",0,TEXT,TEXT);
        assertTrue(json.chars().noneMatch(c -> c<32),"Operation envelope must contain escaped text");
        String payload=SimpleJson.readRawField(json,"payload");
        String actor=SimpleJson.readRawField(payload,"actor");
        assertEquals(TEXT,SimpleJson.readStringField(actor,"userId"));
        var ops=SimpleJson.readArrayObjectElements(payload,"ops");
        assertEquals(2,ops.size());
        String concept=SimpleJson.readRawField(ops.getFirst(),"element");
        assertEquals(TEXT,SimpleJson.readStringField(concept,"documentation"));
        assertEquals(TEXT,SimpleJson.readStringField(concept,"name"));
    }

    @Test void notationTextUsesTheSameEscapingRules() {
        var object=IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        object.setFont(TEXT);
        String json=new NotationSerializer().serializeViewObject(object);
        assertTrue(json.chars().noneMatch(c -> c<32));
        assertEquals(TEXT,SimpleJson.readStringField(json,"font"));
    }

    @Test void legacyRepairChangesOnlyRawControlCharactersInsideStrings() {
        String json="{\n  \"id\":\"unchanged\",\"documentation\":\"First\nsecond\tline\\\\\nend\",\"escaped\":\"a\\n\\\"b\"\n}";
        String repaired=SimpleJson.repairUnescapedControlCharacters(json);
        assertEquals("First\nsecond\tline\\\nend",SimpleJson.readStringField(repaired,"documentation"));
        assertEquals("a\n\"b",SimpleJson.readStringField(repaired,"escaped"));
        assertTrue(repaired.startsWith("{\n"),"Pretty-print whitespace outside strings is preserved");
        assertEquals(repaired,SimpleJson.repairUnescapedControlCharacters(repaired));
        assertEquals("unchanged",SimpleJson.readStringField(repaired,"id"));
    }
}
