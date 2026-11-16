"""Publication-profile workshop inside the existing isolated Northstar demo."""
import json
import time
import uuid
from urllib.parse import quote

import live
import local
from generate import archi_id
from datahub_local.probe import require, wait_for

RELATION = "NorthstarLogicalAccess"
PROFILE = "northstar-logical-access"
ADMIN = "northstar-demo-admin"


def enabled(state): return (local.directory(state)/"publication-enabled.json").exists()


def definition():
    def selector(type_, mapping):
        return {"archimateType":type_,"mappingRef":mapping,"fields":[],"sourceIds":[],"propertiesEquals":{},
                "requiresExplicitPublication":True,"allowCreate":False,"referenceOnly":True}
    return {"schemaVersion":1,"profileId":PROFILE,"version":"1.0.0","name":"Northstar logical application/data access",
        "description":"Synthetic workshop: reuse enterprise identities and publish evidenced READ/WRITE access, separate from seeded CRUD and physical lineage.",
        "enabled":True,"sourceModes":["TAG"],"identityStrategy":"REGISTRY_OR_STABLE_OCCURRENCE",
        "ownershipRule":"SOURCE_AUTHORITY_AND_APPROVED_STEWARD","targetSystem":"TURBO_EA","conflictStrategy":"REPORT_AND_STOP",
        "elements":[selector("ApplicationComponent","Application"),selector("DataObject","DataObject")],"relationships":[],
        "derivationsEnabled":True,"derivations":[{"ruleId":"application-data-access","kind":"APPLICATION_DATA_ACCESS_AGGREGATE",
            "targetMappingRef":RELATION,"accessModeProperty":"demo.accessType","accessAttribute":"attributes.operations",
            "accessValues":{"READ":"READ","WRITE":"WRITE"},"allowedAccessModes":["READ","WRITE"],"retainProvenance":True}],
        "limits":{"maxSelectedElements":20,"maxSelectedRelationships":20,"maxDerivedRelations":20,"maxGraphElements":10000,"maxGraphRelationships":20000}}


def configure(state):
    directory=local.directory(state); config=directory/"live"; config.mkdir(exist_ok=True)
    local.save(config/"publication-profiles.json",{"schemaVersion":1,"profiles":[definition()]})
    (config/"publication-profiles.json").chmod(0o644)
    local.save(directory/"publication-enabled.json",{"schemaVersion":1,"profileId":PROFILE,"version":"1.0.0","run":state["run"]})


def target_configuration(target):
    target["mapping"]["relationTypes"][RELATION]={"archimateType":"AccessRelationship","sourceType":"Application",
        "targetType":"DataObject","cardinality":"n:m","reversed":False}
    target["publicationAccess"]={"enabled":True,"publishers":[ADMIN],"cardTypes":["Application","DataObject"],
        "relationTypes":[RELATION],"fields":["attributes.operations"],"acceptUnconditionalWriteRisk":True,"completeAttributeReadsApproved":True}


def metamodel(api):
    existing=next((t for t in api("GET","/api/v1/metamodel/relation-types") if t["key"]==RELATION),None)
    body={"key":RELATION,"label":"Logical architecture access","reverse_label":"Used by application",
        "description":"ArchiMesh approved logical READ/WRITE claims; not CRUD permissions or DataHub technical lineage.",
        "source_type_key":"Application","target_type_key":"DataObject","cardinality":"n:m",
        "attributes_schema":[{"key":"operations","label":"Logical access","type":"multiselect",
            "options":[{"key":"READ","label":"Read"},{"key":"WRITE","label":"Write"}]}]}
    if existing is None:
        api("POST","/api/v1/metamodel/relation-types",body)
        return
    require(all(existing.get(k)==body[k] for k in ("source_type_key","target_type_key","cardinality")),"compatible demo logical access metamodel endpoints")
    attribute=next((f for f in existing.get("attributes_schema",[]) if f.get("key")=="operations"),{})
    require(attribute.get("type")=="multiselect" and {o["key"] for o in attribute.get("options",[])}=={"READ","WRITE"},"compatible demo logical access values; no automatic metamodel replacement")


def request(state,method,path,body=None):
    def invoke():
        try: return local.archimesh(state,method,path,body)
        except local.ApiError as error:
            if error.code not in ("INTEGRATION_BUSY","PUBLICATION_BUSY","INBOUND_BUSY"): raise
            return None
    return wait_for(invoke,lambda result:result is not None,"demo publication coordination",90)


