"""Dataset discovery/scheduling. All identity reservations and target writes use the owner API."""
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
    def __init__(self, base_url, token, *, approved_plain_http=False, max_pages=100):
        self.base=urlsplit(base_url)
        local=self.base.hostname in ("localhost","127.0.0.1","::1")
        if (not self.base.hostname or self.base.username or self.base.password or self.base.query or self.base.fragment
                or self.base.path not in ("","/") or self.base.scheme not in ("http","https")
                or self.base.scheme=="http" and not (local or approved_plain_http)):
            raise ValueError("Invalid DataHub discovery origin")
        if not 1 <= max_pages <= 1000: raise ValueError("Invalid discovery page limit")
        self.token,self.max_pages=token,max_pages

    def datasets(self):
        query='query($start: Int!) { searchAcrossEntities(input: {types: [DATASET], query: "*", start: $start, count: 100}) { start count total searchResults { entity { urn } } } }'
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
                    if not urn.startswith("urn:li:dataset:(") or not urn.endswith(")"): raise DiscoveryError("Unexpected discovery entity type")
                    found.add(urn)
                start+=len(rows)
                if start>=page["total"]: return sorted(found)
                if not rows: raise DiscoveryError("Discovery pagination made no progress")
            except (KeyError,TypeError,ValueError) as invalid:
                raise DiscoveryError("Invalid DataHub discovery response or credential") from None
            finally: connection.close()
        raise DiscoveryError("Discovery page limit reached; no partial scan is published")


class DatasetBridge:
    def __init__(self, owner, discovery, profile, prefixes):
        self.owner,self.discovery,self.profile=owner,discovery,text(profile,128)
        self.prefixes=tuple(text(prefix,2048) for prefix in prefixes)
        if not self.prefixes or any(not prefix.startswith("urn:li:dataset:(") for prefix in self.prefixes):
            raise ValueError("Dataset URN scope required")

    def cycle(self):
        report={"verified":0,"changed":0,"blocked":0,"codes":{}}
        for urn in self.discovery.datasets():
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
    source=config["datahub"]
    bridge=DatasetBridge(client,DataHubDiscovery(source["baseUrl"],lambda:os.environ[source["tokenEnvironment"]],
                         approved_plain_http=source.get("approvedPlainHttp",False)),config["profile"],config["nativePrefixes"])
    interval=config.get("intervalSeconds",10)
    if not isinstance(interval,int) or not 2<=interval<=3600: raise ValueError("Invalid bridge interval")
    stopped=threading.Event()
    for sig in (signal.SIGINT,signal.SIGTERM): signal.signal(sig,lambda *_:stopped.set())
    while not stopped.is_set():
        try: result=bridge.cycle()
        except (DiscoveryError,OSError,http.client.HTTPException): result={"blocked":1,"codes":{"SOURCE_UNAVAILABLE":1}}
        print(json.dumps(result,sort_keys=True),flush=True)
        if args.once: return 1 if result.get("blocked") else 0
        stopped.wait(interval)
    return 0
