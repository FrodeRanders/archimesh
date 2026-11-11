package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.ExternalChangeIngestService.*;
import java.util.function.Supplier;

@Path("/admin/models/{modelId}/inbound")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InboundEndpoint {
    @Inject ExternalChangeIngestService inbound;
    @Inject AuthorizationService authorization;
    @GET public Response targets(@PathParam("modelId") String model,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> inbound.targets(model,authorization.currentRestSubject(headers,context)));
    }
    @GET @Path("/{target}") public Response status(@PathParam("modelId") String model,@PathParam("target") String target,@QueryParam("after") @DefaultValue("") String after,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> inbound.status(model,target,authorization.currentRestSubject(headers,context),after));
    }
    @POST @Path("/{target}/policy") public Response approve(@PathParam("modelId") String model,@PathParam("target") String target,Approval body,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("InboundPolicy",model,target,subject.userId(),() -> inbound.approve(model,target,subject,body));
    }
    @POST @Path("/{target}/refresh/{element}") public Response refresh(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("element") String element,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("InboundRefresh",model,target,subject.userId(),() -> { inbound.refresh(model,target,subject,element); return java.util.Map.of("elementId",element); });
    }
    @GET @Path("/{target}/resource/{element}") public Response inspect(@PathParam("modelId") String model,@PathParam("target") String target,@PathParam("element") String element,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> inbound.inspect(model,target,authorization.currentRestSubject(headers,context),element));
    }
    @POST @Path("/{target}/accept-remote") public Response adopt(@PathParam("modelId") String model,@PathParam("target") String target,Resolution body,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("InboundAcceptRemote",model,target,subject.userId(),() -> { inbound.acceptRemote(model,target,subject,body); return java.util.Map.of("state","VERIFIED"); });
    }
    private Response mutation(String action,String model,String target,String user,Supplier<?> work) {
        int status=500;
        try { var response=invoke(work); status=response.getStatus(); return response; }
        catch (WebApplicationException denied) { status=denied.getResponse().getStatus(); throw denied; }
        finally { org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action={} model={} target={} user={} status={}",action,audit(model),audit(target),audit(user),status); }
    }
    private String audit(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
    private Response invoke(Supplier<?> work) {
        try { return Response.ok(work.get()).header("Cache-Control","no-store").build(); }
        catch (PublicationModel.Failure failure) { return error(failure.status(),failure.getMessage()); }
        catch (ConnectorException failure) { return error(failure.retryable() ? 503 : 502,"EXTERNAL_"+failure.kind()); }
        catch (org.neo4j.driver.exceptions.Neo4jException failure) { return error(409,"INBOUND_METADATA_CONFLICT_OR_UNAVAILABLE"); }
        catch (IllegalArgumentException failure) { return error(400,"INVALID_INBOUND_REQUEST"); }
        catch (IllegalStateException failure) { return error(409,"INBOUND_STATE_CONFLICT"); }
    }
    private Response error(int status,String code) { return Response.status(status).entity(new ExternalObjectEndpoint.Failure(code)).header("Cache-Control","no-store").build(); }
}
