package org.gautelis.federation.http;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.security.MessageDigest;
import java.util.*;

/** Versioned encoding for observation digests: recursive object-key sort, array order retained. */
public final class RepositoryJson {
    private RepositoryJson() { }
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(16_777_216).maxNumberLength(128).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            var output=JSON.createObjectNode(); var names=new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> output.set(name,ordered(value.get(name)))); return output;
        }
        if (value.isArray()) { var output=JSON.createArrayNode(); value.forEach(item -> output.add(ordered(item))); return output; }
        return value.deepCopy();
    }
    public static String digest(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(ordered(value)))); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid observation value"); }
    }
}
