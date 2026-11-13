package org.gautelis.archimesh.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.function.Supplier;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.federation.*;
import org.gautelis.archimesh.integration.federation.FederationOwnerService.*;
import org.gautelis.federation.http.RepositoryFailure;

@Path("/federation/v1") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class FederationEndpoint {
    @Inject FederationOwnerService owner;
    @Inject DatasetProjectionService projections;
    @Inject AuthorizationService authorization;
    @Context HttpHeaders headers;
    @Context SecurityContext context;
    @POST @Path("/identities/lookup") public Response lookup(SourceRequest request) { return invoke(() -> owner.lookup(authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/sources/observe") public Response observe(SourceRequest request) { return invoke(() -> owner.observe(authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/sources/reserve") public Response reserve(Reservation request) { return invoke(() -> owner.reserve(authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/projections/prepare") public Response prepare(DatasetProjectionService.Request request) { return invoke(() -> projections.prepare(authorization.currentRestSubject(headers,context),request)); }
    @POST @Path("/projections/execute") public Response execute(DatasetProjectionService.Execution request) { return invoke(() -> projections.execute(authorization.currentRestSubject(headers,context),request)); }
    private Response invoke(Supplier<?> action) {
        try { return Response.ok(action.get()).header("Cache-Control","no-store").build(); }
        catch (PublicationModel.Failure failure) { return error(failure.status(),failure.getMessage()); }
        catch (RepositoryFailure failure) { return error(failure.retryable() ? 503 : failure.kind()==RepositoryFailure.Kind.AUTHENTICATION || failure.kind()==RepositoryFailure.Kind.AUTHORIZATION ? 403 : failure.kind()==RepositoryFailure.Kind.NOT_FOUND ? 404 : 502,"SOURCE_"+failure.kind()); }
        catch (ConnectorException failure) { return error(failure.retryable() ? 503 : failure.kind()==ConnectorException.Kind.AUTHENTICATION || failure.kind()==ConnectorException.Kind.AUTHORIZATION ? 403 : failure.kind()==ConnectorException.Kind.NOT_FOUND ? 404 : 502,"SOURCE_"+failure.kind()); }
        catch (IllegalArgumentException failure) { return error(400,"INVALID_FEDERATION_REQUEST"); }
        catch (IllegalStateException | org.neo4j.driver.exceptions.Neo4jException failure) { return error(409,"FEDERATION_CONFLICT_OR_UNAVAILABLE"); }
    }
    private Response error(int status,String code) {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("federation_audit status={} code={}",status,code);
        return Response.status(status).entity(new ExternalObjectEndpoint.Failure(code)).header("Cache-Control","no-store").build();
    }
}
