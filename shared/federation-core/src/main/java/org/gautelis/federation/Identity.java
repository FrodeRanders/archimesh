package org.gautelis.federation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Version 1 value contracts. Native identifiers are opaque, exact and never normalized. */
public final class Identity {
    private Identity() { }
    public enum ResourceKind { OBJECT, RELATIONSHIP }

    public record Repository(String system, String instance) {
        public Repository { text(system, 2048); text(instance, 2048); }
    }
    public record Resource(Repository repository, ResourceKind kind, String nativeId) {
        public Resource { Objects.requireNonNull(repository); Objects.requireNonNull(kind); text(nativeId, 8192); }
    }
    /** Semantic kind is independent of native entity type and ArchiMate type. */
    public record Canonical(String id, String semanticKind) {
        public Canonical { text(id, 2048); text(semanticKind, 2048); }
    }
    public record Representation(Resource resource, String externalType) {
        public Representation { Objects.requireNonNull(resource); text(externalType, 512); }
    }
    public record SourceField(Resource resource, String aspect, String path) {
        public SourceField { Objects.requireNonNull(resource); text(aspect, 512); text(path, 512); }
    }
    /** Bounds are UTF-8 bytes, not Java characters. Reject malformed Unicode rather than replace it. */
    public static String text(String value, int bytes) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > bytes)
            throw new IllegalArgumentException("Invalid federation text");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("Invalid federation text");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Invalid federation Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid federation Unicode");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > bytes) throw new IllegalArgumentException("Federation text exceeds byte limit");
        return value;
    }
}
