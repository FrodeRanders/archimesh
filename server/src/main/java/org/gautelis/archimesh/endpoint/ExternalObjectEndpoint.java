package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.ConnectorException;
import org.gautelis.archimesh.integration.ExternalObjectCatalogService;
import java.util.function.Supplier;

@Path("/models/{modelId}/external-objects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExternalObjectEndpoint {
    @Inject ExternalObjectCatalogService catalog;
    @Inject AuthorizationService authorization;
    public record Failure(String code) { }

    @GET @Path("/targets")
    public Response targets(@PathParam("modelId") String model,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> catalog.targets(model,authorization.currentRestSubject(headers,context)));
    }
    @GET @Path("/catalog")
    public Response search(@PathParam("modelId") String model,@QueryParam("target") String target,@QueryParam("type") String type,
                           @QueryParam("query") String query,@QueryParam("page") @DefaultValue("1") int page,
                           @QueryParam("pageSize") @DefaultValue("25") int pageSize,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> catalog.catalog(model,authorization.currentRestSubject(headers,context),target,type,query,page,pageSize));
    }
    @GET @Path("/preview")
    public Response preview(@PathParam("modelId") String model,@QueryParam("target") String target,@QueryParam("cardId") String card,
                            @QueryParam("ref") @DefaultValue("HEAD") String ref,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> catalog.preview(model,ref,authorization.currentRestSubject(headers,context),target,card));
    }
    @POST @Path("/import")
    public Response importObject(@PathParam("modelId") String model,ExternalObjectCatalogService.ImportRequest request,
                                 @Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        Response response;
        try { response=invoke(() -> catalog.importObject(model,subject,request)); }
        catch (WebApplicationException denied) { audit(model,subject.userId(),request,denied.getResponse().getStatus()); throw denied; }
        audit(model,subject.userId(),request,response.getStatus());
        return response;
    }
    @GET @Path("/{elementId}/status")
    public Response status(@PathParam("modelId") String model,@PathParam("elementId") String element,
                           @Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> catalog.status(model,authorization.currentRestSubject(headers,context),element));
    }
    private Response invoke(Supplier<?> work) {
        try { return Response.ok(work.get()).header("Cache-Control","no-store").build(); }
        catch (ExternalObjectCatalogService.RequestFailure error) { return failure(error.status(),error.getMessage()); }
        catch (ConnectorException error) { return failure(error.retryable() ? 503 : 502,"EXTERNAL_"+error.kind().name()); }
        catch (org.neo4j.driver.exceptions.ClientException conflict) {
            return conflict.code().startsWith("Neo.ClientError.Schema.Constraint") ? failure(409,"IDENTITY_CONFLICT")
                    : failure(503,"INTEGRATION_STATE_UNAVAILABLE");
        }
        catch (IllegalArgumentException invalid) { return failure(400,"INVALID_REQUEST"); }
        catch (IllegalStateException unavailable) { return failure(503,"INTEGRATION_STATE_UNAVAILABLE"); }
    }
    private Response failure(int status,String code) { return Response.status(status).entity(new Failure(code)).header("Cache-Control","no-store").build(); }
    private void audit(String model,String user,ExternalObjectCatalogService.ImportRequest request,int status) {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=Import model={} user={} target={} resource={} request={} status={}",
                auditKey(model),auditKey(user),auditKey(request==null ? null : request.targetKey()),
                auditKey(request==null ? null : request.cardId()),auditKey(request==null ? null : request.requestId()),status);
    }
    private String auditKey(String value) { return value!=null && value.matches("[\\p{L}\\p{N}_.:@/\\-]{1,512}") ? value : "[unavailable]"; }
}