def root(model=live.MODEL): return "/admin/models/"+quote(model,safe="")+"/publication/"+live.TARGET


def fixture(state): return json.loads((local.directory(state)/"artifacts/metadata.json").read_text())


def link(state,card,element):
    prefix="/models/"+live.MODEL+"/external-objects"
    preview=request(state,"GET",prefix+"/preview?target="+live.TARGET+"&cardId="+quote(card)+"&ref=HEAD")
    require(preview.get("elementId") in (None,element),"seeded card is not linked to a different model occurrence")
    if preview.get("elementId")==element:
        return {"elementId":element,"cardId":card,"canonicalId":request(state,"GET",prefix+"/"+element+"/status")["canonicalId"]}
    linked=request(state,"POST",prefix+"/import",{"requestId":str(uuid.uuid4()),"targetKey":live.TARGET,"cardId":card,"ref":"HEAD",
        "mode":"LINK","elementId":element,"previewDigest":preview["digest"]})
    # Explicitly review the master fields once for this synthetic, previously local occurrence.
    inbound="/admin/models/"+live.MODEL+"/inbound/"+live.TARGET
    inspected=request(state,"GET",inbound+"/resource/"+element)
    request(state,"POST",inbound+"/accept-remote",{"elementId":element,"inspectionDigest":inspected["digest"]})
    return linked


def catalog_assets(state):
    """Pre-import a real dataset/product so editing their master is visible immediately in Archi."""
    f=fixture(state); selected=[("dataset",f["datasets"][2],live.PROFILE),("dataProduct",f["products"][0],live.PRODUCT_PROFILE)]
    result=[]
    for kind,asset,profile in selected:
        source={"profile":profile,"nativeId":asset["urn"],"correlation":str(uuid.uuid4())}
        plan=wait_for(lambda:live.request(state,"POST","/federation/v1/projections/prepare",source),lambda p:p["state"]=="VERIFIED","initial "+kind+" bridge projection",300)
        prefix="/models/"+live.MODEL+"/external-objects"
        preview=request(state,"GET",prefix+"/preview?target="+live.TARGET+"&cardId="+plan["targetId"]+"&ref=HEAD")
        imported=request(state,"POST",prefix+"/import",{"requestId":str(uuid.uuid4()),"targetKey":live.TARGET,"cardId":plan["targetId"],
            "ref":"HEAD","mode":"CREATE","previewDigest":preview["digest"]})
        require(imported["canonicalId"]==plan["canonicalId"],"catalog import reuses bridge canonical identity")
        result.append({"kind":kind,"profile":profile,"urn":asset["urn"],"initialName":asset["name"],"cardId":plan["targetId"],
            "elementId":imported["elementId"],"canonicalId":plan["canonicalId"]})
    return result


def prepare(state):
    require(enabled(state) and live.enabled(state),"publication and live bridge configured")
    saved=local.directory(state)/"publication-demo.json"
    if saved.exists(): return verify(state)
    api=local.turbo_api(state); metamodel(api)
    f=fixture(state); journal=json.loads((local.directory(state)/"turbo-journal.json").read_text())["bindings"]
    app=next(a for a in f["applications"] if a["id"]=="oms")
    source={"profile":live.APPLICATION_PROFILE,"nativeId":journal["applications:oms"],"correlation":str(uuid.uuid4())}
    wait_for(lambda:live.request(state,"POST","/federation/v1/application-projections/prepare",source),lambda p:p["state"]=="VERIFIED","shared enterprise Application identity",300)
    bindings=[link(state,journal["applications:oms"],"elem:"+app["archiElementId"])]
    for data in f["logicalData"]:
        bindings.append(link(state,journal["logicalData:"+data["id"]],"elem:"+archi_id("data:"+data["id"])))
    assets=catalog_assets(state)
    local.save(saved,{"schemaVersion":1,"modelId":live.MODEL,"profileId":PROFILE,"bindings":bindings,"catalogAssets":assets})
    return publish(state)


