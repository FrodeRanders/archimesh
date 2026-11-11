package org.gautelis.archimesh.integration;

import java.util.Map;

/** Declarative policy foundation. Enforcement in model acceptance is introduced with inbound sync. */
public final class InformationAuthorityPolicy {
    public enum Authority { TURBO_EA_MASTER, ARCHIMESH_MASTER, LOCAL_MODEL_ONLY, SHARED_PROPOSAL, UNMAPPED }
    public enum Scope { TYPE, CANONICAL }

    public record Rule(Scope scope, String subject, String fieldKey, Authority authority, String version) {
        public Rule {
            if (scope == null || authority == null) throw new IllegalArgumentException("Authority scope and mode are required");
            IntegrationIdentity.required(subject); IntegrationIdentity.required(fieldKey); IntegrationIdentity.required(version);
        }
    }

    private final Map<String, Rule> rules;

    public InformationAuthorityPolicy(java.util.Collection<Rule> values) {
        var result = new java.util.HashMap<String, Rule>();
        for (Rule rule : values) {
            if (result.put(key(rule.scope(), rule.subject(), rule.fieldKey()), rule) != null)
                throw new IllegalArgumentException("Duplicate information authority rule");
        }
        rules = Map.copyOf(result);
    }

    public Authority authority(String canonicalId, String type, String fieldKey) {
        IntegrationIdentity.required(type); IntegrationIdentity.required(fieldKey);
        Rule rule = canonicalId == null ? null : rules.get(key(Scope.CANONICAL, canonicalId, fieldKey));
        if (rule == null) rule = rules.get(key(Scope.TYPE, type, fieldKey));
        return rule == null ? Authority.UNMAPPED : rule.authority();
    }

    private static String key(Scope scope, String subject, String field) {
        // Length prefix avoids collisions in arbitrary canonical URNs/field names.
        return scope + ":" + subject.length() + ":" + subject + field;
    }
}
