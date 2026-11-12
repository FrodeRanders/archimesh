package org.gautelis.federation;

import java.util.Optional;
import static org.gautelis.federation.Identity.*;

/** Trusted-host persistence port; no principal authorization or remote verification is granted by these methods. */
public interface IdentityRegistry extends IdentityResolver.Registry {
    record Evidence(String correlation, String actor, String contentDigest) {
        public Evidence {
            text(correlation,512); text(actor,512);
            if (contentDigest==null || !contentDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid evidence digest");
        }
    }
    record Binding(Canonical canonical, Representation representation, String proofId) { }
    /** Atomically reserve a source. Concurrent candidates for the same source converge; kind conflicts fail. */
    Binding reserveSource(Representation source, Canonical candidate, Evidence evidence);
    /** Explicit verified equivalence only; never merges/reassigns preexisting canonical identities. */
    Binding bindRepresentation(Canonical canonical, Representation representation, Evidence evidence);
    Optional<Binding> find(Resource resource);
    @Override default Optional<Canonical> lookup(Resource resource) { return find(resource).map(Binding::canonical); }
}
