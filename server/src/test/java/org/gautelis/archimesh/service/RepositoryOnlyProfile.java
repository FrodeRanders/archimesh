package org.gautelis.archimesh.service;

import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Set;

/** Storage tests own their pending flags; background delivery would mutate those fixtures. */
public class RepositoryOnlyProfile implements QuarkusTestProfile {
    @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(NoBackgroundDelivery.class); }

    @Alternative
    @ApplicationScoped
    public static class NoBackgroundDelivery extends CommitDeliveryService {
        @Override public synchronized void start() { }
    }
}
