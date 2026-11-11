package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.ArchitecturePublicationService;
import java.util.List;

@Path("/admin/models/{modelId}/publication")
@Produces(MediaType.APPLICATION_JSON)
public class PublicationCatalogEndpoint {
    @Inject ArchitecturePublicationService publication;
    @Inject AuthorizationService authorization;
    @GET public Response targets(@PathParam("modelId") String model,@Context HttpHeaders headers,@Context SecurityContext context) {
        try { return Response.ok(publication.targets(model,authorization.currentRestSubject(headers,context))).header("Cache-Control","no-store").build(); }
        catch (org.gautelis.archimesh.integration.PublicationModel.Failure failure) {
            return Response.status(failure.status()).entity(new ExternalObjectEndpoint.Failure(failure.getMessage())).header("Cache-Control","no-store").build();
        }
    }
}
