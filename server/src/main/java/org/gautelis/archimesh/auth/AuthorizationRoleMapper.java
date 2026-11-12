package org.gautelis.archimesh.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class AuthorizationRoleMapper {

    @ConfigProperty(name = "app.authz.admin-role", defaultValue = "admin")
    String adminRole;

    @ConfigProperty(name = "app.authz.reader-role", defaultValue = "model_reader")
    String readerRole;

    @ConfigProperty(name = "app.authz.writer-role", defaultValue = "model_writer")
    String writerRole;

    @ConfigProperty(name = "app.authz.admin-role-aliases")
    Optional<String> adminRoleAliasesRaw;

    @ConfigProperty(name = "app.authz.reader-role-aliases")
    Optional<String> readerRoleAliasesRaw;

    @ConfigProperty(name = "app.authz.writer-role-aliases")
    Optional<String> writerRoleAliasesRaw;

    @ConfigProperty(name = "app.authz.federation-roles", defaultValue = "federation_reader,federation_bridge,federation_operator")
    String federationRolesRaw = "federation_reader,federation_bridge,federation_operator";

    public Set<String> normalizeRoles(Set<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addIfPresent(normalized, rawRoles, adminRole, adminAliases());
        addIfPresent(normalized, rawRoles, readerRole, readerAliases());
        addIfPresent(normalized, rawRoles, writerRole, writerAliases());
        federationRoles().stream().filter(rawRoles::contains).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    public Set<String> rolesFromSecurityContext(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addIfGranted(normalized, securityContext, adminRole, adminAliases());
        addIfGranted(normalized, securityContext, readerRole, readerAliases());
        addIfGranted(normalized, securityContext, writerRole, writerAliases());
        federationRoles().stream().filter(securityContext::isUserInRole).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    public Set<String> adminAliases() {
        return aliases(adminRoleAliasesRaw.orElse(""));
    }

    public Set<String> readerAliases() {
        return aliases(readerRoleAliasesRaw.orElse(""));
    }

    public Set<String> writerAliases() {
        return aliases(writerRoleAliasesRaw.orElse(""));
    }

    /** Extra roles grant no model/admin capability; preserve them only for the separate owner PEP. */
    public Set<String> federationRoles() {
        return aliases(federationRolesRaw);
    }

    private void addIfPresent(Set<String> normalized, Set<String> rawRoles, String canonicalRole, Set<String> aliases) {
        if (canonicalRole == null || canonicalRole.isBlank()) {
            return;
        }
        String canonical = normalize(canonicalRole);
        if (rawRoles.contains(canonical) || aliases.stream().anyMatch(rawRoles::contains)) {
            normalized.add(canonical);
        }
    }

    private void addIfGranted(Set<String> normalized, SecurityContext securityContext, String canonicalRole, Set<String> aliases) {
        if (canonicalRole == null || canonicalRole.isBlank()) {
            return;
        }
        if (securityContext.isUserInRole(canonicalRole)) {
            normalized.add(normalize(canonicalRole));
            return;
        }
        for (String alias : aliases) {
            if (securityContext.isUserInRole(alias)) {
                normalized.add(normalize(canonicalRole));
                return;
            }
        }
    }

    private Set<String> aliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(this::normalize)
                .filter(v -> !v.isBlank())
                .forEach(values::add);
        return Set.copyOf(values);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
