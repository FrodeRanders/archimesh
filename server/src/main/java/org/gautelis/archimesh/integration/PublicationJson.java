package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content equality independent of object-key order and numeric rendering. Arrays remain ordered unless declared a field set. */
public final class PublicationJson {
    private PublicationJson() { }
    public static JsonNode canonical(JsonNode value) {
        if (value==null || value.isNull() || value.isMissingNode()) return NullNode.instance;
        if (value.isObject()) {
            var result=JsonNodeFactory.instance.objectNode(); var names=new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(key -> result.set(key,canonical(value.get(key)))); return result;
        }
        if (value.isArray()) { var result=JsonNodeFactory.instance.arrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        if (value.isNumber()) return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        return value.deepCopy();
    }
    public static JsonNode field(JsonNode value) {
        if (value==null || !value.isArray()) return canonical(value);
        var sorted=new TreeMap<String,JsonNode>(); value.forEach(item -> sorted.put(canonical(item).toString(),canonical(item)));
        var result=JsonNodeFactory.instance.arrayNode(); sorted.values().forEach(result::add); return result;
    }
    public static String digest(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean equal(JsonNode a,JsonNode b) { return canonical(a).equals(canonical(b)); }
    public static String key(String prefix,String... parts) { return prefix+digest(new ObjectMapper().valueToTree(parts)); }
}
