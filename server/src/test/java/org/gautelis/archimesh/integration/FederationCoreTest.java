package org.gautelis.archimesh.integration;

import org.gautelis.federation.ContractChecks;
import org.junit.jupiter.api.Test;

class FederationCoreTest {
    @Test void sharedIdentityAuthorityAndProvenanceContracts() { ContractChecks.verify(); }
}
