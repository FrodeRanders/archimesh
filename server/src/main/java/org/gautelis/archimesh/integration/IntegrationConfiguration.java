package org.gautelis.archimesh.integration;

import org.gautelis.archimesh.integration.turboea.TurboEaClient;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping;
import java.net.URI;
import java.util.HashSet;
import java.util.List;

/** Non-secret target definitions loaded only when explicitly enabled. */
public record IntegrationConfiguration(int schemaVersion, String instanceId, List<Target> targets) {
    public record Target(String key, String instanceId, URI baseUrl, String tokenEnvironment,
                         String expectedApiVersion, int timeoutMillis, int maxResponseBytes, TurboEaMapping mapping) {
        public Target {
            IntegrationIdentity.required(key); IntegrationIdentity.required(instanceId);
            IntegrationIdentity.required(expectedApiVersion);
            if (tokenEnvironment == null || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}"))
                throw new IllegalArgumentException("Target tokenEnvironment must name an environment variable");
            TurboEaClient.validateBaseUrl(baseUrl);
            if (mapping == null || timeoutMillis < 1 || timeoutMillis > 120_000
                    || maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Target mapping and bounded HTTP limits are required");
        }
    }

    public IntegrationConfiguration {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported integration configuration version");
        IntegrationIdentity.required(instanceId);
        targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.size() > 20) throw new IllegalArgumentException("Configure between one and twenty targets");
        var keys = new HashSet<String>(); var instances = new HashSet<String>();
        for (var target : targets) {
            if (!keys.add(target.key()) || !instances.add(target.instanceId()))
                throw new IllegalArgumentException("Duplicate integration target key or instance identity");
        }
    }
}
