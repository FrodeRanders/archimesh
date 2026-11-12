package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.CentralEditModel.PreviewRequest;
import org.gautelis.archimesh.integration.CentralEditModel.Request;
import org.gautelis.archimesh.integration.CentralEditModel.Decision;
import org.gautelis.archimesh.integration.CentralEditModel.Outcome;
import java.util.function.Supplier;

@Path("/models/{modelId}/central-edits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CentralEditEndpoint {
    @Inject CentralEditService edits;
    @Inject AuthorizationService authorization;
    @Context HttpHeaders auditHeaders;
    @Context SecurityContext auditContext;
    @Context jakarta.ws.rs.core.Request auditRequest;
    @PathParam("modelId") String auditModel;
    @PathParam("target") String auditTarget;
    @GET @Path("/targets") public Response targets(@PathParam("modelId") String model,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.targets(model,authorization.currentRestSubject(headers,context))); }
    @GET @Path("/{target}/capabilities") public Response capabilities(@PathParam("modelId") String model,@PathParam("target") String target,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.capabilities(model,target,authorization.currentRestSubject(headers,context))); }
    @GET @Path("/{target}/bindings") public Response bindings(@PathParam("modelId") String model,@PathParam("target") String target,@QueryParam("after") @DefaultValue("") String after,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.bindings(model,target,authorization.currentRestSubject(headers,context),after)); }
    @GET @Path("/{target}/context/{element}") public Response context(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("element") String element,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.context(model,target,element,authorization.currentRestSubject(headers,context))); }
    @POST @Path("/{target}/preview") public Response preview(@PathParam("modelId") String model,@PathParam("target") String target,PreviewRequest request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.preview(model,target,authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/{target}/requests") public Response submit(@PathParam("modelId") String model,@PathParam("target") String target,Request request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.submit(model,target,authorization.currentRestSubject(headers,context),request)); }
    @GET @Path("/{target}/requests") public Response list(@PathParam("modelId") String model,@PathParam("target") String target,@QueryParam("after") @DefaultValue("") String after,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.list(model,target,authorization.currentRestSubject(headers,context),after)); }
    @GET @Path("/{target}/requests/{id}") public Response get(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.get(model,target,id,authorization.currentRestSubject(headers,context))); }
    @GET @Path("/{target}/requests/{id}/review") public Response review(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.review(model,target,id,authorization.currentRestSubject(headers,context))); }
    @POST @Path("/{target}/requests/{id}/decision") public Response decide(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,Decision request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.decide(model,target,id,authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/{target}/requests/{id}/cancel") public Response cancel(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,Outcome request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.cancel(model,target,id,authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/{target}/requests/{id}/verify-outcome") public Response verify(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,Outcome request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.verifyOutcome(model,target,id,authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/{target}/requests/{id}/retry-fanout") public Response retry(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,Outcome request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.retryFanout(model,target,id,authorization.currentRestSubject(headers,context),request)); }
    @GET @Path("/{target}/deliveries") public Response deliveries(@PathParam("modelId") String model,@PathParam("target") String target,@QueryParam("after") @DefaultValue("") String after,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.deliveries(model,target,authorization.currentRestSubject(headers,context),after)); }
    @GET @Path("/{target}/incoming/{id}/{element}") public Response inspectDelivery(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,@PathParam("element") String element,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> edits.inspectDelivery(model,target,authorization.currentRestSubject(headers,context),element,id)); }
    @POST @Path("/{target}/incoming/{id}/{element}") public Response resolveDelivery(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("id") String id,@PathParam("element") String element,ExternalChangeIngestService.Resolution request,@Context HttpHeaders headers,@Context SecurityContext context) { return invoke(() -> { if (request==null || !element.equals(request.elementId())) throw new PublicationModel.Failure(400,"CENTRAL_DELIVERY_REQUIRED"); edits.resolveDelivery(model,target,authorization.currentRestSubject(headers,context),element,id,request.inspectionDigest()); return java.util.Map.of("state","VERIFIED"); }); }
    private Response invoke(Supplier<?> work) {
        try { return Response.ok(work.get()).header("Cache-Control","no-store").build(); }
        catch (PublicationModel.Failure failure) { return error(failure.status(),failure.getMessage()); }
        catch (WebApplicationException denied) {
            int status=denied.getResponse().getStatus();
            if (status!=401 && status!=403) throw denied;
            return error(status,"CENTRAL_EDIT_ACCESS_DENIED");
        }
        catch (ConnectorException failure) { return error(failure.retryable() ? 503 : 502,"EXTERNAL_"+failure.kind()); }
        catch (org.neo4j.driver.exceptions.Neo4jException failure) { return error(409,"CENTRAL_METADATA_CONFLICT_OR_UNAVAILABLE"); }
        catch (IllegalArgumentException failure) { return error(400,"INVALID_CENTRAL_REQUEST"); }
        catch (IllegalStateException failure) { return error(409,"CENTRAL_STATE_CONFLICT"); }
    }
    private Response error(int status,String code) {
        String actor="[unavailable]";
        try { actor=authorization.currentRestSubject(auditHeaders,auditContext).userId(); } catch (RuntimeException unavailable) { }
        org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=CentralRequestDenied actor={} model={} target={} method={} status={} code={}",
                safe(actor),safe(auditModel),safe(auditTarget),auditRequest==null ? "[unavailable]" : auditRequest.getMethod(),status,safe(code));
        return Response.status(status).entity(new ExternalObjectEndpoint.Failure(code)).header("Cache-Control","no-store").build();
    }
    private String safe(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
}
