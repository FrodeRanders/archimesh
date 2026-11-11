package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Server-internal reads only in M1. This contract grants no end-user catalog access. */
public interface ExternalRepositoryConnector {
    record ExternalObject(String id, String type, String name, String description, String externalId,
                          String status, JsonNode attributes, JsonNode lifecycle) { }
    record ExternalRelation(String id, String type, String sourceId, String targetId,
                            String description, JsonNode attributes) { }
    record CatalogPage(List<ExternalObject> items, long total, int page, int pageSize, List<String> withheldIds) {
        public CatalogPage { items = List.copyOf(items); withheldIds = List.copyOf(withheldIds); }
    }
    record CardType(String key, JsonNode fieldsSchema, boolean hidden) { }
    record RelationType(String key, String sourceType, String targetType, String cardinality,
                        JsonNode attributesSchema, boolean hidden) { }
    record Metamodel(Map<String, CardType> cardTypes, Map<String, RelationType> relationTypes) {
        public Metamodel { cardTypes = Map.copyOf(cardTypes); relationTypes = Map.copyOf(relationTypes); }
    }

    String healthVersion();
    CatalogPage search(String search, String type, int page, int pageSize);
    ExternalObject getObject(String resourceId);
    List<ExternalRelation> relations(String resourceId);
    Metamodel metamodel();
}
