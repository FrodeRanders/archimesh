#!/usr/bin/env python3
"""Clean-checkout demo: Docker builds, all three systems, initial data and readback."""
import argparse
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request

import local
from datahub_local.order_demo import save
from datahub_local.probe import require, wait_for

HERE, REPO = local.HERE, local.REPO
CACHE = REPO / ".run/demo-build"
REVISION = "6d9220e52d47b41f9d79351ec3233ded1f76fdc6"
SOURCE_URL = "https://codeload.github.com/vincentmakes/turbo-ea/tar.gz/" + REVISION
SOURCE_SHA256 = "ae3dc880bda752b5ba11ad21fe87bce271f23aef12f3fc926decda8300347103"
CURRENT = local.RUNS / "demo-current"


def preflight():
    if not shutil.which("docker"): raise RuntimeError("Docker with Compose is required; install/start Docker before running this demo")
    for command in (["docker","info","--format","{{.ServerVersion}}"],["docker","compose","version"]):
        result = subprocess.run(command,capture_output=True,text=True,timeout=30)
        if result.returncode: raise RuntimeError("Docker daemon and Docker Compose must be available to this user")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda:stream.read(1_048_576),b""): digest.update(chunk)
    return digest.hexdigest()


def extract_source(archive, destination):
    """Extract selected regular source files only, with no links or path traversal."""
    prefix = "turbo-ea-" + REVISION + "/"
    allowed = {"Dockerfile","VERSION","CHANGELOG.md",".dockerignore"}
    files, total = {}, 0
    with tarfile.open(archive,"r:gz") as source:
        for member in source:
            require(member.name.startswith(prefix) or member.name == prefix[:-1],"pinned archive root")
            relative = member.name[len(prefix):]
            path = Path(relative)
            require(not path.is_absolute() and ".." not in path.parts,"safe source archive paths")
            if not relative or not (relative in allowed or relative.startswith(("backend/","frontend/"))): continue
            require(member.isdir() or member.isfile(),"regular source archive members")
            if member.isdir(): continue
            total += member.size
            require(total <= 1_073_741_824 and len(files) < 10000,"bounded extracted demo source")
            target = destination / path
            target.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as input_, target.open("wb") as output:
                shutil.copyfileobj(input_,output)
            files[relative] = sha256(target)
    require((destination / "VERSION").read_text().strip() == "2.194.0","pinned source version")
    save(destination / "source-manifest.json",{"revision":REVISION,"archiveSha256":SOURCE_SHA256,"files":files})


def source():
    CACHE.mkdir(parents=True,exist_ok=True,mode=0o700)
    destination = CACHE / ("turboea-" + REVISION)
    manifest = destination / "source-manifest.json"
    if manifest.exists():
        record = json.loads(manifest.read_text())
        require(record["revision"] == REVISION and record["archiveSha256"] == SOURCE_SHA256,"cached source pin")
        require(all((destination/p).is_file() and sha256(destination/p) == digest for p,digest in record["files"].items()),"cached source integrity; move the changed cache aside before rebuilding")
        return destination
    archive = CACHE / ("turboea-" + REVISION + ".tar.gz")
    if not archive.exists():
        print("Downloading pinned public Turbo EA source (first run only)",flush=True)
        partial = archive.with_suffix(".partial")
        size = 0
        with urllib.request.urlopen(SOURCE_URL,timeout=60) as response, partial.open("wb") as stream:
            while True:
                chunk = response.read(1_048_576)
                if not chunk: break
                size += len(chunk)
                require(size <= 402_653_184,"bounded source archive download")
                stream.write(chunk)
        require(sha256(partial) == SOURCE_SHA256,"downloaded source archive SHA-256")
        partial.replace(archive)
    require(sha256(archive) == SOURCE_SHA256,"pinned source archive SHA-256")
    destination.mkdir(parents=True,exist_ok=True)
    extract_source(archive,destination)
    return destination


