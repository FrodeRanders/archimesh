package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record AdminModelExport(
        String format,
        String exportedAt,
        ModelCatalogEntry model,
        ModelAccessControl accessControl,
        JsonNode snapshot,
        JsonNode opBatches,
        List<ModelTagExportEntry> tags,
        @JsonInclude(JsonInclude.Include.NON_NULL) ModelCheckpoint checkpoint,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<BatchReceipt> receipts) {
    public AdminModelExport(String format, String exportedAt, ModelCatalogEntry model,
            ModelAccessControl accessControl, JsonNode snapshot, JsonNode opBatches, List<ModelTagExportEntry> tags) {
        this(format, exportedAt, model, accessControl, snapshot, opBatches, tags, null, null);
    }
}
