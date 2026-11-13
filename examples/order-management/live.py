"""Optional Compose wiring for the standalone dataset bridge and ArchiMesh catalog."""
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from urllib.parse import quote

import local
from datahub_local.probe import Api, require, wait_for

TARGET="turboea"
TYPE="DataHubDataset"
PROFILE="datahub-datasets"
PRINCIPAL="northstar-dataset-bridge"
MODEL="northstar-order-management-demo-v1"


def enabled(state): return (local.directory(state)/"live-integration.json").exists()


def documents(state):
    owner="archimesh-demo-"+state["run"]; target="turboea-demo-"+state["run"]
    def fields(authority):
        return {"name":{"remoteField":"name","valueType":"STRING","authority":authority},
                "documentation":{"remoteField":"description","valueType":"STRING","authority":authority}}
    targets={"schemaVersion":1,"instanceId":owner,"operators":["northstar-demo-admin"],"targets":[{
        "key":TARGET,"instanceId":target,"baseUrl":"http://turbo:8000","approvedPlainHttp":True,
        "tokenEnvironment":"DEMO_LIVE_TURBO_TOKEN","expectedApiVersion":"2.194.0","timeoutMillis":10000,"maxResponseBytes":2097152,
        "catalogAccess":{"approved":True,"readers":["northstar-demo-admin"],"importers":["northstar-demo-admin"],
                         "cardTypes":["Application","DataObject",TYPE],"fields":["name","description"]},
        "inboundAccess":{"enabled":True,"administrators":["northstar-demo-admin"],"cardTypes":["Application","DataObject",TYPE],
                         "fields":["name","description"],"sseEnabled":False},
        "mapping":{"version":"northstar-live-datasets-v1","cardTypes":{"Application":"ApplicationComponent","DataObject":"DataObject",TYPE:"DataObject"},
                   "relationTypes":{},"fields":{"Application":fields("TURBO_EA_MASTER"),"DataObject":fields("TURBO_EA_MASTER"),TYPE:fields("DATAHUB_MASTER")}}}]}
    federation={"schemaVersion":1,"ownerInstanceId":owner,"sources":[{
        "key":"datahub","repository":{"system":"datahub","instance":"datahub-demo-"+state["run"]},
        "baseUrl":"http://gms:8080","approvedPlainHttp":True,"tokenEnvironment":"DEMO_LIVE_DATAHUB_TOKEN",
        "timeoutMillis":10000,"maxResponseBytes":2097152,
        "types":{"dataset":{"semanticKind":"catalog.dataset","archimateType":"DataObject","fields":{
            "name":{"aspect":"datasetProperties","path":"/name"},
            "documentation":{"aspect":"editableDatasetProperties","path":"/description"},
            "property:ingestedDescription":{"aspect":"datasetProperties","path":"/description"}}}}}],
        "grants":[{"principal":PRINCIPAL,"requiredRole":"federation_bridge","operations":["READ"],
                   "resources":{"datahub":["urn:li:dataset:(urn:li:dataPlatform:postgres,northstar_demo.oms.sales_order,DEV)"]}}],
        "projections":[{"key":PROFILE,"sourceKey":"datahub","externalType":"dataset","targetKey":TARGET,"targetType":TYPE,
                        "principals":[PRINCIPAL],"nativePrefixes":["urn:li:dataset:("],
                        "descriptionFields":["documentation","property:ingestedDescription"],"approved":True,
                        "acceptUnconditionalWriteRisk":True,"approvalVersion":"synthetic-whole-instance-datasets-v1"}]}
    bridge={"schemaVersion":1,"owner":{"baseUrl":"http://127.0.0.1:8081","bootstrapUser":PRINCIPAL},
            "datahub":{"baseUrl":"http://gms:8080","approvedPlainHttp":True,"tokenEnvironment":"DEMO_LIVE_DATAHUB_TOKEN"},
            "profile":PROFILE,"nativePrefixes":["urn:li:dataset:("],"intervalSeconds":5}
    return targets,federation,bridge


def overlay(state):
    directory=local.directory(state); path=directory/"compose.override.json"
    document=json.loads(path.read_text()); server=document["services"]["archimesh"]
    server["environment"].update(ARCHIMESH_INTEGRATIONS_ENABLED="true",ARCHIMESH_INTEGRATIONS_CONFIGURATION="/configuration/targets.json",
        ARCHIMESH_FEDERATION_ENABLED="true",ARCHIMESH_FEDERATION_CONFIGURATION="/configuration/sources.json",
        ARCHIMESH_PUBLICATION_WORKER_ENABLED="false",ARCHIMESH_CENTRAL_EDITS_WORKER_ENABLED="false",
        ARCHIMESH_INBOUND_INTERVAL_SECONDS="5",KAFKA_BOOTSTRAP_SERVERS="archimesh-kafka:9092")
    server["env_file"]=[str(directory/"integration-secrets.env")]
    server["volumes"]=[str(directory/"live"/name)+":/configuration/"+name+":ro" for name in ("targets.json","sources.json")]
    server["networks"]={"default":{},"datahub":{}}
    document["services"]["kafka"]={"networks":{"default":{"aliases":["archimesh-kafka"]}}}
    document["networks"]={"datahub":{"external":True,"name":local.datahub(state)["project"]+"_default"}}
    document["services"]["dataset-bridge"]={"image":"northstar-demo-datahub-bridge:"+state["run"],"network_mode":"service:archimesh",
        "env_file":[str(directory/"integration-secrets.env")],"volumes":[str(directory/"live/bridge.json")+":/configuration/bridge.json:ro"],
        "depends_on":["archimesh"],"restart":"unless-stopped","read_only":True,"environment":{"PYTHONDONTWRITEBYTECODE":"1"}}
    local.save(path,document)