def override(state):
    env = state["env"]
    document = {"services":{
        "turbo-db":{"image":env["DEMO_TURBO_DB_IMAGE"]},
        "turbo":{"image":env["DEMO_TURBO_IMAGE"]},
        "turbo-ui":{"image":env["DEMO_TURBO_UI_IMAGE"],"ports":["127.0.0.1:" + env["DEMO_TURBO_UI_PORT"] + ":8080"],
                    "depends_on":["turbo"]},
        "archimesh":{"image":env["DEMO_ARCHIMESH_IMAGE"],"ports":["127.0.0.1:" + env["DEMO_ARCHIMESH_PORT"] + ":8081"],
            "environment":{"QUARKUS_HTTP_HOST":"0.0.0.0","QUARKUS_HTTP_PORT":"8081",
                "NEO4J_URI":"bolt://neo4j:7687","NEO4J_USER":"neo4j","NEO4J_PASSWORD":"${DEMO_DB_PASSWORD}",
                "KAFKA_BOOTSTRAP_SERVERS":"kafka:9092","KAFKA_TOPIC_PREFIX":"northstar","KAFKA_CONSUMER_GROUP_ID":state["project"],
                "ARCHIMESH_AUTHZ_ENABLED":"true","ARCHIMESH_IDENTITY_MODE":"bootstrap","ARCHIMESH_QUARKUS_OIDC_ENABLED":"false",
                "ARCHIMESH_INTEGRATIONS_ENABLED":"false","ARCHIMESH_CHECKPOINT_MAINTENANCE_ENABLED":"false"},
            "depends_on":["neo4j","kafka"]}}}
    save(local.directory(state) / "compose.override.json",document)


def build(state):
    CACHE.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (CACHE / "lock").open("w") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        checkout = source()
        # Separate UI build preserves upstream source; no sibling checkout is modified.
        shutil.copyfile(HERE / "turbo-ui.conf",checkout / "demo-ui.conf")
        shutil.copyfile(HERE / "Dockerfile.turbo-ui",checkout / "Dockerfile.demo-ui")
        commands = [
            ("Turbo EA database",["--target","db","--tag",state["env"]["DEMO_TURBO_DB_IMAGE"],str(checkout)]),
            ("Turbo EA backend",["--target","backend","--tag",state["env"]["DEMO_TURBO_IMAGE"],str(checkout)]),
            ("Turbo EA web UI",["--file",str(checkout / "Dockerfile.demo-ui"),"--tag",state["env"]["DEMO_TURBO_UI_IMAGE"],str(checkout)]),
            ("ArchiMesh server and admin UI",["--file",str(HERE / "Dockerfile.archimesh"),"--tag",state["env"]["DEMO_ARCHIMESH_IMAGE"],str(REPO)])]
        for name, arguments in commands:
            print("Building " + name + "; detailed output in " + str(local.directory(state) / "build.log"),flush=True)
            with (local.directory(state) / "build.log").open("a") as log:
                label="org.opencontainers.image.revision=" + REVISION if name.startswith("Turbo EA") else "org.archimesh.demo.source=local-workspace"
                result = subprocess.run(["docker","build","--label",label,*arguments],
                                        stdout=log,stderr=subprocess.STDOUT,timeout=2400)
            if result.returncode: raise RuntimeError("Demo image build failed: " + name + "; inspect build.log")
    override(state)


def page(port, path):
    connection = http.client.HTTPConnection("127.0.0.1",int(port),timeout=15)
    try:
        connection.request("GET",path)
        response = connection.getresponse()
        body = response.read(2_097_153)
        return response.status,body
    finally: connection.close()


def verify_pages(state):
    for port,path in ((state["env"]["DEMO_ARCHIMESH_PORT"],"/admin-ui/"),(state["env"]["DEMO_TURBO_UI_PORT"],"/")):
        wait_for(lambda:page(port,path),lambda r:r[0] == 200 and b"<html" in r[1].lower(),"demo web page " + path,120)
    # Verify the Turbo EA UI's same-origin API proxy, rather than just its HTML shell.
    health = local.request_json(state["env"]["DEMO_TURBO_UI_PORT"],"GET","/api/health")
    require(health["version"] == "2.194.0","Turbo EA frontend API proxy")


