"""Catalog discovery and application scheduling. Identity reservations and target writes use the owner API."""
import argparse
import http.client
import json
import os
from pathlib import Path
import signal
import threading
from urllib.parse import urlsplit
import uuid

from .owner import OwnerClient, OwnerError, text


class DiscoveryError(RuntimeError):
    pass


class DataHubDiscovery:
    def __init__(self, base_url, token, *, approved_plain_http=False, max_pages=100, external_type="dataset"):
        self.base=urlsplit(base_url)
        local=self.base.hostname in ("localhost","127.0.0.1","::1")
        if (not self.base.hostname or self.base.username or self.base.password or self.base.query or self.base.fragment
                or self.base.path not in ("","/") or self.base.scheme not in ("http","https")
                or self.base.scheme=="http" and not (local or approved_plain_http)):
            raise ValueError("Invalid DataHub discovery origin")
        if not 1 <= max_pages <= 1000: raise ValueError("Invalid discovery page limit")
        self.token,self.max_pages=token,max_pages
        if external_type not in ("dataset","dataProduct"): raise ValueError("Unsupported catalog projection type")
        self.external_type=external_type

    def datasets(self):
        return self.entities()

    def entities(self):
        type_="DATASET" if self.external_type=="dataset" else "DATA_PRODUCT"
        query='query($start: Int!) { searchAcrossEntities(input: {types: ['+type_+'], query: "*", start: $start, count: 100}) { start count total searchResults { entity { urn } } } }'
        start=0; found=set()
        for _ in range(self.max_pages):
            kind=http.client.HTTPSConnection if self.base.scheme=="https" else http.client.HTTPConnection
            connection=kind(self.base.hostname,self.base.port,timeout=20)
            try:
                token=self.token()
                if not token or any(ord(c)<33 or ord(c)>126 for c in token): raise ValueError("Invalid DataHub discovery credential")
                connection.request("POST","/api/graphql",json.dumps({"query":query,"variables":{"start":start}}),
                                   {"Authorization":"Bearer "+token,"Content-Type":"application/json"})
                response=connection.getresponse(); raw=response.read(2_097_153)
                if response.status!=200 or len(raw)>2_097_152: raise DiscoveryError("DataHub discovery request failed")
                value=json.loads(raw)
                if value.get("errors"): raise DiscoveryError("DataHub discovery was denied or unavailable")
                page=value["data"]["searchAcrossEntities"]
                if page["start"]!=start or not isinstance(page["total"],int): raise DiscoveryError("Invalid discovery pagination")
                rows=page["searchResults"]
                for row in rows:
                    urn=text(row["entity"]["urn"],8192)
                    if (self.external_type=="dataset" and (not urn.startswith("urn:li:dataset:(") or not urn.endswith(")"))
                            or self.external_type=="dataProduct" and (not urn.startswith("urn:li:dataProduct:") or len(urn)<=len("urn:li:dataProduct:"))):
                        raise DiscoveryError("Unexpected discovery entity type")
                    found.add(urn)
                start+=len(rows)
                if start>=page["total"]: return sorted(found)
                if not rows: raise DiscoveryError("Discovery pagination made no progress")
            except (KeyError,TypeError,ValueError) as invalid:
                raise DiscoveryError("Invalid DataHub discovery response or credential") from None
            finally: connection.close()
        raise DiscoveryError("Discovery page limit reached; no partial scan is published")


class DatasetBridge:
    def __init__(self, owner, discovery, profile, prefixes, external_type="dataset"):
        self.owner,self.discovery,self.profile=owner,discovery,text(profile,128)
        self.prefixes=tuple(text(prefix,2048) for prefix in prefixes)
        self.external_type=external_type
        if external_type not in ("dataset","dataProduct"): raise ValueError("Unsupported catalog projection type")
        urn_prefix="urn:li:dataset:(" if external_type=="dataset" else "urn:li:dataProduct:"
        if not self.prefixes or any(not prefix.startswith(urn_prefix) for prefix in self.prefixes):
            raise ValueError("Matching dataset/product URN scope required")

    def cycle(self):
        report={"verified":0,"changed":0,"blocked":0,"codes":{}}
        for urn in (self.discovery.datasets() if self.external_type=="dataset" else self.discovery.entities()):
            if not urn.startswith(self.prefixes): continue
            correlation=str(uuid.uuid5(uuid.NAMESPACE_URL,"datahub-turboea-projection:"+self.profile+"\0"+urn))
            try:
                plan=self.owner.prepare_projection(self.profile,urn,correlation)
                if plan["state"]=="PREPARED":
                    plan=self.owner.execute_projection(self.profile,urn,correlation,plan)
                    report["changed"]+=1
                if plan["state"]=="VERIFIED": report["verified"]+=1
                else:
                    report["blocked"]+=1
                    report["codes"]["REVIEW_REQUIRED"]=report["codes"].get("REVIEW_REQUIRED",0)+1
            except OwnerError as failure:
                report["blocked"]+=1
                report["codes"][failure.code]=report["codes"].get(failure.code,0)+1
        return report


