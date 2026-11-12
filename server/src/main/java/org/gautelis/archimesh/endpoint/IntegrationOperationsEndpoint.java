package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.IntegrationOperationsService.*;
import org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import java.util.function.Supplier;

@Path("/admin/integrations") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class IntegrationOperationsEndpoint {
    @Inject IntegrationOperationsService operations;
    @Inject AuthorizationService authorization;
    @Context HttpHeaders headers;
    @Context SecurityContext context;
    private AuthorizationSubject subject() { return authorization.currentRestSubject(headers,context); }
    @GET public Response overview() { return invoke(() -> operations.overview(subject())); }
    @GET @Path("/documents/{kind}") public Response documents(@PathParam("kind") String kind,@QueryParam("after") @DefaultValue("") String after) { return invoke(() -> operations.documents(subject(),DocumentKind.valueOf(kind),after)); }
    @POST @Path("/probe") public Response probe() { return invoke(() -> operations.probe(subject())); }
    @POST @Path("/pause") public Response pause(Decision request) { return invoke(() -> { if (request==null) throw new IllegalArgumentException(); return operations.pause(subject(),request.expectedVersion()); }); }
    @POST @Path("/resume") public Response resume(Decision request) { return invoke(() -> operations.resume(subject(),request)); }
    @POST @Path("/archive/export") public Response export() { return invoke(() -> operations.exportArchive(subject())); }
    @POST @Path("/archive/preview") public Response preview(IntegrationArchive.Package request) { return invoke(() -> operations.previewRestore(subject(),request)); }
    @POST @Path("/archive/restore") public Response restore(RestoreRequest request) { return invoke(() -> operations.restore(subject(),request)); }
    @GET @Path("/models/{model}/review") public Response model(@PathParam("model") String model) { return invoke(() -> operations.previewAttachment(subject(),model)); }
    @POST @Path("/models/{model}/pause") public Response pauseModel(@PathParam("model") String model,Decision request) { return invoke(() -> { if (request==null) throw new IllegalArgumentException(); return operations.pauseModel(subject(),model,request.expectedVersion()); }); }
    @POST @Path("/models/{model}/attach") public Response attach(@PathParam("model") String model,Decision request) { return invoke(() -> operations.attach(subject(),model,request)); }
    @POST @Path("/reconcile") public Response reconcile(ScanRequest request) { return invoke(() -> operations.scan(subject(),request)); }
    @GET @Path("/metrics") @Produces("text/plain; version=0.0.4") public Response metrics() {
        return invoke(() -> {
            var data=operations.overview(subject()); var text=new StringBuilder("# TYPE archimesh_integration_paused gauge\narchimesh_integration_paused ").append(data.path("paused").asBoolean() ? 1 : 0).append('\n');
            text.append("# TYPE archimesh_integration_documents gauge\n");
            for (var row:data.path("counts").path("documents")) if (safeLabel(row.path("kind").asText()) && safeLabel(row.path("state").asText())) {
                String labels="{kind=\""+row.path("kind").asText()+"\",state=\""+row.path("state").asText()+"\"}";
                text.append("archimesh_integration_documents").append(labels).append(' ').append(row.path("count").asLong()).append('\n');
                if (row.has("oldestVerifiedAt")) text.append("archimesh_integration_oldest_verification_age_seconds").append(labels).append(' ').append(Math.max(0,(System.currentTimeMillis()-row.path("oldestVerifiedAt").asLong())/1000)).append('\n');
                if (row.has("nextRetryAt")) text.append("archimesh_integration_next_retry_timestamp_seconds").append(labels).append(' ').append(row.path("nextRetryAt").asLong()/1000).append('\n');
            }
            for (var row:data.path("counts").path("bindings")) if (safeLabel(row.path("state").asText())) text.append("archimesh_integration_bindings{state=\"").append(row.path("state").asText()).append("\"} ").append(row.path("count").asLong()).append('\n');
            return text.toString();
        });
    }
    private boolean safeLabel(String text) { return text.matches("[A-Z_]{1,64}"); }
    private Response invoke(Supplier<?> action) {
        try { return Response.ok(action.get()).header("Cache-Control","no-store").build(); }
        catch (PublicationModel.Failure error) { return error(error.status(),error.getMessage()); }
        catch (ConnectorException error) { return error(error.retryable() ? 503 : 502,"EXTERNAL_"+error.kind()); }
        catch (WebApplicationException error) { if (error.getResponse().getStatus()!=403 && error.getResponse().getStatus()!=401) throw error; return error(error.getResponse().getStatus(),"OPERATIONS_ACCESS_DENIED"); }
        catch (IllegalArgumentException error) { return error(400,"INVALID_OPERATIONS_REQUEST"); }
        catch (IllegalStateException | org.neo4j.driver.exceptions.Neo4jException error) { return error(409,"OPERATIONS_CONFLICT_OR_UNAVAILABLE"); }
    }
    private Response error(int status,String code) {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("integration_audit action=OperationsDenied status={} code={}",status,code);
        return Response.status(status).entity(new ExternalObjectEndpoint.Failure(code)).header("Cache-Control","no-store").build();
    }
}
