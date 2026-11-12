#!/usr/bin/env python3
"""Populate and verify an isolated three-system order-management demo.

Demo orchestration is optional: neither product imports this script.
"""
import argparse
import fcntl
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import time
import uuid

from generate import generate

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
RUNS = REPO / ".run/order-management"
sys.path.insert(0, str(REPO / "integrations/datahub-turboea/tools"))
from datahub_local import harness as dh
from datahub_local.order_demo import (turbo_seed, turbo_verify, aspects, datahub_seed,
                                     datahub_verify, save)
from datahub_local.probe import wait_for, require


def request_json(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", int(port), timeout=30)
    try:
        connection.request(method, path, None if body is None else json.dumps(body).encode(),
                           {"Accept":"application/json","Content-Type":"application/json", **(headers or {})})
        response = connection.getresponse()
        raw = response.read(16_777_217)
        require(len(raw) <= 16_777_216, "bounded demo HTTP response")
        value = json.loads(raw) if raw else None
        if response.status >= 300:
            # Do not expose remote bodies, credentials or tokens in exceptions/logs.
            raise RuntimeError(f"{method} {path}: HTTP {response.status}")
        return value
    finally:
        connection.close()


def initialize(containerized=False):
    run = uuid.uuid4().hex[:12]
    directory = RUNS / run
    directory.mkdir(parents=True, mode=0o700)
    dh_state = dh.initialize("it-" + run, ephemeral=True)
    ports = {dh_state["env"]["DATAHUB_GMS_PORT"],dh_state["env"]["DATAHUB_FRONTEND_PORT"]}
    def port():
        candidate = dh.free_port()
        while str(candidate) in ports: candidate = dh.free_port()
        ports.add(str(candidate))
        return str(candidate)
    state = {"run":run,"project":"northstar-demo-" + run,
             "env":{"DEMO_DB_PASSWORD":secrets.token_hex(24),"DEMO_TURBO_SECRET":secrets.token_hex(32),
                    "DEMO_BOLT_PORT":port(),"DEMO_KAFKA_PORT":port(),"DEMO_TURBO_PORT":port(),"DEMO_ARCHIMESH_PORT":port()},
             "turboEmail":"demo-admin@northstar.example.org", "turboPassword":"Demo1-" + secrets.token_hex(24)}
    if containerized:
        state["mode"] = "compose"
        state["env"].update(DEMO_TURBO_UI_PORT=port(),DEMO_ARCHIMESH_IMAGE="northstar-demo-archimesh:" + run,
            DEMO_TURBO_IMAGE="northstar-demo-turbo-backend:6d9220e",DEMO_TURBO_DB_IMAGE="northstar-demo-turbo-db:6d9220e",
            DEMO_TURBO_UI_IMAGE="northstar-demo-turbo-ui:6d9220e")
    save(directory / "state.json", state)
    print("Demo run: " + run, flush=True)
    return state


def load(run):
    if not re.fullmatch(r"[0-9a-f]{12}", run): raise ValueError("Expected a demo run ID of 12 hex characters")
    state = json.loads((RUNS / run / "state.json").read_text())
    require(state["run"] == run and state["project"] == "northstar-demo-" + run, "local demo project scope")
    expected = {"DEMO_DB_PASSWORD","DEMO_TURBO_SECRET","DEMO_BOLT_PORT", "DEMO_KAFKA_PORT","DEMO_TURBO_PORT","DEMO_ARCHIMESH_PORT"}
    if state.get("mode") == "compose":
        expected.update({"DEMO_TURBO_UI_PORT","DEMO_ARCHIMESH_IMAGE","DEMO_TURBO_IMAGE","DEMO_TURBO_DB_IMAGE","DEMO_TURBO_UI_IMAGE"})
    else: require("mode" not in state,"known demo runtime mode")
    require(set(state["env"]) == expected, "local demo environment keys")
    for key, value in state["env"].items():
        if key.endswith("PORT"): require(value.isdigit() and 1024 <= int(value) <= 65535, "loopback port")
    return state


def directory(state): return RUNS / state["run"]
def datahub(state): return dh.load(dh.RUNS / ("it-" + state["run"]))


def compose(state, *args, input_=None, check=True):
    env = {k:v for k,v in os.environ.items() if not k.startswith(("COMPOSE_","DEMO_"))}
    env.update(state["env"])
    command = ["docker","compose","--project-name",state["project"],"--file",str(HERE / "compose.json")]
    if state.get("mode") == "compose":
        command.extend(["--file",str(directory(state) / "compose.override.json")])
    command.extend(args)
    result = subprocess.run(command, input=input_, capture_output=True, text=True, env=env, timeout=1200)
    output = result.stdout + result.stderr
    for value in (state["env"]["DEMO_DB_PASSWORD"], state["env"]["DEMO_TURBO_SECRET"], state["turboPassword"]):
        output = output.replace(value,"<redacted>")
    with (directory(state) / "compose.log").open("a") as stream: stream.write(output)
    if check and result.returncode: raise RuntimeError("Demo Compose failed; see " + str(directory(state) / "compose.log"))
    return result


def archimesh(state, method, path, body=None):
    return request_json(state["env"]["DEMO_ARCHIMESH_PORT"],method,path,body,
                {"X-Archimesh-User":"northstar-demo-admin","X-Archimesh-Roles":"admin"})


def server_start(state):
    def database_ready():
        return compose(state,"exec","-T","neo4j","cypher-shell","-u","neo4j","-p",state["env"]["DEMO_DB_PASSWORD"],
                       input_="RETURN 1;",check=False).returncode
    wait_for(database_ready,lambda code:code == 0,"Neo4j before JVM startup",180)
    if state.get("mode") == "compose":
        compose(state,"up","-d","archimesh")
        wait_for(lambda:archimesh(state,"GET","/admin/models"),lambda r:isinstance(r,list),"containerized ArchiMesh startup",120)
        return
    env = {k:v for k,v in os.environ.items() if not k.startswith(("QUARKUS_","ARCHIMESH_","NEO4J_","KAFKA_","MP_","SMALLRYE_"))
           and k not in {"JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"}}
    env.update(QUARKUS_HTTP_HOST="127.0.0.1",QUARKUS_HTTP_PORT=state["env"]["DEMO_ARCHIMESH_PORT"],
               NEO4J_URI="bolt://127.0.0.1:" + state["env"]["DEMO_BOLT_PORT"],NEO4J_USER="neo4j",NEO4J_PASSWORD=state["env"]["DEMO_DB_PASSWORD"],
               KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:" + state["env"]["DEMO_KAFKA_PORT"],KAFKA_TOPIC_PREFIX="northstar",
               KAFKA_CONSUMER_GROUP_ID=state["project"],ARCHIMESH_AUTHZ_ENABLED="true",ARCHIMESH_IDENTITY_MODE="bootstrap",
               ARCHIMESH_QUARKUS_OIDC_ENABLED="false",ARCHIMESH_CHECKPOINT_MAINTENANCE_ENABLED="false",ARCHIMESH_INTEGRATIONS_ENABLED="false")
    require(not (directory(state) / "server.pid").exists(), "server PID file absent before startup")
    with (directory(state) / "server.log").open("a") as log:
        process = subprocess.Popen(["java","-Xms64m","-Xmx384m","-Dnorthstar.demo.run=" + state["run"],
            "-jar",str(REPO / "server/target/quarkus-app/quarkus-run.jar")],cwd=REPO,env=env,
            stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (directory(state) / "server.pid").write_text(str(process.pid))
    def ready():
        if process.poll() is not None:
            raise RuntimeError("ArchiMesh demo exited; inspect " + str(directory(state) / "server.log"))
        return archimesh(state,"GET","/admin/models")
    wait_for(ready,lambda r:isinstance(r,list), "ArchiMesh demo startup",90)


def server_stop(state):
    if state.get("mode") == "compose":
        compose(state,"stop","archimesh")
        return
    pidfile = directory(state) / "server.pid"
    if not pidfile.exists(): return
    pid = int(pidfile.read_text())
    process = subprocess.run(["ps","-p",str(pid),"-o","args="],capture_output=True,text=True)
    if process.returncode == 0:
        require("-Dnorthstar.demo.run=" + state["run"] in process.stdout, "PID still belongs to this demo")
        os.kill(pid,signal.SIGTERM)
        for _ in range(100):
            child, _status = (0,0)
            try: child, _status = os.waitpid(pid,os.WNOHANG)
            except ChildProcessError: pass
            if child or subprocess.run(["ps","-p",str(pid)],capture_output=True).returncode != 0: break
            time.sleep(.1)
        else: raise RuntimeError("Demo server did not stop; retained PID for operator review")
    pidfile.unlink()


def turbo_api(state, register=False):
    port = state["env"]["DEMO_TURBO_PORT"]
    health = wait_for(lambda:request_json(port,"GET","/api/health"),lambda r:bool(r),"Turbo EA demo readiness",180)
    require(health["version"] == "2.194.0", "pinned Turbo EA API version")
    endpoint = "register" if register else "login"
    body = {"email":state["turboEmail"],"password":state["turboPassword"]}
    if register: body["display_name"] = "Northstar Demo Administrator"
    response = request_json(port,"POST","/api/v1/auth/" + endpoint,body)
    token = response["access_token"]
    return lambda method,path,body=None:request_json(port,method,path,body,{"Authorization":"Bearer " + token})


def seed(state):
    out = directory(state) / "artifacts"
    counts = generate(out)
    fixture = json.loads((out / "metadata.json").read_text())
    api = turbo_api(state,register=not (directory(state) / "turbo-journal.json").exists())
    journal = turbo_seed(api,fixture,directory(state) / "turbo-journal.json")
    apps = turbo_verify(api,fixture,journal)
    records = aspects(fixture,apps,state["project"])
    datahub_seed(datahub(state),records)
    model = json.loads((out / "archimesh-model.json").read_text())
    catalog = archimesh(state,"GET","/admin/models")
    if not any(entry["modelId"] == model["model"]["modelId"] for entry in catalog):
        archimesh(state,"POST","/admin/models/import?overwrite=false",model)
    crosswalk = {"profile":"synthetic-demo-only-v1", "registryBindingsInstalled":False,
        "applications":[{"canonicalId":app["canonicalId"],"turboNativeId":apps[app["id"]]["id"],
            "datahubUrn":app["urn"],"archimeshElementId":"elem:" + app["archiElementId"]} for app in fixture["applications"]],
        "datasets":[{"canonicalId":d["canonicalId"],"datahubUrn":d["urn"],
            "relatedLogicalDataCanonicalId":next(x["canonicalId"] for x in fixture["logicalData"] if x["id"] == d["logicalData"])} for d in fixture["datasets"]]}
    save(out / "crosswalk.json",crosswalk)
    save(out / "counts.json",counts)


def verify(state, label="verification"):
    out = directory(state) / "artifacts"
    fixture = json.loads((out / "metadata.json").read_text())
    journal = json.loads((directory(state) / "turbo-journal.json").read_text())
    api = turbo_api(state)
    apps = turbo_verify(api,fixture,journal)
    cards = len(fixture["applications"]) + len(fixture["logicalData"]) + len(fixture["infrastructure"])
    report = {"run":state["run"],"turbo":{"cards":cards,
              "relations":len(journal["bindings"]) - cards}, "datahub":datahub_verify(datahub(state),aspects(fixture,apps,state["project"]))}
    expected = json.loads((out / "archimesh-model.json").read_text())
    model = expected["model"]["modelId"]
    exported = archimesh(state,"GET","/admin/models/" + model + "/export")
    for key in ("elements","relationships"):
        actual = {e["id"]:e for e in exported["snapshot"][key]}
        wanted = expected["snapshot"][key]
        require(len(actual) == len(wanted), "ArchiMesh " + key + " count")
        for item in wanted:
            require(all(actual[item["id"]].get(k) == v for k,v in item.items() if k != "accessType"), "ArchiMesh " + key + " semantic readback")
    require(exported["model"]["headRevision"] == expected["model"]["headRevision"], "full ArchiMesh revision history")
    require(exported["opBatches"] == expected["opBatches"], "persisted operations and causal metadata")
    wanted_properties = [op for op in expected["opBatches"][0]["ops"] if op["type"] == "SetProperty"]
    for op in wanted_properties:
        require(op in exported["snapshot"]["propertyState"], "persisted demo access annotation")
    require(not exported["snapshot"]["views"], "viewless formal model")
    integrity = archimesh(state,"GET","/admin/models/" + model + "/integrity")
    require(integrity["ok"], "ArchiMesh graph integrity")
    status = archimesh(state,"GET","/admin/models/" + model + "/status")
    require(status["consistency"]["consistent"], "ArchiMesh commit and materialized graph consistency")
    report["archimesh"] = {"elements":len(exported["snapshot"]["elements"]),"relationships":len(exported["snapshot"]["relationships"]),
                           "headRevision":exported["model"]["headRevision"],"integrity":integrity,"status":status}
    save(out / (label + ".json"),report)
    save(out / "archimesh-verified-export.json",exported)
    print(json.dumps(report,indent=2),flush=True)


def up(state, skip_build, retain=True):
    if not skip_build and state.get("mode") != "compose":
        print("Building the current ArchiMesh server",flush=True)
        with (directory(state) / "build.log").open("w") as log:
            subprocess.run(["mvn","-q","-DskipTests","package"],cwd=REPO / "server",stdout=log,stderr=subprocess.STDOUT,check=True)
    if state.get("mode") == "compose": compose(state,"up","-d","neo4j","kafka","turbo-db","turbo","turbo-ui")
    else:
        require((REPO / "server/target/quarkus-app/quarkus-run.jar").exists(),"packaged ArchiMesh server")
        compose(state,"up","-d")
    def schema():
        return compose(state,"exec","-T","neo4j","cypher-shell","-u","neo4j","-p",state["env"]["DEMO_DB_PASSWORD"],
                       input_=(REPO / "neo4j/schema.cypher").read_text(),check=False).returncode
    wait_for(schema,lambda code:code == 0,"Neo4j demo readiness",120)
    server_start(state)
    dh.up(datahub(state))
    seed(state)
    verify(state)
    if retain:
        print("Retained demo. Credentials and artifacts: " + str(directory(state)),flush=True)
        print("ArchiMesh: http://127.0.0.1:" + state["env"]["DEMO_ARCHIMESH_PORT"] + "/admin-ui/",flush=True)
        print("Turbo EA API: http://127.0.0.1:" + state["env"]["DEMO_TURBO_PORT"],flush=True)


def down(state, volumes=False):
    server_stop(state)
    compose(state,"down",*(["--volumes"] if volumes else []))
    dh.down(datahub(state),volumes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["up","start","seed","verify","restart","down","test"])
    parser.add_argument("--run",help="12-character run ID printed by up/test")
    parser.add_argument("--skip-build",action="store_true")
    parser.add_argument("--volumes",action="store_true",help="Explicitly remove this run's demo volumes on down")
    args = parser.parse_args()
    if args.action in ("up","test") and args.run: parser.error("up/test create a fresh unique run")
    if args.action not in ("up","test") and not args.run: parser.error("this action requires --run")
    if args.volumes and args.action != "down": parser.error("--volumes applies only to down")
    state = initialize() if args.action in ("up","test") else load(args.run)
    with (directory(state) / "lock").open("w") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            if args.action in ("up","test"): up(state,args.skip_build,retain=args.action!="test")
            elif args.action == "start":
                compose(state,"up","-d")
                dh.up(datahub(state))
                server_start(state)
                verify(state)
            elif args.action == "seed":
                seed(state)
                verify(state)
            if args.action in ("restart","test"):
                print("Restarting all three targets without reseeding",flush=True)
                server_stop(state)
                compose(state,"restart","neo4j","turbo")
                dh.compose(datahub(state),"restart","gms","frontend")
                server_start(state)
                verify(state,"after-restart")
            elif args.action == "verify": verify(state)
            elif args.action == "down": down(state,args.volumes)
        finally:
            if args.action == "test": down(state,volumes=True)


if __name__ == "__main__":
    try: main()
    except (RuntimeError,ValueError,OSError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr)
        sys.exit(1)
