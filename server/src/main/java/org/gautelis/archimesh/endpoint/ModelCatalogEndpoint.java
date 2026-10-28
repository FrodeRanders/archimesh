package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.model.*;
import org.gautelis.archimesh.service.ArchimeshService;
import java.util.List;

/** User-facing discovery is filtered by model read access, independently of admin privileges. */
@Path("/models")
@Produces(MediaType.APPLICATION_JSON)
public class ModelCatalogEndpoint {
    @Inject ArchimeshService service;
    @Inject AuthorizationService authorization;

    @GET
    public List<ModelCatalogEntry> models(@Context HttpHeaders headers, @Context SecurityContext context) {
        return service.getModelCatalog().stream().filter(model -> {
            try {
                authorization.requireRestAllowed(headers, context, AuthorizationAction.MODEL_SNAPSHOT_READ, model.modelId(), "HEAD");
                return true;
            } catch (WebApplicationException denied) {
                if (denied.getResponse().getStatus() != 403) throw denied;
                return false;
            }
        }).toList();
    }

    @GET
    @Path("/{modelId}/tags")
    public List<ModelTagEntry> tags(@PathParam("modelId") String modelId,
                                   @Context HttpHeaders headers, @Context SecurityContext context) {
        authorization.requireRestAllowed(headers, context, AuthorizationAction.MODEL_SNAPSHOT_READ, modelId, "HEAD");
        return service.getModelTags(modelId);
    }
}
