package org.gautelis.archimesh.integration.federation;

import java.net.URI;
import java.util.*;
import org.gautelis.federation.Identity;
import org.gautelis.federation.Identity.Repository;
import org.gautelis.federation.http.SourceReader.Field;

/** Separate, explicit source-read/identity scopes. No bridge scheduler or projection jobs. */
public record FederationConfiguration(int schemaVersion,String ownerInstanceId,List<Source> sources,List<Grant> grants) {
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
        if (schemaVersion!=1 || sources.isEmpty() || sources.size()>20 || grants.isEmpty() || grants.size()>256) throw new IllegalArgumentException("Invalid federation configuration");
        var keys=new HashSet<String>(); var repositories=new HashSet<Repository>();
        sources.forEach(source -> { if (!keys.add(source.key()) || !repositories.add(source.repository())) throw new IllegalArgumentException("Duplicate source"); });
        var principals=new HashSet<String>();
        grants.forEach(grant -> { if (!principals.add(grant.principal()) || !keys.containsAll(grant.resources().keySet())) throw new IllegalArgumentException("Duplicate or unknown grant scope"); });
    }
}
