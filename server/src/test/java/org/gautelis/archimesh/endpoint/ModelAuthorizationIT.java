package org.gautelis.archimesh.endpoint;

import org.gautelis.archimesh.service.ArchimeshService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
@TestProfile(AdminAuthorizationEnabledProfile.class)
class ModelAuthorizationIT {

    @TestHTTPResource
    URI baseUri;

    @Inject
    ArchimeshService archimeshService;

    private final List<String> modelsToDelete = new ArrayList<>();

    @Test
    void readerCatalogAndTagsRespectModelAclWithoutAdminRole() throws Exception {
        String visible = "catalog-reader-visible";
        String hidden = "catalog-reader-hidden";
        modelsToDelete.add(visible); modelsToDelete.add(hidden);
        archimeshService.registerModel(visible, "Visible", "owner");
        archimeshService.registerModel(hidden, "Hidden", "owner");
        archimeshService.updateModelAccessControl(visible, java.util.Set.of("owner"), java.util.Set.of(), java.util.Set.of("reader"));
        HttpResponse<String> catalog = get("/models", "reader", "model_reader");
        Assertions.assertEquals(200, catalog.statusCode());
        Assertions.assertTrue(catalog.body().contains(visible));
        Assertions.assertFalse(catalog.body().contains(hidden));
        Assertions.assertEquals(200, get("/models/" + visible + "/tags", "reader", "model_reader").statusCode());
        Assertions.assertEquals(403, get("/models/" + hidden + "/tags", "reader", "model_reader").statusCode());
    }

    @AfterEach
    void cleanup() {
        for (String modelId : modelsToDelete) {
            try {
                archimeshService.deleteModel(modelId, true);
            } catch (Exception ignored) {
            }
        }
        modelsToDelete.clear();
    }

    @Test
    void snapshotRequiresReaderWriterOrAdminRole() throws Exception {
        String modelId = "authz-snapshot-demo";
        modelsToDelete.add(modelId);
        archimeshService.registerModel(modelId, "Authz Snapshot Demo");

        HttpResponse<String> noHeaders = get("/models/" + modelId + "/snapshot", null, null);
        Assertions.assertEquals(403, noHeaders.statusCode());

        HttpResponse<String> reader = get("/models/" + modelId + "/snapshot", "reader", "model_reader");
        Assertions.assertEquals(200, reader.statusCode());

        HttpResponse<String> writer = get("/models/" + modelId + "/snapshot", "writer", "model_writer");
        Assertions.assertEquals(200, writer.statusCode());

        HttpResponse<String> admin = get("/models/" + modelId + "/snapshot", "admin-user", "admin");
        Assertions.assertEquals(200, admin.statusCode());
    }

    @Test
    void rebuildRemainsAdminOnlyWhenAuthorizationIsEnabled() throws Exception {
        String modelId = "authz-rebuild-demo";
        modelsToDelete.add(modelId);
        archimeshService.registerModel(modelId, "Authz Rebuild Demo");

        HttpResponse<String> reader = post("/models/" + modelId + "/rebuild", "reader", "model_reader");
        Assertions.assertEquals(403, reader.statusCode());

        HttpResponse<String> writer = post("/models/" + modelId + "/rebuild", "writer", "model_writer");
        Assertions.assertEquals(403, writer.statusCode());

        HttpResponse<String> admin = post("/models/" + modelId + "/rebuild", "admin-user", "admin");
        Assertions.assertTrue(admin.statusCode() >= 200 && admin.statusCode() < 300, admin.body());
    }

    @Test
    void modelAclOverridesGlobalReaderWriterRolesWhenConfigured() throws Exception {
        String modelId = "authz-acl-demo";
        modelsToDelete.add(modelId);
        archimeshService.registerModel(modelId, "Authz ACL Demo", "owner-user");
        archimeshService.updateModelAccessControl(modelId, java.util.Set.of("owner-user"), java.util.Set.of("writer-user"), java.util.Set.of("reader-user"));

        HttpResponse<String> globalRoleDenied = get("/models/" + modelId + "/snapshot", "other-writer", "model_writer");
        Assertions.assertEquals(403, globalRoleDenied.statusCode());

        HttpResponse<String> aclReaderAllowed = get("/models/" + modelId + "/snapshot", "reader-user", null);
        Assertions.assertEquals(200, aclReaderAllowed.statusCode());

        HttpResponse<String> aclWriterAllowed = get("/models/" + modelId + "/snapshot", "writer-user", null);
        Assertions.assertEquals(200, aclWriterAllowed.statusCode());
    }

    private HttpResponse<String> get(String path, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (user != null) {
            builder.header("X-Archimesh-User", user);
        }
        if (roles != null) {
            builder.header("X-Archimesh-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (user != null) {
            builder.header("X-Archimesh-User", user);
        }
        if (roles != null) {
            builder.header("X-Archimesh-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