class ApplicationBridge:
    """Poll exact approved Turbo EA IDs through the owner. No source/target credentials or second registry."""
    def __init__(self, owner, profile, native_ids):
        self.owner,self.profile=owner,text(profile,128)
        if not isinstance(native_ids,(list,tuple)): raise ValueError("Exact application ID list required")
        self.native_ids=tuple(sorted(set(text(value,8192) for value in native_ids)))
        if not 1 <= len(self.native_ids) <= 256: raise ValueError("Bounded exact application scope required")

    def cycle(self):
        report={"verified":0,"changed":0,"blocked":0,"codes":{}}
        for native_id in self.native_ids:
            correlation=str(uuid.uuid5(uuid.NAMESPACE_URL,"turboea-datahub-application:"+self.profile+"\0"+native_id))
            try:
                plan=self.owner.prepare_application(self.profile,native_id,correlation)
                if plan["state"]=="PREPARED":
                    plan=self.owner.execute_application(self.profile,native_id,correlation,plan)
                    report["changed"]+=1
                if plan["state"]=="VERIFIED": report["verified"]+=1
                else:
                    report["blocked"]+=1
                    report["codes"]["REVIEW_REQUIRED"]=report["codes"].get("REVIEW_REQUIRED",0)+1
            except OwnerError as failure:
                report["blocked"]+=1
                report["codes"][failure.code]=report["codes"].get(failure.code,0)+1
        return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config",type=Path,required=True)
    parser.add_argument("--once",action="store_true")
    args=parser.parse_args(); config=json.loads(args.config.read_text())
    if config.get("schemaVersion")!=1: raise ValueError("Unsupported bridge configuration")
    owner=config["owner"]; bootstrap=owner.get("bootstrapUser")
    headers=(lambda:{"X-Archimesh-User":bootstrap,"X-Archimesh-Roles":"federation_bridge"}) if bootstrap else (
             lambda:{"Authorization":"Bearer "+os.environ[owner["tokenEnvironment"]]})
    client=OwnerClient(owner["baseUrl"],headers,bootstrap=bool(bootstrap))
    bridges=[]
    catalogs=list(config.get("catalogs",[]))
    if "datahub" in config: catalogs.insert(0,config)
    for catalog in catalogs:
        source=catalog["datahub"]; external_type=catalog.get("externalType","dataset")
        bridges.append(DatasetBridge(client,DataHubDiscovery(source["baseUrl"],lambda source=source:os.environ[source["tokenEnvironment"]],
                       approved_plain_http=source.get("approvedPlainHttp",False),external_type=external_type),
                       catalog["profile"],catalog["nativePrefixes"],external_type))
    for application in config.get("applications",[]):
        bridges.append(ApplicationBridge(client,application["profile"],application["nativeIds"]))
    if not bridges or len(bridges)>32: raise ValueError("Explicit bridge profiles required")
    interval=config.get("intervalSeconds",10)
    if not isinstance(interval,int) or not 2<=interval<=3600: raise ValueError("Invalid bridge interval")
    stopped=threading.Event()
    for sig in (signal.SIGINT,signal.SIGTERM): signal.signal(sig,lambda *_:stopped.set())
    while not stopped.is_set():
        result={"verified":0,"changed":0,"blocked":0,"codes":{}}
        for bridge in bridges:
            try: lane=bridge.cycle()
            except (DiscoveryError,OSError,http.client.HTTPException): lane={"blocked":1,"codes":{"SOURCE_UNAVAILABLE":1}}
            for key in ("verified","changed","blocked"): result[key]+=lane.get(key,0)
            for code,count in lane.get("codes",{}).items(): result["codes"][code]=result["codes"].get(code,0)+count
        print(json.dumps(result,sort_keys=True),flush=True)
        if args.once: return 1 if result.get("blocked") else 0
        stopped.wait(interval)
    return 0
