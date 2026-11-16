package org.gautelis.federation;

import java.util.*;
import static org.gautelis.federation.ArchitectureAssertion.*;

final class ArchitectureAssertionChecks {
    static void run() {
        var target=new Identity.Repository("turboea","enterprise");
        var logical=new Key("app",Predicate.LOGICAL_DATA_ACCESS,"data",target,"profile","rule","access","explicit-v1");
        var catalog=new Key("app",Predicate.CATALOG_CONSUMES,"data",target,"profile","rule","access","explicit-v1");
        if(logical.canonicalId().equals(catalog.canonicalId())) throw new AssertionError("Logical and catalog claims were conflated");
        var paths=List.of(new Path("assignment-a","function-a","access-a",Set.of(Access.READ)),new Path("assignment-b","function-b","access-b",Set.of(Access.WRITE)));
        var a=new Evidence("archimesh","instance","model-a","release",12,"profile","1","a".repeat(64),"rule",Authority.ARCHIMESH_ARCHITECTURE,List.of("app","data"),paths);
        var reordered=new Evidence("archimesh","instance","model-a","release",12,"profile","1","a".repeat(64),"rule",Authority.ARCHIMESH_ARCHITECTURE,List.of("data","app"),List.of(paths.get(1),paths.get(0)));
        var other=new Evidence("archimesh","instance","model-b","release",12,"profile","1","a".repeat(64),"rule",Authority.ARCHIMESH_ARCHITECTURE,List.of("app","data"),paths);
        if(!a.digest().equals(reordered.digest()) || a.digest().equals(other.digest()) || !a.access().equals(Set.of(Access.READ,Access.WRITE)))
            throw new AssertionError("Evidence normalization, source separation or access union failed");
    }
}
