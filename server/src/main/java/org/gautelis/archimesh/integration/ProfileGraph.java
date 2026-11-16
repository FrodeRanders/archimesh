package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Accepted semantic graph only. Geometry, folders, local documentation and schemas are not graph edges. */
public final class ProfileGraph {
    public record Node(String id,String type) { }
    public record Edge(String id,String type,String source,String target) { }
    private final SortedMap<String,Node> nodes=new TreeMap<>();
    private final SortedMap<String,Edge> edges=new TreeMap<>();
    private final Map<String,SortedMap<String,String>> properties=new TreeMap<>();
    public ProfileGraph(JsonNode snapshot,PublicationProfile.Limits limits) {
        if(snapshot.path("elements").size()>limits.maxGraphElements() || snapshot.path("relationships").size()>limits.maxGraphRelationships()
                || snapshot.path("propertyState").size()>100000) throw new Failure(413,"PROFILE_GRAPH_LIMIT_EXCEEDED");
        for(var value:snapshot.path("elements")) {
            String id=value.path("id").asText();
            if(nodes.put(id,new Node(id,value.path("archimateType").asText()))!=null) throw new Failure(409,"PROFILE_DUPLICATE_SOURCE_ID");
        }
        for(var value:snapshot.path("relationships")) {
            String id=value.path("id").asText();
            if(edges.put(id,new Edge(id,value.path("archimateType").asText(),value.path("sourceId").asText(),value.path("targetId").asText()))!=null)
                throw new Failure(409,"PROFILE_DUPLICATE_SOURCE_ID");
        }
        for(var value:snapshot.path("propertyState")) if("SetProperty".equals(value.path("type").asText())) {
            var target=properties.computeIfAbsent(value.path("targetId").asText(),ignored -> new TreeMap<>());
            String key=value.path("key").asText(), text=value.path("value").isTextual() ? value.path("value").asText() : value.path("value").toString();
            if(target.put(key,text)!=null) throw new Failure(409,"PROFILE_DUPLICATE_SOURCE_PROPERTY");
        }
    }
    public Map<String,Node> nodes() { return Collections.unmodifiableSortedMap(nodes); }
    public Collection<Edge> edges() { return Collections.unmodifiableCollection(edges.values()); }
    public boolean type(String id,String type) { return nodes.containsKey(id) && nodes.get(id).type().equals(type); }
    public String property(String id,String key) { return properties.getOrDefault(id,Collections.emptySortedMap()).get(key); }
}
