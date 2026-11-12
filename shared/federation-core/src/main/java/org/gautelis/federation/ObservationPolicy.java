package org.gautelis.federation;

import java.util.Objects;
import static org.gautelis.federation.Identity.*;

/** Pure admission decisions. The host authenticates reads and commits baselines with CAS. */
public final class ObservationPolicy {
    private ObservationPolicy() { }
    public enum Decision { ACCEPT_DIRECT, UNCHANGED, POLICY_DENIED, WRONG_SOURCE, UNVERIFIED_IDENTITY,
        STALE_BASELINE, UNVERIFIED_MIRROR, REFRESH_SOURCE }
    public record Observation(SourceField source, Resource via, String sourceRevision, String valueDigest, String receipt) {
        public Observation {
            Objects.requireNonNull(source); Objects.requireNonNull(via); text(sourceRevision, 512); digest(valueDigest);
            if (receipt != null) text(receipt, 512);
        }
    }
    public record Baseline(long version, SourceField source, String sourceRevision, String valueDigest) {
        public Baseline {
            if (version < 1) throw new IllegalArgumentException("Invalid source baseline version");
            Objects.requireNonNull(source); text(sourceRevision, 512); digest(valueDigest);
        }
    }
    /** Implement using durable publication/adoption proof, never a field in the remote card. */
    @FunctionalInterface public interface ReceiptVerifier { boolean verify(Canonical canonical, Observation observation); }

    public static Decision evaluate(Canonical canonical, SourceAuthority authority, Observation observation,
                                    long expectedBaseline, Baseline baseline,
                                    IdentityResolver registry, ReceiptVerifier receipts) {
        Objects.requireNonNull(canonical); Objects.requireNonNull(authority); Objects.requireNonNull(observation);
        Objects.requireNonNull(registry); Objects.requireNonNull(receipts);
        if (expectedBaseline < 0) throw new IllegalArgumentException("Invalid expected baseline");
        if (authority.mode() != SourceAuthority.Mode.REPOSITORY_MASTER) return Decision.POLICY_DENIED;
        if (!authority.owner().equals(observation.source())) return Decision.WRONG_SOURCE;
        try {
            if (!canonical.equals(registry.resolve(observation.via(), observation.source().resource()))) return Decision.UNVERIFIED_IDENTITY;
        } catch (IdentityResolver.Conflict conflict) { return Decision.UNVERIFIED_IDENTITY; }
        if (expectedBaseline != (baseline == null ? 0 : baseline.version())) return Decision.STALE_BASELINE;
        if (baseline != null && !baseline.source().equals(observation.source())) return Decision.WRONG_SOURCE;
        boolean same = baseline != null && baseline.sourceRevision().equals(observation.sourceRevision())
                && baseline.valueDigest().equals(observation.valueDigest());
        if (observation.via().equals(observation.source().resource())) return same ? Decision.UNCHANGED : Decision.ACCEPT_DIRECT;
        if (observation.receipt() == null || !receipts.verify(canonical, observation)) return Decision.UNVERIFIED_MIRROR;
        // Opaque revisions have no cross-system ordering. A mirror can confirm a baseline, never advance it.
        return same ? Decision.UNCHANGED : Decision.REFRESH_SOURCE;
    }
    private static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid value digest");
    }
}
