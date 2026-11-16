"""Reviewed demo-only Turbo EA upgrade, with a disposable database migration trial."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import time
import uuid

import local
import live
from turbo_version import VERSION, PREVIOUS_VERSION, images
from datahub_local import turbo_upgrade as database
from datahub_local.probe import require, wait_for

CONFIGURATION=("state.json","compose.override.json","integration-secrets.env","live-integration.json",
               "live/targets.json","live/sources.json","live/bridge.json")


def snapshot_configuration(state,backup):
    for name in CONFIGURATION:
        path=local.directory(state)/name
        if path.exists():
            target=backup/"configuration"/name;target.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            shutil.copy2(path,target)


def restore_configuration(state,backup):
    for name in CONFIGURATION:
        source=backup/"configuration"/name;target=local.directory(state)/name
        if source.exists(): shutil.copy2(source,target)


def bindings(archive):
    labels={"IntegrationCanonicalObject","IntegrationExternalBinding","IntegrationModelBinding","IntegrationPublicationIdentity"}
    stable={"canonicalId","kind","semanticKind","registryInstanceId","system","targetInstanceId","resourceKind","resourceId",
            "instanceId","sourceModelId","entityKind","entityId","archimateType","externalType","nativeId"}
    return sorted(json.dumps({"label":node["label"],"properties":{key:value for key,value in node["properties"].items() if key in stable}},sort_keys=True)
                  for node in archive["nodes"] if node["label"] in labels)


def contract_probe(state):
    api=local.turbo_api(state)
    page=api("GET","/api/v1/cards?type=Application&page=1&page_size=2")
    require(page["page"]==1 and page["page_size"]==2 and isinstance(page["items"],list),"one-based card pagination")
    require({"Application","DataObject"}.issubset({item["key"] for item in api("GET","/api/v1/metamodel/types")}),"base metamodel retained")
    suffix=uuid.uuid4().hex[:8]
    app=api("POST","/api/v1/cards",{"type":"Application","name":"Disposable upgrade probe "+suffix,"external_id":"upgrade-probe:"+suffix,
                                    "attributes":{"upgradeProbeA":"a","upgradeProbeB":"b"},"strict_attributes":False})
    duplicate=api("POST","/api/v1/cards",{"type":"Application","name":"Second disposable upgrade probe "+suffix,"external_id":"upgrade-probe:"+suffix,"attributes":{}})
    require(app["id"]!=duplicate["id"],"external_id still does not grant idempotence")
    api("PATCH","/api/v1/cards/"+app["id"],{"description":"Verified migrated API","attributes":{"upgradeProbeA":"changed"},"strict_attributes":False})
    observed=api("GET","/api/v1/cards/"+app["id"])
    # Turbo EA recomputes hierarchyLevel; replacement is proved by dropping B.
    require(observed["name"]==app["name"] and observed["description"]=="Verified migrated API" and observed["attributes"].get("upgradeProbeA")=="changed" and "upgradeProbeB" not in observed["attributes"],"PATCH replacement and omitted-field semantics")
    obj=api("POST","/api/v1/cards",{"type":"DataObject","name":"Disposable probe data "+suffix,"attributes":{}})
    relation=api("POST","/api/v1/relations",{"type":"relAppToDataObj","source_id":app["id"],"target_id":obj["id"],"attributes":{"crudRead":True}})
    require(any(item["id"]==relation["id"] for item in api("GET","/api/v1/relations?card_id="+app["id"])),"relation create and read")
    return {"pagination":True,"customMetamodel":True,"patchSemantics":True,"externalIdNotIdempotent":True,"relations":True}


def operation(state,path,body=None):
    def invoke():
        try: return local.archimesh(state,"POST","/admin/integrations/"+path,body)
        except local.ApiError as error:
            if error.code!="INTEGRATION_BUSY": raise
            return None
    return wait_for(invoke,lambda value:value is not None,"upgrade integration coordination",60)


def resume(state):
    reviewed=operation(state,"probe")
    operation(state,"resume",{"expectedVersion":reviewed["details"]["controlVersion"],"digest":reviewed["digest"]})


def pause(state):
    status=local.archimesh(state,"GET","/admin/integrations")
    if not status["paused"]: operation(state,"pause",{"expectedVersion":status["controlVersion"]})


def run(state,build):
    require(state.get("mode")=="compose","containerized local demo upgrade only")
    require(local.deployed_version(state)==PREVIOUS_VERSION,"upgrade requires the reviewed "+PREVIOUS_VERSION+" baseline; already upgraded or unknown version")
    local.turbo_api(state)
    backup=local.directory(state)/("upgrade-"+VERSION+"-"+time.strftime("%Y%m%d-%H%M%S"));backup.mkdir(mode=0o700)
    snapshot_configuration(state,backup)
    candidate=copy.deepcopy(state);candidate["turboVersion"]=VERSION;candidate["env"].update(images())
    try: build(candidate)
    finally: restore_configuration(state,backup)
    enabled=live.enabled(state)
    if enabled:
        require(not local.archimesh(state,"GET","/admin/integrations")["paused"],"resume an intentionally paused integration before demo upgrade")
    models={item["modelId"]:local.archimesh(state,"GET","/admin/models/"+item["modelId"]+"/export") for item in local.archimesh(state,"GET","/admin/models")}
    local.save(backup/"models.json",models)
    archive=None
    db=local.compose(state,"ps","-q","turbo-db").stdout.strip();backend=local.compose(state,"ps","-q","turbo").stdout.strip()
    services=(["dataset-bridge"] if enabled else [])+["archimesh","turbo-ui","turbo"]
    print("Pausing demo writers; backup and migration trial: "+str(backup),flush=True)
    migration_started=False;paused_by_upgrade=False
    try:
        if enabled:
            local.compose(state,"stop","-t","60","dataset-bridge")
            pause(state);paused_by_upgrade=True
            archive=operation(state,"archive/export");local.save(backup/"integration-archive.json",archive)
        local.compose(state,"stop",*services)
        before=database.inventory(db);local.save(backup/"inventory-before.json",before)
        database.database_backup(db,backup/"turboea.dump")
        database.files_backup(backend,backup/"attachments.tar")
        with database.migration_trial(backup/"trial",backup/"turboea.dump",candidate["env"]["DEMO_TURBO_DB_IMAGE"],candidate["env"]["DEMO_TURBO_IMAGE"],state["env"]["DEMO_TURBO_SECRET"]) as (port,clone):
            trial=copy.deepcopy(candidate);trial["env"]["DEMO_TURBO_PORT"]=str(port)
            local.turbo_api(trial)
            require(before==database.inventory(clone),"migration clone preserves cards, relations and metamodel IDs/values")
            checks=contract_probe(trial)
        local.save(backup/"migration-trial.json",{"sourceVersion":PREVIOUS_VERSION,"targetVersion":VERSION,"identityAndDataPreserved":True,"checks":checks})
        print("Migration clone and write/readback contract passed; upgrading the existing demo",flush=True)
        import bootstrap
        bootstrap.override(candidate)
        migration_started=True
        local.compose(candidate,"up","-d","turbo-db","turbo","turbo-ui")
        local.turbo_api(candidate)
        current_db=local.compose(candidate,"ps","-q","turbo-db").stdout.strip()
        require(before==database.inventory(current_db),"in-place migration preserves all original cards, relations and metamodel IDs/values")
        local.save(local.directory(state)/"state.json",candidate)
        if enabled:
            live.enable(candidate,build=False,resume_operations=True);paused_by_upgrade=False
        else: local.server_start(candidate)
        for model,export in models.items():
            current=local.archimesh(candidate,"GET","/admin/models/"+model+"/export")
            require(current["snapshot"]==export["snapshot"],"ArchiMesh snapshot preserved during Turbo EA upgrade")
        if archive:
            pause(candidate);paused_by_upgrade=True
            after=operation(candidate,"archive/export")
            require(bindings(archive)==bindings(after),"canonical, external and model identity bindings preserved")
            resume(candidate);paused_by_upgrade=False
        report={"sourceVersion":PREVIOUS_VERSION,"targetVersion":VERSION,"databaseBackup":"turboea.dump","attachmentBackup":"attachments.tar",
                "databaseBackupSha256":hashlib.sha256((backup/"turboea.dump").read_bytes()).hexdigest(),"migrationTrial":checks,
                "cards":len(before["cards"]),"relations":len(before["relations"]),"models":len(models),"identitiesPreserved":True}
        local.save(backup/"result.json",report)
        state.clear();state.update(candidate)
        print("Upgrade verified; backups retained at "+str(backup),flush=True)
    except BaseException:
        if not migration_started:
            restore_configuration(state,backup)
            local.compose(state,"up","-d","turbo-db","turbo","turbo-ui")
            local.turbo_api(state);local.server_start(state)
            if paused_by_upgrade: resume(state)
            if enabled: local.compose(state,"up","-d","dataset-bridge")
            print("Migration trial failed; original version resumed without database changes",flush=True)
        else:
            if enabled:
                try: pause(candidate)
                except (OSError,RuntimeError): pass
            local.compose(candidate,"stop",*services,check=False)
            print("Upgrade stopped after migration began. Keep the services paused; restore the database backup before downgrading images. Backups: "+str(backup),flush=True)
        raise
