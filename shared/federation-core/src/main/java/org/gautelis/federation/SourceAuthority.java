package org.gautelis.federation;

import java.util.Objects;
import static org.gautelis.federation.Identity.*;

/** Field ownership names a source instance/aspect/path, independently of the transport repository. */
public record SourceAuthority(Mode mode, SourceField owner, String version) {
    public enum Mode { REPOSITORY_MASTER, MODEL_MASTER, LOCAL_ONLY, SHARED_PROPOSAL, UNMAPPED }
    public SourceAuthority {
        Objects.requireNonNull(mode); text(version, 512);
        if ((mode == Mode.REPOSITORY_MASTER || mode == Mode.SHARED_PROPOSAL) != (owner != null))
            throw new IllegalArgumentException("Repository/shared authority requires an exact source field");
    }
    public boolean protectsLocal() { return mode == Mode.REPOSITORY_MASTER || mode == Mode.SHARED_PROPOSAL; }
}