def approve_model(state,model):
    acl=local.archimesh(state,"GET","/admin/models/"+model+"/acl")
    for role in ("adminUsers","writerUsers","readerUsers"): acl[role]=sorted(set(acl.get(role,[]))|{"northstar-demo-admin"})
    acl["modelId"]=model
    local.archimesh(state,"PUT","/admin/models/"+model+"/acl",acl)
    status=local.archimesh(state,"GET","/admin/models/"+model+"/inbound/"+TARGET)
    def approve():
        try:
            local.archimesh(state,"POST","/admin/models/"+model+"/inbound/"+TARGET+"/policy",{
                "enabled":True,"expectedVersion":status["policyVersion"],"targetInstanceId":"turboea-demo-"+state["run"],"mappingVersion":"northstar-live-datasets-v1"})
            return True
        except local.ApiError as error:
            if error.code!="INTEGRATION_BUSY": raise
            return False
    wait_for(approve,bool,"inbound policy coordination",60)


def enable(state,*,build=True):
    require(state.get("mode")=="compose","live demo requires containerized run")
    directory=local.directory(state); config=directory/"live"; config.mkdir(exist_ok=True)
    api=local.turbo_api(state)
    types={item["key"]:item for item in api("GET","/api/v1/metamodel/types")}
    if TYPE not in types:
        api("POST","/api/v1/metamodel/types",{"key":TYPE,"label":"DataHub Dataset","description":"Technical datasets whose metadata is authoritative in this demo DataHub instance.",
                                             "color":"#0277bd","icon":"table_chart","fields_schema":[]})
    else: require(types[TYPE]["label"]=="DataHub Dataset","compatible dedicated dataset metamodel type")
    login=local.request_json(state["env"]["DEMO_TURBO_PORT"],"POST","/api/v1/auth/login",{"email":state["turboEmail"],"password":state["turboPassword"]})
    token=local.dh.session_token(local.datahub(state),"datahub",lifetime_seconds=86400)
    secret=directory/"integration-secrets.env"
    with open(secret,"w",opener=lambda p,flags:os.open(p,flags,0o600)) as stream:
        stream.write("DEMO_LIVE_TURBO_TOKEN="+login["access_token"]+"\nDEMO_LIVE_DATAHUB_TOKEN="+token+"\n")
    for name,body in zip(("targets.json","sources.json","bridge.json"),documents(state)):
        local.save(config/name,body); (config/name).chmod(0o644)  # Non-secret config read by unprivileged containers.
    if build:
        with (directory/"build.log").open("a") as log:
            result=subprocess.run(["docker","build","-f",str(local.REPO/"integrations/datahub-turboea/Dockerfile.bridge"),
                "-t","northstar-demo-datahub-bridge:"+state["run"],str(local.REPO/"integrations/datahub-turboea")],stdout=log,stderr=subprocess.STDOUT,timeout=600)
        require(result.returncode==0,"standalone bridge image build; inspect build.log")
    local.compose(state,"stop","dataset-bridge",check=False)
    overlay(state)
    local.compose(state,"up","-d","kafka","archimesh")
    try:
        wait_for(lambda:local.archimesh(state,"GET","/admin/models"),lambda value:isinstance(value,list),"configured registry owner",120)
    except RuntimeError:
        local.compose(state,"logs","--no-color","--tail","120","archimesh",check=False)
        raise
    approve_model(state,MODEL)
    local.compose(state,"up","-d","--force-recreate","dataset-bridge")
    local.save(directory/"live-integration.json",{"schemaVersion":1,"profile":PROFILE,"target":TARGET,"datasetType":TYPE,
        "enabled":True,"scope":"All datasets in this synthetic DataHub instance", "credentialsIssuedAt":int(time.time()),
        "credentialRenewal":"./scripts/demo.sh start (regenerates the demo's one-day service sessions)"})
    print("Live DataHub dataset bridge and Archi Enterprise Repository catalog enabled.",flush=True)


