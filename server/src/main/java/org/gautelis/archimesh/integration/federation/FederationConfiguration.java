package org.gautelis.archimesh.integration.federation;

import java.net.URI;
import java.util.*;
import org.gautelis.federation.Identity;
import org.gautelis.federation.Identity.Repository;
import org.gautelis.federation.http.SourceReader.Field;

/** Explicit source, identity and separately approved target scopes; scheduling stays in the bridge. */
public record FederationConfiguration(int schemaVersion,String ownerInstanceId,List<Source> sources,List<Grant> grants,
                                      List<Projection> projections,List<DataHubTarget> datahubTargets,
                                      List<ApplicationProjection> applicationProjections) {
    public FederationConfiguration(int schemaVersion,String owner,List<Source> sources,List<Grant> grants) {
        this(schemaVersion,owner,sources,grants,List.of(),List.of(),List.of());
    }
    public FederationConfiguration(int schemaVersion,String owner,List<Source> sources,List<Grant> grants,List<Projection> projections) {
        this(schemaVersion,owner,sources,grants,projections,List.of(),List.of());
    }
    /** Separate application writer credential; never reuse the DataHub dataset-reader credential. */
    public record DataHubTarget(String key,Repository repository,URI baseUrl,String tokenEnvironment,
                                int timeoutMillis,int maxResponseBytes,boolean approvedPlainHttp) {
        public DataHubTarget {
            Identity.text(key,128); Objects.requireNonNull(repository);
            if(!repository.system().equals("datahub") || baseUrl==null || tokenEnvironment==null
                    || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}") || timeoutMillis<1 || timeoutMillis>120000
                    || maxResponseBytes<1024 || maxResponseBytes>16_777_216) throw new IllegalArgumentException("Invalid DataHub application target");
        }
    }
    public record ApplicationProjection(String key,String sourceKey,String externalType,String targetKey,
                                         Set<String> principals,Set<String> nativeIds,Map<String,String> adoptions,
                                         String lifecycleField,boolean approved,boolean acceptUnconditionalWriteRisk,
                                         String approvalVersion) {
        public ApplicationProjection {
            for(String value:List.of(key,sourceKey,externalType,targetKey,approvalVersion)) Identity.text(value,128);
            principals=Set.copyOf(principals); nativeIds=Set.copyOf(nativeIds);
            adoptions=adoptions==null ? Map.of() : Map.copyOf(adoptions);
            if(principals.isEmpty() || principals.size()>32 || nativeIds.isEmpty() || nativeIds.size()>256
                    || !nativeIds.containsAll(adoptions.keySet()) || new HashSet<>(adoptions.values()).size()!=adoptions.size())
                throw new IllegalArgumentException("Explicit bounded application scope required");
            principals.forEach(p -> Identity.text(p,256)); nativeIds.forEach(id -> Identity.text(id,8192));
            adoptions.values().forEach(urn -> { Identity.text(urn,8192); if(!urn.startsWith("urn:li:application:")
                || urn.length()=="urn:li:application:".length()) throw new IllegalArgumentException("Application URN required"); });
            if(lifecycleField!=null) Identity.text(lifecycleField,512);
        }
    }
    /** Reviewed standing approval for a bounded source type/audience and its owned target fields. */
    public record Projection(String key,String sourceKey,String externalType,String targetKey,String targetType,
                             Set<String> principals,Set<String> nativePrefixes,List<String> descriptionFields,
                             boolean approved,boolean acceptUnconditionalWriteRisk,String approvalVersion) {
        public Projection {
            for(String value:List.of(key,sourceKey,externalType,targetKey,targetType,approvalVersion)) Identity.text(value,128);
            principals=Set.copyOf(principals); nativePrefixes=Set.copyOf(nativePrefixes);
            descriptionFields=descriptionFields==null ? List.of("documentation") : List.copyOf(descriptionFields);
            if(!Set.of("dataset","dataProduct").contains(externalType) || principals.isEmpty() || principals.size()>32
                    || nativePrefixes.isEmpty() || nativePrefixes.size()>32 || descriptionFields.isEmpty() || descriptionFields.size()>4)
                throw new IllegalArgumentException("Explicit bounded dataset/product projection scope required");
            principals.forEach(p -> Identity.text(p,256));
            String urnPrefix=externalType.equals("dataset") ? "urn:li:dataset:(" : "urn:li:dataProduct:";
            nativePrefixes.forEach(p -> { Identity.text(p,2048); if(!p.startsWith(urnPrefix)) throw new IllegalArgumentException("Matching dataset/product URN prefix required"); });
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
        datahubTargets=datahubTargets==null ? List.of() : List.copyOf(datahubTargets);
        applicationProjections=applicationProjections==null ? List.of() : List.copyOf(applicationProjections);
        if(datahubTargets.size()>20 || applicationProjections.size()>64 || projections.size()>64) throw new IllegalArgumentException("Too many projection scopes");
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
                throw new IllegalArgumentException("Projection must use configured DataHub dataset/product fields");
            if(!type.semanticKind().equals(projection.externalType().equals("dataset") ? "catalog.dataset" : "catalog.data-product"))
                throw new IllegalArgumentException("Dataset and product semantic identities must remain distinct");
        }
        var targetKeys=new HashSet<String>(); var targetRepositories=new HashSet<Repository>();
        for(var target:datahubTargets) if(!targetKeys.add(target.key()) || !targetRepositories.add(target.repository()))
            throw new IllegalArgumentException("Duplicate DataHub target");
        for(var projection:applicationProjections) {
            if(!profiles.add(projection.key()) || !keys.contains(projection.sourceKey()) || !targetKeys.contains(projection.targetKey()))
                throw new IllegalArgumentException("Unknown or duplicate application projection");
            var source=sources.stream().filter(s -> s.key().equals(projection.sourceKey())).findFirst().orElseThrow();
            var type=source.types().get(projection.externalType());
            if(!source.repository().system().equals("turboea") || type==null || !type.semanticKind().equals("enterprise.application")
                    || !type.archimateType().equals("ApplicationComponent") || !type.fields().containsKey("documentation")
                    || projection.lifecycleField()!=null && !type.fields().containsKey(projection.lifecycleField()))
                throw new IllegalArgumentException("Application projection requires explicit Turbo EA application fields");
            var target=datahubTargets.stream().filter(t -> t.key().equals(projection.targetKey())).findFirst().orElseThrow();
            if(sources.stream().anyMatch(s -> s.repository().equals(target.repository()) && target.tokenEnvironment().equals(s.tokenEnvironment())))
                throw new IllegalArgumentException("Application writer requires a separate credential environment");
        }
    }
}