def access(state):
    dh_state = local.datahub(state)
    model_package = local.directory(state) / "artifacts/archimesh-model.json"
    model_id = json.loads(model_package.read_text())["model"]["modelId"]
    archi_connection = {"wsBaseUrl":"ws://127.0.0.1:" + state["env"]["DEMO_ARCHIMESH_PORT"],
                       "modelId":model_id, "modelRef":"HEAD", "modelPackage":str(model_package)}
    endpoints = {"archimesh":"http://127.0.0.1:" + state["env"]["DEMO_ARCHIMESH_PORT"] + "/admin-ui/",
                 "turboea":"http://127.0.0.1:" + state["env"]["DEMO_TURBO_UI_PORT"],
                 "datahub":"http://127.0.0.1:" + dh_state["env"]["DATAHUB_FRONTEND_PORT"]}
    save(local.directory(state) / "access.json",{"run":state["run"],"endpoints":endpoints,
        "turboCredentials":"state.json", "datahubCredentials":str(Path(dh_state["directory"]) / "user.props"),
        "archimeshBootstrapUser":"northstar-demo-admin","archimeshBootstrapRoles":"admin",
        "archiConnection":archi_connection})
    print("\nDemo connection details — run " + state["run"],flush=True)
    for name,url in endpoints.items(): print(name + " web UI: " + url,flush=True)
    print("Archi: Tools -> Open Archimesh Model from Server...",flush=True)
    print("  WebSocket Base URL: " + archi_connection["wsBaseUrl"],flush=True)
    print("  Model: " + model_id + " (HEAD)",flush=True)
    print("  Server import package: " + str(model_package),flush=True)
    print("  Identity: northstar-demo-admin; bootstrap roles: admin; bearer token: leave empty",flush=True)
    print("Turbo EA catalog in Archi: disabled in this demo; the three systems are seeded separately.",flush=True)
    print("Private access details: " + str(local.directory(state) / "access.json"),flush=True)
    executable = os.environ.get("ARCHI_EXECUTABLE", "/Applications/Archi.app/Contents/MacOS/Archi" if sys.platform == "darwin" else "Archi")
    launch = ["env", "ARCHIMESH_BOOTSTRAP_USER=northstar-demo-admin", "ARCHIMESH_BOOTSTRAP_ROLES=admin",
              "ARCHIMESH_STARTUP_PULL_ENABLED=true", "ARCHIMESH_STARTUP_PULL_USER_ID=northstar-demo-admin",
              "ARCHIMESH_STARTUP_PULL_WS_BASE_URL=" + archi_connection["wsBaseUrl"],
              "ARCHIMESH_STARTUP_PULL_MODEL_ID=" + model_id, executable, "-clean"]
    print("Close open models, quit Archi, then launch it connected to this model (requires the current plugin):",flush=True)
    print("  " + shlex.join(launch),flush=True)
    print("Redisplay these addresses: ./scripts/demo.sh connection --run " + state["run"],flush=True)


def inventory(state):
    config = json.loads(local.compose(state,"config","--format","json").stdout)
    images = sorted({s["image"] for s in config["services"].values()})
    result = subprocess.run(["docker","image","inspect",*images],capture_output=True,text=True,check=True,timeout=30)
    save(local.directory(state) / "images.json",{"sourceRevision":REVISION,"sourceArchiveSha256":SOURCE_SHA256,
        "images":[{"id":i["Id"],"tags":i.get("RepoTags",[]),"digests":i.get("RepoDigests",[]),"architecture":i["Architecture"]}
                  for i in json.loads(result.stdout)]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",nargs="?",default="up",choices=["up","test","start","status","connection","seed","verify","restart","down"])
    parser.add_argument("--run",help="Run ID; defaults to the last up created by this bootstrap")
    parser.add_argument("--volumes",action="store_true",help="Explicitly remove this demo's volumes on down")
    args = parser.parse_args()
    if args.volumes and args.action != "down": parser.error("--volumes applies only to down")
    if args.action in ("up","test") and args.run: parser.error("up/test always create fresh demo storage")
    if args.action != "connection": preflight()
    state = local.initialize(containerized=True) if args.action in ("up","test") else local.load(args.run or json.loads(CURRENT.read_text()))
    require(state.get("mode") == "compose","containerized demo run")
    if args.action == "connection":
        access(state)
        print("Saved addresses only; server availability has not been checked.",flush=True)
        return
    if args.action == "up": save(CURRENT,state["run"])
    if args.action in ("up","test"): override(state)
    # CURRENT is a JSON string, not unvalidated shell content.
    with (local.directory(state) / "lock").open("w") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            if args.action in ("up","test"):
                build(state)
                local.up(state,skip_build=True,retain=args.action!="test")
                inventory(state)
            elif args.action == "start":
                local.compose(state,"up","-d","neo4j","kafka","turbo-db","turbo","turbo-ui")
                local.dh.up(local.datahub(state))
                local.server_start(state)
                local.verify(state)
            elif args.action == "seed":
                local.seed(state)
                local.verify(state)
            elif args.action == "verify": local.verify(state)
            elif args.action == "down": local.down(state,args.volumes)
            elif args.action == "status":
                print(local.compose(state,"ps").stdout)
                print(local.dh.compose(local.datahub(state),"ps",capture=True)[1])
                access(state)
            if args.action in ("restart","test"):
                print("Restarting without reseeding",flush=True)
                local.server_stop(state)
                local.compose(state,"restart","neo4j","turbo","turbo-ui")
                local.dh.compose(local.datahub(state),"restart","gms","frontend")
                local.server_start(state)
                local.verify(state,"after-restart")
            if args.action not in ("down","status"):
                verify_pages(state)
                if args.action!="test": access(state)
        finally:
            if args.action == "test": local.down(state,volumes=True)
        if args.action=="test": print("Demo test passed; its containers and volumes were removed",flush=True)


if __name__ == "__main__":
    try: main()
    except (RuntimeError,ValueError,OSError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr)
        sys.exit(1)
