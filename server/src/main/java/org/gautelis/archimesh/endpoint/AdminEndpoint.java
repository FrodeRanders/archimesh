package org.gautelis.archimesh.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.archimesh.auth.AuthorizationAction;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.model.*;
import org.gautelis.archimesh.service.ArchimeshService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint for administrative operations at {@code /admin}. Provides CRUD APIs
 * for model catalog management, access control, tags, export/import, compaction,
 * integrity checks, and operational diagnostics.
 */
@Path("/admin")
public class AdminEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(AdminEndpoint.class);

    @Inject
    ArchimeshService archimeshService;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "app.authz.enabled", defaultValue = "false")
    boolean authorizationEnabled;

    @ConfigProperty(name = "app.audit.websocket.actions",
            defaultValue = "WebSocketOpenRejected,WebSocketJoin,WebSocketMessageRejected,WebSocketClosed")
    String configuredAuditActions;

    @ConfigProperty(name = "app.audit.websocket.verbose", defaultValue = "false")
    boolean websocketAuditVerbose;

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Lists all models registered in the catalog.
     */
    public List<ModelCatalogEntry> modelCatalog(@Context HttpHeaders headers,
                                                @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_CATALOG_READ, null, null);
        return archimeshService.getModelCatalog();
    }

    @GET
    @Path("/auth/diagnostics")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the current auth identity mode, user ID, and roles for diagnostic purposes.
     */
    public AdminAuthorizationDiagnostics authorizationDiagnostics(@Context HttpHeaders headers,
                                                                 @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_OVERVIEW_READ, null, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("identityMode", authorizationService.currentIdentityMode());
        context.put("roles", subject.roles());
        audit("AdminAuthDiagnostics", null, subject.userId(), context);
        return new AdminAuthorizationDiagnostics(
                authorizationService.currentIdentityMode(),
                subject.userId(),
                subject.roles());
    }

    @GET
    @Path("/audit/config")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the current audit configuration including enabled actions and verbosity settings.
     */
    public AdminAuditConfig auditConfig(@Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_OVERVIEW_READ, null, null);
        return new AdminAuditConfig(
                authorizationService.currentIdentityMode(),
                authorizationEnabled,
                Arrays.stream(configuredAuditActions.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toList()),
                websocketAuditVerbose);
    }

    @POST
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Registers a new model in the catalog with an optional display name.
     */
    public ModelCatalogEntry registerModel(@PathParam("modelId") String modelId,
                                           @QueryParam("modelName") String modelName,
                                           @Context HttpHeaders headers,
                                           @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_CREATE, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelCatalogEntry result = archimeshService.registerModel(modelId, modelName, subject.userId());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("modelName", result.modelName());
        context.put("headRevision", result.headRevision());
        audit("AdminModelCreate", modelId, subject.userId(), context);
        return result;
    }

    @PUT
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Renames an existing model.
     */
    public ModelCatalogEntry renameModel(@PathParam("modelId") String modelId,
                                         @QueryParam("modelName") String modelName,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_RENAME, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelCatalogEntry result = archimeshService.renameModel(modelId, modelName);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("modelName", result.modelName());
        context.put("headRevision", result.headRevision());
        audit("AdminModelRename", modelId, subject.userId(), context);
        return result;
    }

    @GET
    @Path("/models/{modelId}/acl")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Reads the access control list (admin, writer, reader users) for a model.
     */
    public ModelAccessControl readModelAccessControl(@PathParam("modelId") String modelId,
                                                     @Context HttpHeaders headers,
                                                     @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_ACL_READ, modelId, null);
        return archimeshService.getModelAccessControl(modelId);
    }

    @PUT
    @Path("/models/{modelId}/acl")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Updates the access control list for a model.
     */
    public ModelAccessControl updateModelAccessControl(@PathParam("modelId") String modelId,
                                                       ModelAccessControlUpdateRequest request,
                                                       @Context HttpHeaders headers,
                                                       @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_ACL_UPDATE, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelAccessControlUpdateRequest safeRequest = request == null
                ? new ModelAccessControlUpdateRequest(null, null, null)
                : request;
        ModelAccessControl result = archimeshService.updateModelAccessControl(
                modelId,
                safeRequest.adminUsers(),
                safeRequest.writerUsers(),
                safeRequest.readerUsers());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("configured", result.configured());
        context.put("adminUserCount", result.adminUsers().size());
        context.put("writerUserCount", result.writerUsers().size());
        context.put("readerUserCount", result.readerUsers().size());
        audit("AdminModelAclUpdate", modelId, subject.userId(), context);
        return result;
    }

    @GET
    @Path("/models/{modelId}/tags")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Lists all snapshot tags for a model.
     */
    public List<ModelTagEntry> listModelTags(@PathParam("modelId") String modelId,
                                             @Context HttpHeaders headers,
                                             @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_LIST, modelId, null);
        return archimeshService.getModelTags(modelId);
    }

    @POST
    @Path("/models/{modelId}/tags")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Creates a named tag capturing the current model state.
     */
    public ModelTagEntry createModelTag(@PathParam("modelId") String modelId,
                                        @QueryParam("tagName") String tagName,
                                        @QueryParam("description") String description,
                                        @Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_CREATE, modelId, tagName);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelTagEntry result = archimeshService.createModelTag(modelId, tagName, description);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tagName", result.tagName());
        context.put("revision", result.revision());
        context.put("hasDescription", result.description() != null && !result.description().isBlank());
        audit("AdminModelTagCreate", modelId, subject.userId(), context);
        return result;
    }

    @DELETE
    @Path("/models/{modelId}/tags/{tagName}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Deletes a named tag from a model.
     */
    public void deleteModelTag(@PathParam("modelId") String modelId,
                               @PathParam("tagName") String tagName,
                               @Context HttpHeaders headers,
                               @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_DELETE, modelId, tagName);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        try {
            archimeshService.deleteModelTag(modelId, tagName);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("tagName", tagName);
            audit("AdminModelTagDelete", modelId, subject.userId(), context);
        } catch (IllegalStateException ex) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("tagName", tagName);
            context.put("status", Response.Status.CONFLICT.getStatusCode());
            context.put("error", ex.getMessage());
            audit("AdminModelTagDeleteRejected", modelId, subject.userId(), context);
            throw new WebApplicationException(ex.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("/models/{modelId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the current administrative status of a model including element counts
     * and consistency diagnostics.
     */
    public AdminStatus status(@PathParam("modelId") String modelId,
                              @Context HttpHeaders headers,
                              @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_STATUS_READ, modelId, null);
        return archimeshService.getAdminStatus(modelId);
    }

    @POST
    @Path("/models/{modelId}/rebuild-and-status")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Rebuilds the materialized state from the op-log and returns the updated status.
     */
    public AdminRebuildStatus rebuildAndStatus(@PathParam("modelId") String modelId,
                                               @Context HttpHeaders headers,
                                               @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_REBUILD, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminRebuildStatus result = archimeshService.rebuildAndGetAdminStatus(modelId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("snapshotHeadRevision", result.status() == null ? null : result.status().snapshotHeadRevision());
        context.put("consistent", result.status() != null
                && result.status().consistency() != null
                && result.status().consistency().consistent());
        audit("AdminModelRebuild", modelId, subject.userId(), context);
        return result;
    }

    @POST
    @Path("/models/{modelId}/compact")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Compacts metadata and op-log history for a model, reclaiming storage below the
     * configured retention watermark.
     */
    public AdminCompactionStatus compact(@PathParam("modelId") String modelId,
                                         @QueryParam("retainRevisions") Long retainRevisions,
                                         @QueryParam("dryRun") Boolean dryRun,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_COMPACT, modelId, null);
        // Compaction reclaims eligible metadata/op-log history according to committed-horizon watermark policy
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminCompactionStatus result = archimeshService.compactModelMetadata(modelId, retainRevisions, Boolean.TRUE.equals(dryRun));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("retainRevisions", result.retainRevisions());
        context.put("executed", result.executed());
        context.put("deletedCommitCount", result.deletedCommitCount());
        context.put("deletedOpCount", result.deletedOpCount());
        context.put("checkpointRevision", result.checkpointRevision());
        context.put("prunedBatchCount", result.prunedBatchCount());
        context.put("dryRun", result.dryRun());
        audit("AdminModelCompact", modelId, subject.userId(), context);
        return result;
    }

    @POST
    @Path("/models/{modelId}/checkpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public CheckpointSummary checkpoint(@PathParam("modelId") String modelId,
                                         @Context HttpHeaders headers, @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers,securityContext,AuthorizationAction.ADMIN_MODEL_CHECKPOINT_CREATE,modelId,null);
        try {
            CheckpointSummary result = archimeshService.createModelCheckpoint(modelId);
            audit("AdminModelCheckpoint",modelId,authorizationService.currentRestSubject(headers,securityContext).userId(),
                    Map.of("revision",result.revision(),"sha256",result.sha256()));
            return result;
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(),Response.Status.BAD_REQUEST);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(),Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("/models/{modelId}/checkpoints/latest")
    @Produces(MediaType.APPLICATION_JSON)
    public CheckpointSummary latestCheckpoint(@PathParam("modelId") String modelId,
                                               @Context HttpHeaders headers, @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers,securityContext,AuthorizationAction.ADMIN_MODEL_CHECKPOINT_READ,modelId,null);
        return archimeshService.getLatestModelCheckpoint(modelId).orElseThrow(() -> new NotFoundException("Model has no checkpoint"));
    }

    @GET
    @Path("/models/{modelId}/window")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns a comprehensive administrative window view of a model including sessions,
     * tags, ACL, status, integrity, and recent activity.
     */
    public AdminModelWindow window(@PathParam("modelId") String modelId,
                                   @QueryParam("limit") Integer limit,
                                   @Context HttpHeaders headers,
                                   @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_WINDOW_READ, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminModelWindow window = archimeshService.getAdminModelWindow(modelId, limit);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("limit", limit);
        context.put("activeSessionCount", window.activeSessionCount());
        audit("AdminWindowRead", modelId, subject.userId(), context);
        return window;
    }

    @GET
    @Path("/models/{modelId}/integrity")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Runs a referential integrity check on the model snapshot.
     */
    public AdminIntegrityReport integrity(@PathParam("modelId") String modelId,
                                          @Context HttpHeaders headers,
                                          @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_INTEGRITY_READ, modelId, null);
        return archimeshService.getAdminIntegrity(modelId);
    }

    @DELETE
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Deletes a model and all associated data, optionally forcing deletion when active
     * sessions exist.
     */
    public AdminDeleteResult deleteModel(@PathParam("modelId") String modelId,
                                         @QueryParam("force") Boolean force,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_DELETE, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminDeleteResult result = archimeshService.deleteModel(modelId, Boolean.TRUE.equals(force));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("force", Boolean.TRUE.equals(force));
        context.put("deleted", result.deleted());
        context.put("activeSessions", result.activeSessions());
        context.put("message", result.message());
        audit("AdminModelDelete", modelId, subject.userId(), context);
        return result;
    }

    @GET
    @Path("/models/{modelId}/export")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Exports the full model state as a portable package for backup or transfer.
     */
    public AdminModelExport exportModel(@PathParam("modelId") String modelId,
                                        @Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_EXPORT, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminModelExport result = archimeshService.exportModel(modelId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("headRevision", result.model() == null ? null : result.model().headRevision());
        context.put("opBatchCount", result.opBatches() == null ? 0 : result.opBatches().size());
        context.put("tagCount", result.tags() == null ? 0 : result.tags().size());
        audit("AdminModelExport", modelId, subject.userId(), context);
        return result;
    }

    @POST
    @Path("/models/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Imports a model from a previously exported package, optionally overwriting an
     * existing model with the same ID.
     */
    public AdminModelImportResult importModel(AdminModelExport exportPackage,
                                              @QueryParam("overwrite") Boolean overwrite,
                                              @Context HttpHeaders headers,
                                              @Context SecurityContext securityContext) {
        String modelId = exportPackage == null || exportPackage.model() == null ? null : exportPackage.model().modelId();
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_IMPORT,
                modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        try {
            AdminModelImportResult result = archimeshService.importModel(exportPackage, Boolean.TRUE.equals(overwrite));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("overwrite", Boolean.TRUE.equals(overwrite));
            context.put("headRevision", result.headRevision());
            context.put("importedOpBatchCount", result.importedOpBatchCount());
            context.put("importedTagCount", result.importedTagCount());
            context.put("overwritten", result.overwritten());
            audit("AdminModelImport", result.modelId(), subject.userId(), context);
            return result;
        } catch (IllegalStateException ex) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("overwrite", Boolean.TRUE.equals(overwrite));
            context.put("status", Response.Status.CONFLICT.getStatusCode());
            context.put("error", ex.getMessage());
            audit("AdminModelImportRejected", modelId, subject.userId(), context);
            throw new WebApplicationException(ex.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("overwrite", Boolean.TRUE.equals(overwrite));
            context.put("status", Response.Status.BAD_REQUEST.getStatusCode());
            context.put("error", ex.getMessage());
            audit("AdminModelImportRejected", modelId, subject.userId(), context);
            throw new WebApplicationException(ex.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns a lightweight aggregated overview of all active models for operator dashboards.
     */
    public List<AdminModelWindow> overview(@QueryParam("limit") Integer limit,
                                           @Context HttpHeaders headers,
                                           @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_OVERVIEW_READ, null, null);
        // Lightweight aggregated view for operator dashboards
        return archimeshService.getAdminOverview(limit);
    }

    private void audit(String action, String modelId, String userId, Map<String, Object> context) {
        AdminAuditEvent event = new AdminAuditEvent(
                Instant.now().toString(),
                action,
                sanitize(modelId, "-"),
                sanitize(userId, "anonymous"),
                context == null ? Map.of() : context);
        try {
            LOG.info("admin_audit {}", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            LOG.info("admin_audit action={} modelId={} userId={} context={}",
                    event.action(),
                    event.modelId(),
                    event.userId(),
                    event.context());
        }
    }

    private String sanitize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
