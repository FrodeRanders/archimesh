package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityMigrationInputTest {
    @Test void malformedUnicodeIsRejectedBeforeAnyDatabaseAccess() {
        var migration=new IdentityMigration(null,new ObjectMapper());
        assertThrows(IllegalArgumentException.class,() -> migration.preview("instance","urn:canonical","kind\uD800"));
        assertThrows(IllegalArgumentException.class,() -> migration.apply("instance","urn:bad\uD800","kind","a".repeat(64),"actor"));
    }
    @Test void coreSupportsExistingUnicodeCanonicalIdsWithoutChangingTheirSpelling() {
        String id="å".repeat(512);
        assertEquals(id,new org.gautelis.federation.Identity.Canonical(id,"enterprise.application").id());
    }
}