def publish(state):
    require(enabled(state),"enable publication first: ./scripts/demo.sh enable-publication")
    summary=json.loads((local.directory(state)/"publication-demo.json").read_text())
    exported=request(state,"GET","/admin/models/"+live.MODEL+"/export"); revision=exported["model"]["headRevision"]
    tag="demo-publication-r"+str(revision)
    tags=request(state,"GET","/admin/models/"+live.MODEL+"/tags")
    existing=next((t for t in tags if t["tagName"]==tag),None)
    if existing: require(existing["revision"]==revision,"immutable demo publication tag")
    else: revision=request(state,"POST","/admin/models/"+live.MODEL+"/tags?tagName="+tag+"&description=Reviewed+Northstar+demo+publication")["revision"]
    profiles=request(state,"GET",root()+"/profiles/v1")["profiles"]
    pin=next(p["pin"] for p in profiles if p["pin"]["profileId"]==PROFILE and p["pin"]["version"]=="1.0.0")
    spec={"mode":"TAG","ref":tag,"elements":sorted(b["elementId"] for b in summary["bindings"]),"relationships":[],"stewards":[],"profile":pin}
    explained=request(state,"POST",root()+"/explain/v1",spec)
    require(not any(d["severity"]=="CONFLICT" for d in explained["preview"]["plan"].get("diagnostics",[])),"conflict-free publication preview")
    local.save(local.directory(state)/"artifacts/publication-preview.json",explained)
    current=request(state,"GET",root())["policy"]
    if current["policy"]!=spec:
        request(state,"POST",root()+"/policy",{"policy":spec,"expectedVersion":current["version"],"previewDigest":explained["preview"]["digest"]})
    run={"requestId":str(uuid.uuid4()),"previewDigest":explained["preview"]["digest"]}
    local.save(local.directory(state)/"publication-request.json",run)
    request(state,"POST",root()+"/run",run)
    def status():
        current=request(state,"GET",root())
        if (current.get("job") or {}).get("state") in ("CONFLICT","AMBIGUOUS","ERROR","PAUSED"):
            raise RuntimeError("Demo publication stopped: "+str(current["job"].get("code",current["job"]["state"]))+"; inspect Publication, do not reset identity/baseline fences")
        return current
    wait_for(status,lambda s:(s.get("job") or {}).get("state")=="SYNCED","approved tag published by the normal worker",180)
    summary.update(tag=tag,revision=revision,profile=pin)
    local.save(local.directory(state)/"publication-demo.json",summary)
    return verify(state)


def verify(state):
    summary=json.loads((local.directory(state)/"publication-demo.json").read_text())
    require("tag" in summary,"complete setup with ./scripts/demo.sh publish")
    status=request(state,"GET",root())
    require(status["policy"]["policy"]["profile"]==summary["profile"] and status["policy"]["policy"]["ref"]==summary["tag"],"same reviewed profile and immutable tag")
    explained=request(state,"POST",root()+"/explain/v1",status["policy"]["policy"])
    resources={r["canonicalId"]:r for r in status["resources"]}
    api=local.turbo_api(state); assertions=[]
    for resource in explained["preview"]["plan"]["resources"]:
        provenance=resource.get("provenance",{}); key=provenance.get("assertion")
        if not key: continue
        canonical=resource["identity"]["canonical"]["canonicalId"]; row=resources[canonical]
        require(row["state"]=="SYNCED" and row["resourceId"],"verified derived assertion")
        # Read actual remote state, not just the job's SYNCED marker.
        source=next(b["cardId"] for b in summary["bindings"] if b["elementId"]==resource["sourceEntityId"])
        destination=next(b["cardId"] for b in summary["bindings"] if b["elementId"]==resource["targetEntityId"])
        matches=[r for r in api("GET","/api/v1/relations?card_id="+source) if r["type"]==RELATION and r["source_id"]==source and r["target_id"]==destination]
        require(len(matches)==1 and matches[0]["id"]==row["resourceId"],"one exact relation per canonical assertion")
        require(matches[0].get("attributes",{}).get("operations")==resource["fields"]["attributes.operations"],"logical READ/WRITE remote readback")
        assertions.append({"canonicalId":canonical,"relationId":row["resourceId"],"operations":matches[0]["attributes"]["operations"],"evidencePaths":len(provenance["evidence"]["paths"]),"source":source,"target":destination})
    require(assertions,"nonempty Northstar architectural access projection")
    result={"schemaVersion":1,"modelId":live.MODEL,"tag":summary["tag"],"revision":summary["revision"],"profile":summary["profile"],
        "assertions":assertions,"catalogAssets":summary["catalogAssets"],"status":status["job"]["state"]}
    local.save(local.directory(state)/"artifacts/publication-verification.json",result)
    print("Publication Profiles: "+str(len(assertions))+" verified logical READ/WRITE relations; tag "+summary["tag"],flush=True)
    return result


