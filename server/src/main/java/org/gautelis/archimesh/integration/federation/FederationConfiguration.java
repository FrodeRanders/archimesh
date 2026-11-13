package org.gautelis.archimesh.integration.federation;

import java.net.URI;
import java.util.*;
import org.gautelis.federation.Identity;
import org.gautelis.federation.Identity.Repository;
import org.gautelis.federation.http.SourceReader.Field;

/** Separate, explicit source-read/identity scopes. No bridge scheduler or projection jobs. */
public record FederationConfiguration(int schemaVersion,String ownerInstanceId,List<Source> sources,List<Grant> grants,
                                      List<Projection> projections) {
    public FederationConfiguration(int schemaVersion,String owner,List<Source> sources,List<Grant> grants) {
        this(schemaVersion,owner,sources,grants,List.of());
    }
    /** Reviewed standing approval for a bounded source type/audience and its owned target fields. */
    public record Projection(String key,String sourceKey,String externalType,String targetKey,String targetType,
                             Set<String> principals,Set<String> nativePrefixes,List<String> descriptionFields,
                             boolean approved,boolean acceptUnconditionalWriteRisk,String approvalVersion) {
        public Projection {
            for(String value:List.of(key,sourceKey,externalType,targetKey,targetType,approvalVersion)) Identity.text(value,128);
            principals=Set.copyOf(principals); nativePrefixes=Set.copyOf(nativePrefixes);
            descriptionFields=descriptionFields==null ? List.of("documentation") : List.copyOf(descriptionFields);
            if(!externalType.equals("dataset") || principals.isEmpty() || principals.size()>32
                    || nativePrefixes.isEmpty() || nativePrefixes.size()>32 || descriptionFields.isEmpty() || descriptionFields.size()>4)
                throw new IllegalArgumentException("Explicit bounded dataset projection scope required");
            principals.forEach(p -> Identity.text(p,256));
            nativePrefixes.forEach(p -> { Identity.text(p,2048); if(!p.startsWith("urn:li:dataset:(")) throw new IllegalArgumentException("Dataset URN prefix required"); });
        }
    }
    public enum Operation { READ, REGISTER_SOURCE }
    public record Type(String semanticKind,String archimateType,Map<String,Field> fields) {
        public Type {
            Identity.text(semanticKind,512); Identity.text(archimateType,512); fields=Map.copyOf(fields);
            if (!fields.containsKey("name") || fields.size()>32) throw new IllegalArgumentException("Source type needs bounded name mapping");
            fields.keySet().forEach(field -> { if (!(field.equals("name") || field.equals("documentation") || field.startsWith("property:") && field.length()>9)) throw new IllegalArgumentException("Unsupported local field"); Identity.text(field,512); });
        }
    }
    public record Source(String key,Repository repository,String turboTargetKey,URI baseUrl,String tokenEnvironment,
                         int timeoutMillis,int maxResponseBytes,boolean approvedPlainHttp,Map<String,Type> types) {
        public Source {
            Identity.text(key,128); Objects.requireNonNull(repository); types=Map.copyOf(types);
            if (types.isEmpty() || types.size()>32 || timeoutMillis<1 || timeoutMillis>120000 || maxResponseBytes<1024 || maxResponseBytes>16_777_216) throw new IllegalArgumentException("Invalid source bounds");
            types.keySet().forEach(type -> Identity.text(type,128));
            if (repository.system().equals("datahub")) {
                if (turboTargetKey!=null || baseUrl==null || tokenEnvironment==null || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}")
                        || !Set.of("dataset","dataProduct","application").containsAll(types.keySet())) throw new IllegalArgumentException("Invalid DataHub source");
            } else if (repository.system().equals("turboea")) {
                Identity.text(turboTargetKey,128); if (baseUrl!=null || tokenEnvironment!=null || approvedPlainHttp) throw new IllegalArgumentException("Reuse configured Turbo EA transport");
                if (types.values().stream().flatMap(type -> type.fields().values().stream()).anyMatch(field -> !field.aspect().equals("card"))) throw new IllegalArgumentException("Turbo EA fields require card aspect");
            } else throw new IllegalArgumentException("Unsupported source system");
        }
    }
    public record Grant(String principal,String requiredRole,Set<Operation> operations,Map<String,Set<String>> resources) {
        public Grant {
            Identity.text(principal,256); Identity.text(requiredRole,128); operations=Set.copyOf(operations);
            var copy=new TreeMap<String,Set<String>>(); resources.forEach((key,ids) -> {
                Identity.text(key,128); var values=Set.copyOf(ids); if (values.isEmpty() || values.size()>256) throw new IllegalArgumentException("Bounded exact resource scope required");
                values.forEach(id -> Identity.text(id,8192)); copy.put(key,values);
            }); resources=Map.copyOf(copy);
            if (operations.isEmpty() || !operations.contains(Operation.READ) || resources.isEmpty() || resources.size()>20) throw new IllegalArgumentException("Explicit readable source scope required");
        }
    }
    public FederationConfiguration {
        Identity.text(ownerInstanceId,512); sources=List.copyOf(sources); grants=List.copyOf(grants);
        projections=projections==null ? List.of() : List.copyOf(projections);
        if (schemaVersion!=1 || sources.isEmpty() || sources.size()>20 || grants.isEmpty() || grants.size()>256) throw new IllegalArgumentException("Invalid federation configuration");
        var keys=new HashSet<String>(); var repositories=new HashSet<Repository>();
        sources.forEach(source -> { if (!keys.add(source.key()) || !repositories.add(source.repository())) throw new IllegalArgumentException("Duplicate source"); });
        var principals=new HashSet<String>();
        grants.forEach(grant -> { if (!principals.add(grant.principal()) || !keys.containsAll(grant.resources().keySet())) throw new IllegalArgumentException("Duplicate or unknown grant scope"); });
        var profiles=new HashSet<String>();
        for(var projection:projections) {
            if(!profiles.add(projection.key()) || !keys.contains(projection.sourceKey())) throw new IllegalArgumentException("Unknown or duplicate projection");
            var source=sources.stream().filter(s -> s.key().equals(projection.sourceKey())).findFirst().orElseThrow();
            var type=source.types().get(projection.externalType());
            if(!source.repository().system().equals("datahub") || type==null || !type.fields().keySet().containsAll(projection.descriptionFields()))
                throw new IllegalArgumentException("Projection must use configured DataHub dataset fields");
        }
    }
}
