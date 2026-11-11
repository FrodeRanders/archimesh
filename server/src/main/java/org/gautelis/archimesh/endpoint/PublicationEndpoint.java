package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.PublicationModel.*;
import java.util.function.Supplier;

@Path("/admin/models/{modelId}/publication/{targetKey}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PublicationEndpoint {
    @Inject ArchitecturePublicationService publication;
    @Inject AuthorizationService authorization;
    @GET public Response status(@PathParam("modelId") String model,@PathParam("targetKey") String target,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> publication.status(model,target,authorization.currentRestSubject(headers,context)));
    }
    @POST @Path("/preview") public Response preview(@PathParam("modelId") String model,@PathParam("targetKey") String target,PolicySpec spec,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> publication.preview(model,target,authorization.currentRestSubject(headers,context),spec));
    }
    @POST @Path("/policy") public Response approve(@PathParam("modelId") String model,@PathParam("targetKey") String target,Approval body,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("PolicyApproval",model,target,subject.userId(),() -> publication.approve(model,target,subject,body));
    }
    @POST @Path("/run") public Response run(@PathParam("modelId") String model,@PathParam("targetKey") String target,RunRequest request,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("QueuePublication",model,target,subject.userId(),() -> publication.enqueue(model,target,subject,request));
    }
    @GET @Path("/resource") public Response inspect(@PathParam("modelId") String model,@PathParam("targetKey") String target,@QueryParam("canonicalId") String canonical,@QueryParam("resourceId") String remote,@Context HttpHeaders headers,@Context SecurityContext context) {
        return invoke(() -> publication.inspect(model,target,authorization.currentRestSubject(headers,context),canonical,remote));
    }
    @POST @Path("/adopt-baseline") public Response adopt(@PathParam("modelId") String model,@PathParam("targetKey") String target,Adoption request,@Context HttpHeaders headers,@Context SecurityContext context) {
        var subject=authorization.currentRestSubject(headers,context);
        return mutation("AdoptBaseline",model,target,subject.userId(),() -> publication.adopt(model,target,subject,request));
    }
    private Response mutation(String action,String model,String target,String user,Supplier<?> work) {
        int status=500;
        try { var response=invoke(work); status=response.getStatus(); return response; }
        catch (WebApplicationException denied) { status=denied.getResponse().getStatus(); throw denied; }
        finally { org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action={} model={} target={} user={} status={}",action,auditKey(model),auditKey(target),auditKey(user),status); }
    }
    private String auditKey(String value) { return value!=null && value.matches("[a-zA-Z0-9_.:@/-]{1,512}") ? value : "[unavailable]"; }
    private Response invoke(Supplier<?> work) {
        try { return Response.ok(work.get()).header("Cache-Control","no-store").build(); }
        catch (Failure failure) { return error(failure.status(),failure.getMessage()); }
        catch (ConnectorException failure) { return error(failure.retryable() ? 503 : 502,"EXTERNAL_"+failure.kind()); }
        catch (org.neo4j.driver.exceptions.Neo4jException unavailable) { return error(409,"PUBLICATION_METADATA_CONFLICT_OR_UNAVAILABLE"); }
        catch (IllegalArgumentException invalid) { return error(400,"INVALID_PUBLICATION_REQUEST"); }
        catch (IllegalStateException conflict) { return error(409,"PUBLICATION_STATE_CONFLICT"); }
    }
    private Response error(int status,String code) { return Response.status(status).entity(new ExternalObjectEndpoint.Failure(code)).header("Cache-Control","no-store").build(); }
}
