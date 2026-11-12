package org.gautelis.archimesh.integration;

import org.gautelis.federation.FieldRules;

/** Shared rule precedence; effective mappings feed model acceptance and repository projection. */
public final class InformationAuthorityPolicy {
    public enum Authority { TURBO_EA_MASTER, ARCHIMESH_MASTER, LOCAL_MODEL_ONLY, SHARED_PROPOSAL, UNMAPPED }
    public enum Scope { TYPE, CANONICAL }

    public record Rule(Scope scope, String subject, String fieldKey, Authority authority, String version) {
        public Rule {
            if (scope == null || authority == null) throw new IllegalArgumentException("Authority scope and mode are required");
            IntegrationIdentity.required(subject); IntegrationIdentity.required(fieldKey); IntegrationIdentity.required(version);
        }
    }

    private final FieldRules<Rule> rules;

    public InformationAuthorityPolicy(java.util.Collection<Rule> values) {
        rules = new FieldRules<>(values.stream().map(rule -> new FieldRules.Rule<>(
                FieldRules.Scope.valueOf(rule.scope().name()),rule.subject(),rule.fieldKey(),rule)).toList());
    }

    public Authority authority(String canonicalId, String type, String fieldKey) {
        IntegrationIdentity.required(type); IntegrationIdentity.required(fieldKey);
        return rules.resolve(canonicalId,type,fieldKey).map(Rule::authority).orElse(Authority.UNMAPPED);
    }
}
