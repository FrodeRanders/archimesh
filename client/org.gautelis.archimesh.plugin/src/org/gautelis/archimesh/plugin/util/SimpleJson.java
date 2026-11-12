package org.gautelis.archimesh.plugin.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Small JSON helper for Archimesh envelopes without adding new dependencies.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    /** Escape a JSON string body, including every control character forbidden by JSON. */
    public static String escapeString(String value) {
        if(value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if(c == '\\' || c == '"') result.append('\\').append(c);
            else if(c < 0x20) {
                result.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
            }
            else result.append(c);
        }
        return result.toString();
    }

    /** Repair legacy outbox JSON affected by the old encoder, without rewriting other tokens. */
    public static String repairUnescapedControlCharacters(String json) {
        if(json == null) return null;
        StringBuilder result = new StringBuilder(json.length());
        boolean inString = false, escaped = false;
        for(int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if(inString && !escaped && c < 0x20) {
                result.append(escapeString(String.valueOf(c)));
                continue;
            }
            result.append(c);
            if(escaped) escaped = false;
            else if(inString && c == '\\') escaped = true;
            else if(c == '"') inString = !inString;
        }
        return result.toString();
    }

    public static String readRawField(String jsonObject, String key) {
        if(jsonObject == null || key == null) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for(int i = 0; i < jsonObject.length(); i++) {
            char c = jsonObject.charAt(i);

            if(inString) {
                if(escaped) {
                    escaped = false;
                }
                else if(c == '\\') {
                    escaped = true;
                }
                else if(c == '"') {
                    inString = false;
                }
                continue;
            }

            if(c == '"') {
                if(depth == 1) {
                    int prev = previousNonWhitespace(jsonObject, i - 1);
                    // Object keys at this depth must follow '{' or ','.
                    // If not, this quote belongs to a value string.
                    if(prev != '{' && prev != ',') {
                        inString = true;
                        continue;
                    }

                    int keyStart = i + 1;
                    int keyEnd = findStringEnd(jsonObject, keyStart);
                    if(keyEnd < 0) {
                        return null;
                    }
                    String actualKey = decodeString(jsonObject.substring(keyStart, keyEnd));
                    i = keyEnd;
                    if(!key.equals(actualKey)) {
                        continue;
                    }

                    int colon = skipWhitespace(jsonObject, i + 1);
                    if(colon >= jsonObject.length() || jsonObject.charAt(colon) != ':') {
                        return null;
                    }

                    int valueStart = skipWhitespace(jsonObject, colon + 1);
                    if(valueStart >= jsonObject.length()) {
                        return null;
                    }

                    int valueEnd = findValueEnd(jsonObject, valueStart);
                    if(valueEnd < 0 || valueEnd < valueStart) {
                        return null;
                    }

                    return jsonObject.substring(valueStart, valueEnd);
                }
                inString = true;
                continue;
            }

            if(c == '{' || c == '[') {
                depth++;
            }
            else if(c == '}' || c == ']') {
                depth--;
            }
        }

        return null;
    }

    public static boolean hasField(String jsonObject, String key) {
        return readRawField(jsonObject, key) != null;
    }

    public static String readStringField(String jsonObject, String key) {
        String raw = readRawField(jsonObject, key);
        if(raw == null || "null".equals(raw)) {
            return null;
        }
        if(raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return decodeString(raw.substring(1, raw.length() - 1));
        }
        return raw;
    }

    public static Integer readIntField(String jsonObject, String key) {
        String raw = readRawField(jsonObject, key);
        if(raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch(NumberFormatException ex) {
            return null;
        }
    }

    public static Long readLongField(String jsonObject, String key) {
        String raw = readRawField(jsonObject, key);
        if(raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch(NumberFormatException ex) {
            return null;
        }
    }

    public static Boolean readBooleanField(String jsonObject, String key) {
        String raw = readRawField(jsonObject, key);
        if(raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        if("true".equalsIgnoreCase(raw.trim())) {
            return Boolean.TRUE;
        }
        if("false".equalsIgnoreCase(raw.trim())) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static String asJsonObject(String rawFieldValue) {
        if(rawFieldValue == null) {
            return null;
        }
        String trimmed = rawFieldValue.trim();
        if(trimmed.startsWith("{")) {
            return trimmed;
        }
        if(trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            String decoded = decodeString(trimmed.substring(1, trimmed.length() - 1)).trim();
            if(decoded.startsWith("{")) {
                return decoded;
            }
        }
        return null;
    }

    public static List<String> readArrayObjectElements(String jsonObject, String key) {
        String rawArray = readRawField(jsonObject, key);
        if(rawArray == null) {
            return List.of();
        }
        return splitArrayObjects(rawArray);
    }

    public static List<String> splitArrayObjects(String jsonArray) {
        if(jsonArray == null) {
            return List.of();
        }

        String text = jsonArray.trim();
        if(text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int start = -1;

        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if(inString) {
                if(escaped) {
                    escaped = false;
                }
                else if(c == '\\') {
                    escaped = true;
                }
                else if(c == '"') {
                    inString = false;
                }
                continue;
            }

            if(c == '"') {
                inString = true;
                continue;
            }

            if(c == '{') {
                if(depth == 0) {
                    start = i;
                }
                depth++;
            }
            else if(c == '}') {
                depth--;
                if(depth == 0 && start >= 0) {
                    result.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        return result;
    }

    public static String decodeString(String jsonStringBody) {
        StringBuilder out = new StringBuilder(jsonStringBody.length());
        for(int i = 0; i < jsonStringBody.length(); i++) {
            char c = jsonStringBody.charAt(i);
            if(c != '\\') {
                out.append(c);
                continue;
            }

            if(i + 1 >= jsonStringBody.length()) {
                break;
            }

            char n = jsonStringBody.charAt(++i);
            switch(n) {
                case '"':
                case '\\':
                case '/':
                    out.append(n);
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if(i + 4 < jsonStringBody.length()) {
                        String hex = jsonStringBody.substring(i + 1, i + 5);
                        try {
                            out.append((char)Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        catch(NumberFormatException ex) {
                            out.append("\\u").append(hex);
                            i += 4;
                        }
                    }
                    break;
                default:
                    out.append(n);
                    break;
            }
        }
        return out.toString();
    }

    private static int skipWhitespace(String s, int index) {
        int i = index;
        while(i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int previousNonWhitespace(String s, int index) {
        int i = index;
        while(i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i < 0 ? -1 : s.charAt(i);
    }

    private static int findStringEnd(String s, int from) {
        boolean escaped = false;
        for(int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if(escaped) {
                escaped = false;
            }
            else if(c == '\\') {
                escaped = true;
            }
            else if(c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findValueEnd(String s, int valueStart) {
        char first = s.charAt(valueStart);
        if(first == '"') {
            int endQuote = findStringEnd(s, valueStart + 1);
            return endQuote < 0 ? -1 : endQuote + 1;
        }

        if(first == '{' || first == '[') {
            char open = first;
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for(int i = valueStart; i < s.length(); i++) {
                char c = s.charAt(i);
                if(inString) {
                    if(escaped) {
                        escaped = false;
                    }
                    else if(c == '\\') {
                        escaped = true;
                    }
                    else if(c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if(c == '"') {
                    inString = true;
                    continue;
                }
                if(c == open) {
                    depth++;
                }
                else if(c == close) {
                    depth--;
                    if(depth == 0) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }

        for(int i = valueStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if(c == ',' || c == '}') {
                return i;
            }
        }
        return s.length();
    }
}
