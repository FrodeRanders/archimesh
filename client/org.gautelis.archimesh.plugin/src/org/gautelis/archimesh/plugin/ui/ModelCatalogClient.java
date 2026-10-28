package org.gautelis.archimesh.plugin.ui;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.gautelis.archimesh.plugin.util.ArchimeshAuthHints;
import org.gautelis.archimesh.plugin.util.SimpleJson;

/**
 * Lightweight admin catalog client used by connection dialogs.
 */
public final class ModelCatalogClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private ModelCatalogClient() {
    }

    public static List<ModelOption> fetchModels(String wsBaseUrl) throws IOException, InterruptedException {
        return fetchModels(wsBaseUrl, null);
    }

    public static List<ModelOption> fetchModels(String wsBaseUrl, String bearerToken) throws IOException, InterruptedException {
        URI overviewUri = adminUri(wsBaseUrl, "/models");

        HttpRequest request = authorizedRequest(overviewUri, bearerToken)
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(ArchimeshAuthHints.describeHttpFailure("Loading model catalog", response.statusCode(),
                    bearerToken != null && !bearerToken.isBlank()));
        }

        List<String> items = SimpleJson.splitArrayObjects(response.body());
        List<ModelOption> result = new ArrayList<>();
        for(String item : items) {
            String modelId = SimpleJson.readStringField(item, "modelId");
            if(modelId == null || modelId.isBlank()) {
                continue;
            }
            // Optional future-compatible display name fields.
            String modelName = SimpleJson.readStringField(item, "modelName");
            if(modelName == null || modelName.isBlank()) {
                modelName = SimpleJson.readStringField(item, "name");
            }
            result.add(new ModelOption(modelId, modelName));
        }
        return result;
    }

    public static List<ModelTagOption> fetchTags(String wsBaseUrl, String modelId) throws IOException, InterruptedException {
        return fetchTags(wsBaseUrl, modelId, null);
    }

    public static List<ModelTagOption> fetchTags(String wsBaseUrl, String modelId, String bearerToken) throws IOException, InterruptedException {
        if(modelId == null || modelId.isBlank()) {
            throw new IOException("Model ID is required");
        }
        URI tagsUri = adminUri(wsBaseUrl, "/models/" + modelId + "/tags");

        HttpRequest request = authorizedRequest(tagsUri, bearerToken)
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(ArchimeshAuthHints.describeHttpFailure("Loading model tags", response.statusCode(),
                    bearerToken != null && !bearerToken.isBlank()));
        }

        List<String> items = SimpleJson.splitArrayObjects(response.body());
        List<ModelTagOption> result = new ArrayList<>();
        for(String item : items) {
            String tagName = SimpleJson.readStringField(item, "tagName");
            if(tagName == null || tagName.isBlank()) {
                continue;
            }
            String description = SimpleJson.readStringField(item, "description");
            String createdAt = SimpleJson.readStringField(item, "createdAt");
            long revision = parseLongField(SimpleJson.readRawField(item, "revision"));
            result.add(new ModelTagOption(tagName, description, revision, createdAt));
        }
        return result;
    }

    private static URI adminUri(String wsBaseUrl, String path) throws IOException {
        URI wsUri = normalizeUri(wsBaseUrl);
        String scheme = wsUri.getScheme();
        String httpScheme = "wss".equalsIgnoreCase(scheme) ? "https" : "http";
        try {
            return new URI(
                    httpScheme,
                    wsUri.getUserInfo(),
                    wsUri.getHost(),
                    wsUri.getPort(),
                    path,
                    null,
                    null);
        }
        catch(URISyntaxException ex) {
            throw new IOException("Invalid admin URI from WebSocket base URL", ex);
        }
    }

    private static long parseLongField(String value) {
        if(value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch(NumberFormatException ex) {
            return 0L;
        }
    }

    public static URI normalizeUri(String value) throws IOException {
        if(value == null || value.isBlank()) {
            throw new IOException("WebSocket base URL is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        }
        catch(IllegalArgumentException ex) {
            throw new IOException("Invalid WebSocket base URL", ex);
        }
        String scheme = uri.getScheme();
        if(scheme == null || (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme))) {
            throw new IOException("WebSocket base URL must use ws:// or wss://");
        }
        if(uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("WebSocket base URL must include host");
        }
        return uri;
    }

    private static HttpRequest.Builder authorizedRequest(URI uri, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5));
        if(bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken.trim());
        }
        return builder;
    }

    public record ModelOption(String modelId, String modelName) {
        public String label() {
            if(modelName != null && !modelName.isBlank() && !modelName.equals(modelId)) {
                return modelName + " (" + modelId + ")";
            }
            return modelId;
        }
    }

    public record ModelTagOption(String tagName, String description, long revision, String createdAt) {
        public String ref() {
            return tagName;
        }

        public String label() {
            StringBuilder label = new StringBuilder(tagName);
            label.append(" (rev ").append(revision).append(", read-only");
            if(description != null && !description.isBlank()) {
                label.append(", ").append(description);
            }
            label.append(")");
            return label.toString();
        }
    }
}
