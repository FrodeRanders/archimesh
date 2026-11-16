package org.gautelis.federation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Typed cross-connector claims. Shared endpoints do not make distinct predicates or authorities equivalent. */
public final class ArchitectureAssertion {
    private ArchitectureAssertion() { }
    public enum Predicate { LOGICAL_DATA_ACCESS, ARCHITECTURAL_CONNECTION, CATALOG_CONSUMES, CATALOG_PRODUCES, TECHNICAL_LINEAGE }
    public enum Access { READ, WRITE }
    public enum Authority { ARCHIMESH_ARCHITECTURE, DATAHUB_CATALOG }
    public record Key(String subjectCanonicalId,Predicate predicate,String objectCanonicalId,Identity.Repository target,
                       String profileId,String ruleId,String mappingRef,String mappingSemantics) {
        public Key {
            Identity.text(subjectCanonicalId,2048); Identity.text(objectCanonicalId,2048); Objects.requireNonNull(predicate); Objects.requireNonNull(target);
            for(String value:List.of(profileId,ruleId,mappingRef,mappingSemantics)) Identity.text(value,512);
        }
        public String canonicalId() {
            return "urn:archimesh:assertion:"+hash(String.join("\0",subjectCanonicalId,predicate.name(),objectCanonicalId,target.system(),target.instance(),profileId,ruleId,mappingRef,mappingSemantics));
        }
    }
    public record Path(String assignmentId,String functionId,String accessId,Set<Access> access) {
        public Path {
            Identity.text(assignmentId,512); Identity.text(functionId,512); Identity.text(accessId,512);
            access=Collections.unmodifiableSet(new TreeSet<>(access));
            if(access.isEmpty()) throw new IllegalArgumentException("Evidenced READ/WRITE required");
        }
        public String sortKey() { return assignmentId+"\0"+functionId+"\0"+accessId; }
    }
    public record Evidence(String sourceSystem,String sourceInstanceId,String sourceModelId,String sourceRef,long sourceRevision,
                            String profileId,String profileVersion,String profileDigest,String ruleId,Authority authority,
                            List<String> sourceEntityIds,List<Path> paths) {
        public Evidence {
            for(String value:List.of(sourceSystem,sourceInstanceId,sourceModelId,sourceRef,profileId,profileVersion,ruleId)) Identity.text(value,512);
            if(sourceRevision<1 || profileDigest==null || !profileDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Versioned source/profile evidence required");
            Objects.requireNonNull(authority); sourceEntityIds=sourceEntityIds.stream().distinct().sorted().toList();
            sourceEntityIds.forEach(id -> Identity.text(id,512));
            paths=paths.stream().distinct().sorted(Comparator.comparing(Path::sortKey)).toList();
            if(sourceEntityIds.size()>258 || paths.size()>128) throw new IllegalArgumentException("Assertion evidence exceeds bounds");
        }
        public Set<Access> access() { var result=new TreeSet<Access>(); paths.forEach(p -> result.addAll(p.access())); return Collections.unmodifiableSet(result); }
        public String digest() {
            var parts=new ArrayList<>(List.of(sourceSystem,sourceInstanceId,sourceModelId,sourceRef,Long.toString(sourceRevision),profileId,profileVersion,profileDigest,ruleId,authority.name()));
            parts.add(Integer.toString(sourceEntityIds.size())); parts.addAll(sourceEntityIds); parts.add(Integer.toString(paths.size()));
            paths.forEach(p -> { parts.add(p.sortKey()); parts.add(Integer.toString(p.access().size())); p.access().forEach(a -> parts.add(a.name())); });
            return hash(String.join("\0",parts));
        }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
