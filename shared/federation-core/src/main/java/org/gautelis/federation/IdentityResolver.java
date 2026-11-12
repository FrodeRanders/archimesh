package org.gautelis.federation;

import java.util.Objects;
import java.util.Optional;
import static org.gautelis.federation.Identity.*;

/** Read-only resolution against a trusted registry port; editable origin claims grant no binding. */
public final class IdentityResolver {
    @FunctionalInterface public interface Registry { Optional<Canonical> lookup(Resource resource); }
    public enum Code { UNKNOWN_RESOURCE, UNVERIFIED_ORIGIN, IDENTITY_CONFLICT }
    public static final class Conflict extends IllegalStateException {
        private final Code code;
        public Conflict(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
    private final Registry registry;
    public IdentityResolver(Registry registry) { this.registry = Objects.requireNonNull(registry); }

    public Canonical resolve(Resource requested) {
        return registry.lookup(Objects.requireNonNull(requested)).orElseThrow(() -> new Conflict(Code.UNKNOWN_RESOURCE));
    }
    public Canonical resolve(Resource requested, Resource claimedOrigin) {
        var actual = resolve(requested);
        var origin = registry.lookup(Objects.requireNonNull(claimedOrigin)).orElseThrow(() -> new Conflict(Code.UNVERIFIED_ORIGIN));
        if (!actual.equals(origin)) throw new Conflict(Code.IDENTITY_CONFLICT);
        return actual;
    }
}
