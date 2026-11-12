package org.gautelis.federation.http;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static org.gautelis.federation.Identity.*;

/** Neutral read port. An observation is not a model operation, approval or target-write grant. */
public interface SourceReader {
    record Field(String aspect,String path) implements Comparable<Field> {
        public Field { text(aspect,512); text(path,512); JsonPointer.compile(path); if (!path.startsWith("/")) throw new IllegalArgumentException("Field requires a JSON pointer"); }
        @Override public int compareTo(Field other) { int result=aspect.compareTo(other.aspect); return result==0 ? path.compareTo(other.path) : result; }
    }
    enum Lifecycle { ACTIVE, SOFT_DELETED, UNKNOWN }
    record Value(boolean present,JsonNode value,String digest) {
        public Value { Objects.requireNonNull(value); value=value.deepCopy(); if (digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid field digest"); }
        @Override public JsonNode value() { return value.deepCopy(); }
        public static Value of(JsonNode node) {
            boolean present=!node.isMissingNode(); var value=present ? node : RepositoryJson.JSON.nullNode();
            var material=RepositoryJson.JSON.createObjectNode().put("present",present); material.set("value",value);
            return new Value(present,value,RepositoryJson.digest(material));
        }
    }
    record Snapshot(Representation representation,Lifecycle lifecycle,Map<Field,Value> fields,String revision,String digest) {
        public Snapshot { Objects.requireNonNull(representation); Objects.requireNonNull(lifecycle); fields=Collections.unmodifiableMap(new TreeMap<>(fields)); text(revision,512); if (!digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid snapshot digest"); }
        public SourceField source(Field field) { if (!fields.containsKey(field)) throw new IllegalArgumentException("Unobserved field"); return new SourceField(representation.resource(),field.aspect(),field.path()); }
    }
    Snapshot read(Representation representation,Set<Field> fields);
    static Snapshot snapshot(Representation representation,Lifecycle lifecycle,Map<Field,Value> fields) {
        var material=RepositoryJson.JSON.createObjectNode(); material.set("representation",RepositoryJson.JSON.valueToTree(representation));
        material.put("encoding","federation-observation-json-v1").put("lifecycle",lifecycle.name());
        var values=material.putArray("fields");
        new TreeMap<>(fields).forEach((field,value) -> { var row=values.addObject().put("aspect",field.aspect()).put("path",field.path()).put("present",value.present()); row.set("value",value.value()); });
        String digest=RepositoryJson.digest(material);
        return new Snapshot(representation,lifecycle,fields,"content-sha256:"+digest,digest);
    }
}
