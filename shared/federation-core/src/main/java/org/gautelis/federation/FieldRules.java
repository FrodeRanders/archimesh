package org.gautelis.federation;

import java.util.*;

/** One precedence algorithm shared by repository adapters and model ownership guards. */
public final class FieldRules<T> {
    public enum Scope { TYPE, CANONICAL }
    public record Rule<T>(Scope scope, String subject, String field, T value) {
        public Rule { Objects.requireNonNull(scope); Identity.text(subject, 2048); Identity.text(field, 2048); Objects.requireNonNull(value); }
    }
    private record Key(Scope scope, String subject, String field) { }
    private final Map<Key,T> rules;
    public FieldRules(Collection<Rule<T>> values) {
        var result = new HashMap<Key,T>();
        for (var rule : values) {
            if (result.put(new Key(rule.scope(), rule.subject(), rule.field()), rule.value()) != null)
                throw new IllegalArgumentException("Duplicate field authority rule");
        }
        rules = Map.copyOf(result);
    }
    public Optional<T> resolve(String canonical, String type, String field) {
        Identity.text(type, 2048); Identity.text(field, 2048);
        T value = null;
        if (canonical != null) { Identity.text(canonical, 2048); value = rules.get(new Key(Scope.CANONICAL, canonical, field)); }
        if (value == null) value = rules.get(new Key(Scope.TYPE, type, field));
        return Optional.ofNullable(value);
    }
}