def probe(state):
    before=publish(state); publish(state); after=verify(state)
    require(before["assertions"]==after["assertions"],"repeat publication reuses canonical assertions and remote relation IDs")
    if before["revision"]==after["revision"]: require(before["tag"]==after["tag"],"unchanged source revision reuses its immutable tag")
    local.save(local.directory(state)/"artifacts/publication-probe.json",{"repeatPublicationStable":True,"before":before,"after":after})
    return after


def curated_baseline(status,body,rendered):
    from datahub_local.probe import unwrap
    original=dict(unwrap(body)) if status==200 else {}
    # An explicit empty string overrides ingestion. Preserve the rendered text
    # when there was no curated description, without deleting the whole aspect.
    if "description" not in original: original["description"]=rendered or ""
    return original


def master_flow(state):
    """Edit only DataHub; observe the real bridge and inbound workers, then restore."""
    from datahub_local.probe import Api, unwrap
    summary=json.loads((local.directory(state)/"publication-demo.json").read_text())
    asset=next(a for a in summary["catalogAssets"] if a["kind"]=="dataset")
    source=Api(local.datahub(state),"datahub"); ea=local.turbo_api(state)
    request_body={"profile":asset["profile"],"nativeId":asset["urn"],"correlation":str(uuid.uuid4())}
    wait_for(lambda:live.request(state,"POST","/federation/v1/projections/prepare",request_body),lambda p:p["state"]=="VERIFIED","fresh dataset source before demo edit",240)
    def observed():
        card=ea("GET","/api/v1/cards/"+asset["cardId"])
        exported=request(state,"GET","/admin/models/"+live.MODEL+"/export")
        element=next(e for e in exported["snapshot"]["elements"] if e["id"]==asset["elementId"])
        return card,element
    before=wait_for(observed,lambda pair:(pair[0].get("description") or "")==(pair[1].get("documentation") or ""),"linked model source baseline",240)
    tag_path="/models/"+live.MODEL+"/snapshot?ref="+quote(summary["tag"])
    historical=request(state,"GET",tag_path)
    status,body=source.aspect(asset["urn"],"editableDatasetProperties")
    require(status in (200,404),"read curated dataset description")
    original=curated_baseline(status,body,before[0].get("description"))
    changed=dict(original); text="Northstar live demo "+str(int(time.time()))+": dataset description edited in its DataHub master."
    changed["description"]=text
    print("Editing DataHub dataset description; waiting for Turbo EA and ArchiMesh HEAD...",flush=True)
    started=time.monotonic()
    try:
        require(source.upsert(asset["urn"],"editableDatasetProperties",changed)[0]<300,"DataHub master edit")
        wait_for(observed,lambda pair:text in (pair[0].get("description") or "") and text in (pair[1].get("documentation") or ""),"DataHub -> Turbo EA -> ArchiMesh model update",240)
        require(request(state,"GET",tag_path)==historical,"historical publication tag stays unchanged during live catalog edits")
        report={"schemaVersion":1,"datahubUrn":asset["urn"],"turboCardId":asset["cardId"],"modelElementId":asset["elementId"],
            "canonicalId":asset["canonicalId"],"sourceEditObservedInTurboEA":True,"sourceEditObservedInModel":True,
            "historicalTagUnchanged":True,"propagationSeconds":round(time.monotonic()-started,2)}
    except Exception as error:
        print("Master-edit observation failed: "+str(error),flush=True)
        raise
    finally:
        require(source.upsert(asset["urn"],"editableDatasetProperties",original)[0]<300,"restore curated master description")
        wait_for(observed,lambda pair:(pair[0].get("description") or "")==(before[0].get("description") or "") and (pair[1].get("documentation") or "")==(before[1].get("documentation") or ""),"source description restored across all three systems",240)
    report["sourceDescriptionRestored"]=True
    local.save(local.directory(state)/"artifacts/master-edit-demo.json",report)
    print("DataHub master edit reached Turbo EA and ArchiMesh; source text restored, IDs and historical tag preserved.",flush=True)
    return report
