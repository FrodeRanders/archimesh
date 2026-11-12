package org.gautelis.archimesh.auth;

import org.gautelis.archimesh.model.ModelAccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class InProcessPolicyDecisionPoint implements PolicyDecisionPoint {

    @ConfigProperty(name = "app.authz.admin-role", defaultValue = "admin")
    String adminRole;

    @ConfigProperty(name = "app.authz.reader-role", defaultValue = "model_reader")
    String readerRole;

    @ConfigProperty(name = "app.authz.writer-role", defaultValue = "model_writer")
    String writerRole;

    @Override
    public AuthorizationDecision decide(AuthorizationRequest request) {
        if (request == null || request.subject() == null || request.subject().userId() == null || request.subject().userId().isBlank()) {
            return AuthorizationDecision.deny("AUTH_REQUIRED", "Authenticated subject is required.");
        }
        if (hasRole(request, adminRole)) {
            return AuthorizationDecision.allow();
        }
        if (isCatalogScopedAdminAction(request.action())) {
            return AuthorizationDecision.deny("ADMIN_ROLE_REQUIRED",
                    "Action " + request.action() + " requires role " + adminRole + ".");
        }

        ModelAccessControl accessControl = request.accessControl();
        if (request.action().name().startsWith("ADMIN_")) {
            if (isModelAdmin(request, accessControl)) {
                return AuthorizationDecision.allow();
            }
            return AuthorizationDecision.deny("MODEL_ADMIN_REQUIRED",
                    "Action " + request.action() + " requires model admin access.");
        }

        if (accessControl != null && accessControl.configured()) {
            return switch (request.action()) {
                case MODEL_JOIN, MODEL_SNAPSHOT_READ, EXTERNAL_CATALOG_READ -> isModelReader(request, accessControl)
                        ? AuthorizationDecision.allow()
                        : AuthorizationDecision.deny("MODEL_ACCESS_DENIED",
                        "Subject is not allowed to read model " + request.modelId() + ".");
                case MODEL_SUBMIT_OPS, MODEL_ACQUIRE_LOCK, MODEL_RELEASE_LOCK, MODEL_PRESENCE, EXTERNAL_OBJECT_IMPORT -> isModelWriter(request, accessControl)
                        ? AuthorizationDecision.allow()
                        : AuthorizationDecision.deny("MODEL_ACCESS_DENIED",
                        "Subject is not allowed to modify model " + request.modelId() + ".");
                default -> AuthorizationDecision.deny("ACTION_DENIED", "Unsupported model action");
            };
        }

        return switch (request.action()) {
            case MODEL_JOIN, MODEL_SNAPSHOT_READ, EXTERNAL_CATALOG_READ -> hasAnyRole(request, readerRole, writerRole)
                    ? AuthorizationDecision.allow()
                    : AuthorizationDecision.deny("MODEL_READ_ROLE_REQUIRED",
                    "Action " + request.action() + " requires role " + readerRole + " or " + writerRole + ".");
            case MODEL_SUBMIT_OPS, MODEL_ACQUIRE_LOCK, MODEL_RELEASE_LOCK, MODEL_PRESENCE, EXTERNAL_OBJECT_IMPORT -> hasRole(request, writerRole)
                    ? AuthorizationDecision.allow()
                    : AuthorizationDecision.deny("MODEL_WRITE_ROLE_REQUIRED",
                    "Action " + request.action() + " requires role " + writerRole + ".");
            default -> AuthorizationDecision.deny("ACTION_DENIED", "Unsupported model action");
        };
    }

    private boolean isCatalogScopedAdminAction(AuthorizationAction action) {
        return action == AuthorizationAction.ADMIN_INTEGRATION_OPERATIONS
                || action == AuthorizationAction.ADMIN_MODEL_CATALOG_READ
                || action == AuthorizationAction.ADMIN_MODEL_CREATE
                || action == AuthorizationAction.ADMIN_MODEL_IMPORT
                || action == AuthorizationAction.ADMIN_OVERVIEW_READ;
    }

    private boolean hasRole(AuthorizationRequest request, String role) {
        return request.subject().roles().contains(role);
    }

    private boolean hasAnyRole(AuthorizationRequest request, String... roles) {
        for (String role : roles) {
            if (hasRole(request, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean isModelAdmin(AuthorizationRequest request, ModelAccessControl accessControl) {
        return hasUser(accessControl == null ? null : accessControl.adminUsers(), request.subject().userId());
    }

    private boolean isModelWriter(AuthorizationRequest request, ModelAccessControl accessControl) {
        return isModelAdmin(request, accessControl)
                || hasUser(accessControl == null ? null : accessControl.writerUsers(), request.subject().userId());
    }

    private boolean isModelReader(AuthorizationRequest request, ModelAccessControl accessControl) {
        return isModelWriter(request, accessControl)
                || hasUser(accessControl == null ? null : accessControl.readerUsers(), request.subject().userId());
    }

    private boolean hasUser(java.util.Set<String> allowedUsers, String userId) {
        return allowedUsers != null && userId != null && !userId.isBlank() && allowedUsers.contains(userId);
    }
}
