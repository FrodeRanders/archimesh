package org.gautelis.federation;

import java.util.*;
import static org.gautelis.federation.Identity.*;
import static org.gautelis.federation.ObservationPolicy.*;

/** Executable contract scenarios, also invoked by the server's JUnit gate. No infrastructure. */
public final class ContractChecks {
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Federation contract violated"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IdentityResolver.Conflict expected) { return; }
        throw new AssertionError("Invalid federation input accepted");
    }
    public static void main(String[] args) { verify(); System.out.println("Federation core contract scenarios passed"); }
    public static void verify() {
        var datahub = new Repository("datahub", "agency-catalog");
        var turbo = new Repository("turboea", "agency-ea");
        String urn = "urn:li:dataset:(urn:li:dataPlatform:postgres,agency.customer,PROD)";
        var dataset = new Resource(datahub, ResourceKind.OBJECT, urn);
        var card = new Resource(turbo, ResourceKind.OBJECT, "card-42");
        var product = new Resource(datahub, ResourceKind.OBJECT, "urn:li:dataProduct:customer");
        var canonical = new Canonical("urn:uuid:asset-1", "catalog.dataset");
        var productCanonical = new Canonical("urn:uuid:product-1", "catalog.data-product");
        var bindings = Map.of(dataset, canonical, card, canonical, product, productCanonical);
        var registry = new IdentityResolver(resource -> Optional.ofNullable(bindings.get(resource)));
        check(registry.resolve(dataset).equals(registry.resolve(card, dataset)));
        check(!registry.resolve(product).equals(registry.resolve(dataset)));
        check(!new Representation(dataset,"dataset").externalType().equals(new Representation(card,"DataObject").externalType()));
        rejects(() -> registry.resolve(card, product));
        rejects(() -> registry.resolve(new Resource(turbo, ResourceKind.OBJECT, "copied-origin-claim"), dataset));
        rejects(() -> registry.resolve(new Resource(new Repository("datahub", "test-catalog"), ResourceKind.OBJECT, urn)));
        rejects(() -> registry.resolve(new Resource(turbo, ResourceKind.RELATIONSHIP, "card-42")));
        String longUrn = "urn:li:dataset:" + "å".repeat(2000);
        check(new Resource(datahub,ResourceKind.OBJECT,longUrn).nativeId().equals(longUrn));
        rejects(() -> new Resource(datahub,ResourceKind.OBJECT,"a".repeat(8193)));
        rejects(() -> new Resource(datahub,ResourceKind.OBJECT,"å".repeat(4097)));
        rejects(() -> new Resource(datahub,ResourceKind.OBJECT,"urn:a\n"));
        rejects(() -> new Resource(datahub,ResourceKind.OBJECT,"urn:a\uD800"));
        rejects(() -> new Resource(datahub,ResourceKind.OBJECT," urn:a"));

        var source = new SourceField(dataset,"editableDatasetProperties","description");
        var owner = new SourceAuthority(SourceAuthority.Mode.REPOSITORY_MASTER,source,"policy-v1");
        var shared = new SourceAuthority(SourceAuthority.Mode.SHARED_PROPOSAL,source,"policy-v1");
        check(owner.protectsLocal() && shared.protectsLocal());
        rejects(() -> new SourceAuthority(SourceAuthority.Mode.REPOSITORY_MASTER,null,"policy-v1"));
        rejects(() -> new SourceAuthority(SourceAuthority.Mode.LOCAL_ONLY,source,"policy-v1"));
        var rules = new FieldRules<>(List.of(
                new FieldRules.Rule<>(FieldRules.Scope.TYPE,"catalog.dataset","description",owner),
                new FieldRules.Rule<>(FieldRules.Scope.CANONICAL,canonical.id(),"description",shared)));
        check(rules.resolve(canonical.id(),canonical.semanticKind(),"description").orElseThrow().equals(shared));
        check(rules.resolve("another",canonical.semanticKind(),"description").orElseThrow().equals(owner));
        check(rules.resolve(canonical.id(),canonical.semanticKind(),"geometry").isEmpty());
        rejects(() -> new FieldRules<>(List.of(
                new FieldRules.Rule<>(FieldRules.Scope.TYPE,"a","bc",owner),
                new FieldRules.Rule<>(FieldRules.Scope.TYPE,"a","bc",shared))));
        var separated = new FieldRules<>(List.of(
                new FieldRules.Rule<>(FieldRules.Scope.TYPE,"a","bc",owner),
                new FieldRules.Rule<>(FieldRules.Scope.TYPE,"ab","c",shared)));
        check(separated.resolve(null,"a","bc").orElseThrow().equals(owner));

        String digest = "a".repeat(64), newer = "b".repeat(64);
        var direct = new Observation(source,dataset,"opaque-rev-z",digest,null);
        check(evaluate(canonical,owner,direct,0,null,registry,(c,o) -> false) == Decision.ACCEPT_DIRECT);
        var baseline = new Baseline(3,source,direct.sourceRevision(),digest);
        check(evaluate(canonical,owner,direct,3,baseline,registry,(c,o) -> false) == Decision.UNCHANGED);
        check(evaluate(canonical,owner,direct,2,baseline,registry,(c,o) -> true) == Decision.STALE_BASELINE);
        var mirror = new Observation(source,card,direct.sourceRevision(),digest,"receipt-1");
        check(evaluate(canonical,owner,mirror,3,baseline,registry,(c,o) -> false) == Decision.UNVERIFIED_MIRROR);
        check(evaluate(canonical,owner,mirror,3,baseline,registry,(c,o) -> c.equals(canonical) && o.equals(mirror)) == Decision.UNCHANGED);
        var stale = new Observation(source,card,"older-opaque",newer,"receipt-0");
        check(evaluate(canonical,owner,stale,3,baseline,registry,(c,o) -> true) == Decision.REFRESH_SOURCE);
        check(evaluate(canonical,owner,mirror,0,null,registry,(c,o) -> true) == Decision.REFRESH_SOURCE);
        var future = new Observation(source,card,"zzz-not-orderable",newer,"receipt-new");
        check(evaluate(canonical,owner,future,3,baseline,registry,(c,o) -> true) == Decision.REFRESH_SOURCE);
        check(evaluate(canonical,shared,direct,3,baseline,registry,(c,o) -> true) == Decision.POLICY_DENIED);
        var wrongAspect = new SourceField(dataset,"datasetProperties","description");
        check(evaluate(canonical,owner,new Observation(wrongAspect,dataset,"rev",digest,null),3,baseline,registry,(c,o) -> true) == Decision.WRONG_SOURCE);
        check(evaluate(productCanonical,owner,direct,3,baseline,registry,(c,o) -> true) == Decision.UNVERIFIED_IDENTITY);
        rejects(() -> new Observation(source,card,"rev","bad digest",null));
        // The reverse projection uses the same protocol, with Turbo EA as the field owner.
        var application=new Canonical("urn:agency:application:1","enterprise.application");
        var appCard=new Resource(turbo,ResourceKind.OBJECT,"application-17");
        var appReference=new Resource(datahub,ResourceKind.OBJECT,"urn:li:application:explicitly-reviewed-reference");
        var appRegistry=new IdentityResolver(resource -> resource.equals(appCard) || resource.equals(appReference) ? Optional.of(application) : Optional.empty());
        var appName=new SourceField(appCard,"card","name");
        var appAuthority=new SourceAuthority(SourceAuthority.Mode.REPOSITORY_MASTER,appName,"app-policy-v1");
        var appRead=new Observation(appName,appCard,"source-app-rev",digest,null);
        check(evaluate(application,appAuthority,appRead,0,null,appRegistry,(c,o) -> false)==Decision.ACCEPT_DIRECT);
        var appBaseline=new Baseline(1,appName,"source-app-rev",digest);
        var appMirror=new Observation(appName,appReference,"source-app-rev",digest,"app-receipt");
        check(evaluate(application,appAuthority,appMirror,1,appBaseline,appRegistry,(c,o) -> true)==Decision.UNCHANGED);
        check(evaluate(application,appAuthority,appMirror,1,appBaseline,appRegistry,(c,o) -> false)==Decision.UNVERIFIED_MIRROR);
        var forgedOwner=new Observation(new SourceField(appReference,"applicationProperties","name"),appReference,"datahub-edit",newer,null);
        check(evaluate(application,appAuthority,forgedOwner,1,appBaseline,appRegistry,(c,o) -> true)==Decision.WRONG_SOURCE);
    }
}