def request(state,method,path,body=None):
    def invoke():
        try:
            return local.request_json(state["env"]["DEMO_ARCHIMESH_PORT"],method,path,body,
                                      {"X-Archimesh-User":PRINCIPAL,"X-Archimesh-Roles":"federation_bridge"})
        except local.ApiError as error:
            if error.code!="INTEGRATION_BUSY": raise
            return None
    return wait_for(invoke,lambda value:value is not None,"registry owner coordination",60)


def probe(state):
    """Prove post-start discovery, governed import, update and stable identity in an isolated model."""
    require(enabled(state),"live integration enabled")
    suffix=uuid.uuid4().hex[:10]; urn="urn:li:dataset:(urn:li:dataPlatform:postgres,northstar_live.orders_"+suffix+",DEV)"
    name="Northstar live orders "+suffix; model="northstar-live-probe-"+suffix
    api=Api(local.datahub(state),"datahub")
    require(api.upsert(urn,"datasetProperties",{"name":name,"description":"Created after the bridge was started."})[0]<300,"new DataHub dataset")
    require(api.upsert(urn,"status",{"removed":False})[0]<300,"new dataset lifecycle")
    local.archimesh(state,"POST","/admin/models/"+model+"?modelName="+quote("Disposable live bridge probe"))
    approve_model(state,model)
    root="/models/"+model+"/external-objects"
    def catalog():
        try: return local.archimesh(state,"GET",root+"/catalog?target="+TARGET+"&type="+TYPE+"&query="+quote(name)+"&page=1&pageSize=25")
        except local.ApiError as error:
            if error.code not in ("PROJECTION_SOURCE_REFRESH_REQUIRED","PROJECTION_VERIFIED_READBACK_REQUIRED","INTEGRATION_BUSY"): raise
            return {"items":[]}
    result=wait_for(catalog,lambda page:any(item["name"]==name for item in page["items"]),"post-start dataset in ArchiMesh catalog",240)
    card=next(item for item in result["items"] if item["name"]==name)
    preview=local.archimesh(state,"GET",root+"/preview?target="+TARGET+"&cardId="+card["id"]+"&ref=HEAD")
    imported=local.archimesh(state,"POST",root+"/import",{"requestId":str(uuid.uuid4()),"targetKey":TARGET,"cardId":card["id"],"ref":"HEAD","mode":"CREATE","previewDigest":preview["digest"]})
    correlation=str(uuid.uuid4())
    prepared=request(state,"POST","/federation/v1/projections/prepare",{"profile":PROFILE,"nativeId":urn,"correlation":correlation})
    require(prepared["canonicalId"]==imported["canonicalId"] and prepared["targetId"]==card["id"],"one source/mirror/model canonical identity")
    require(any(field["authority"]=="DATAHUB_MASTER" for field in preview["fields"]),"DataHub authority preserved through TurboEA")
    updated=name+" revised"
    require(api.upsert(urn,"datasetProperties",{"name":updated,"description":"Updated source description."})[0]<300,"source name change")
    def snapshot(): return local.archimesh(state,"GET","/admin/models/"+model+"/export")
    current=wait_for(snapshot,lambda value:any(e["id"]==imported["elementId"] and e["name"]==updated for e in value["snapshot"]["elements"]),"source change in linked Archi model",240)
    require(len(current["snapshot"]["elements"])==1,"one model occurrence after update")
    # Reverse the source name to exercise A -> B -> A without reusing a historical update intent.
    require(api.upsert(urn,"datasetProperties",{"name":name,"description":"Created after the bridge was started."})[0]<300,"source name reversion")
    wait_for(snapshot,lambda value:any(e["id"]==imported["elementId"] and e["name"]==name for e in value["snapshot"]["elements"]),"source reversion in same model occurrence",240)
    preview=local.archimesh(state,"GET",root+"/preview?target="+TARGET+"&cardId="+card["id"]+"&ref=HEAD")
    reused=local.archimesh(state,"POST",root+"/import",{"requestId":str(uuid.uuid4()),"targetKey":TARGET,"cardId":card["id"],"ref":"HEAD","mode":"CREATE","previewDigest":preview["digest"]})
    require(reused["elementId"]==imported["elementId"] and reused["canonicalId"]==imported["canonicalId"],"repeat import reuses identity and occurrence")
    local.compose(state,"restart","dataset-bridge")
    time.sleep(6)
    after=request(state,"POST","/federation/v1/projections/prepare",{"profile":PROFILE,"nativeId":urn,"correlation":str(uuid.uuid4())})
    require(after["state"]=="VERIFIED" and after["canonicalId"]==imported["canonicalId"] and after["targetId"]==card["id"],"worker restart preserves the same target/canonical")
    report={"modelId":model,"datahubUrn":urn,"turboNativeId":card["id"],"canonicalId":imported["canonicalId"],"elementId":imported["elementId"],
            "postStartDiscovery":True,"sourceUpdate":True,"sourceReversion":True,"repeatImport":True,"workerRestart":True}
    local.save(local.directory(state)/"artifacts/live-integration-probe.json",report)
    print(json.dumps(report,indent=2),flush=True)
    return report
