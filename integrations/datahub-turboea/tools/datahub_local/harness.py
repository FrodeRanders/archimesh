"""Compose lifecycle; uses stdlib only and never touches another project's data."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "compose.json"
RUNS = ROOT / ".run/datahub"
VERSION = "v1.8.0"


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(text)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def initialize(name, ephemeral=False):
    if not re.fullmatch(r"dev|it-[0-9a-f]{12}", name):
        raise ValueError("Invalid local environment name")
    directory = RUNS / name
    if directory.exists():
        return load(directory)
    directory.mkdir(parents=True, mode=0o700)
    env = {key: secrets.token_hex(32) for key in (
        "DATAHUB_DB_PASSWORD", "DATAHUB_SYSTEM_CLIENT_SECRET",
        "DATAHUB_TOKEN_SERVICE_SIGNING_KEY", "DATAHUB_TOKEN_SERVICE_SALT",
        "DATAHUB_FRONTEND_SECRET")}
    gms, frontend = (free_port(), free_port()) if ephemeral else (18080, 19002)
    while frontend == gms:
        frontend = free_port()
    env.update(DATAHUB_GMS_PORT=str(gms), DATAHUB_FRONTEND_PORT=str(frontend),
               DATAHUB_SECRETS_DIR=str(directory.resolve()))
    private_write(directory / ".env", "".join(f"{k}={v}\n" for k, v in env.items()))
    private_write(directory / "user.props", "datahub=" + secrets.token_hex(24) + "\n")
    private_write(directory / "state.json", json.dumps({
        "project": "datahub-turboea-" + name, "version": VERSION,
        "ephemeral": ephemeral, "directory": str(directory.resolve())}, indent=2) + "\n")
    return load(directory)


def load(directory):
    directory = directory.resolve()
    if directory.parent != RUNS.resolve():
        raise ValueError("Environment must be inside .run/datahub")
    state = json.loads((directory / "state.json").read_text())
    if state != {"project": "datahub-turboea-" + directory.name, "version": VERSION,
                 "ephemeral": directory.name.startswith("it-"), "directory": str(directory)}:
        raise ValueError("Invalid local environment state")
    if not re.fullmatch(r"dev|it-[0-9a-f]{12}", directory.name):
        raise ValueError("Invalid project scope")
    state["env"] = dict(line.split("=", 1) for line in (directory / ".env").read_text().splitlines())
    state["private_values"] = [(directory / "user.props").read_text().strip().split("=", 1)[1]]
    return state


def redact(text, state):
    for value in state.get("private_values", []):
        text = text.replace(value, "<redacted>")
    for key, value in state["env"].items():
        if key.endswith(("SECRET", "PASSWORD", "KEY", "SALT")):
            text = text.replace(value, "<redacted>")
    text = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "<token>", text)
    return text


def compose(state, *args, check=True, capture=False):
    command = ["docker", "compose", "--project-name", state["project"],
               "--env-file", str(Path(state["directory"]) / ".env"), "--file", str(COMPOSE), *args]
    # Generated values override ambient Compose variables. Disable implicit override files/profiles.
    env = {k: v for k, v in os.environ.items() if not k.startswith(("COMPOSE_", "DATAHUB_"))}
    env.update(state["env"])
    output = []
    with subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True) as process:
        timer = threading.Timer(1200, process.kill)
        timer.start()
        try:
            with (Path(state["directory"]) / "compose.log").open("a") as stream:
                for line in process.stdout:
                    line = redact(line, state)
                    if capture:
                        output.append(line)
                    else:
                        stream.write(line)
                        stream.flush()
                code = process.wait()
        finally:
            timer.cancel()
    if check and code:
        raise RuntimeError(f"Compose {args[0]} failed; inspect {state['directory']}/compose.log")
    return code, "".join(output)


def session_token(state, actor, *, lifetime_seconds=3600):
    """Synthetic SESSION bootstrap only; never use the agency token signing key here."""
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    if not isinstance(lifetime_seconds,int) or not 1<=lifetime_seconds<=86400: raise ValueError("Invalid synthetic session lifetime")
    payload = {"sub": actor, "actorId": actor, "actorType": "USER", "type": "SESSION",
               "version": "1", "exp": int(time.time()) + lifetime_seconds, "jti": str(uuid.uuid4())}
    body = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode(payload)
    signature = hmac.new(state["env"]["DATAHUB_TOKEN_SERVICE_SIGNING_KEY"].encode(), body, hashlib.sha256).digest()
    return (body + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()


def save_inventory(state):
    _, config = compose(state, "config", "--format", "json", capture=True)
    doc = json.loads(config)
    images = {k: v["image"] for k, v in doc["services"].items()}
    result = {"baseline": VERSION, "project": state["project"], "images": images}
    inventory = subprocess.run(["docker", "image", "inspect", *sorted(set(images.values()))],
                               capture_output=True, text=True, timeout=30)
    if inventory.returncode == 0:
        result["resolvedImages"] = [{"id": i["Id"], "digests": i.get("RepoDigests", []),
                                     "architecture": i["Architecture"]} for i in json.loads(inventory.stdout)]
    (Path(state["directory"]) / "images.json").write_text(json.dumps(result, indent=2) + "\n")


def up(state):
    print(f"Starting {state['project']} ({VERSION}); logs in {state['directory']}", flush=True)
    compose(state, "up", "-d", "--wait", "--wait-timeout", "900")
    save_inventory(state)
    print("GMS: http://127.0.0.1:" + state["env"]["DATAHUB_GMS_PORT"], flush=True)
    print("Frontend: http://127.0.0.1:" + state["env"]["DATAHUB_FRONTEND_PORT"], flush=True)


def down(state, volumes=False):
    compose(state, "down", *(["--volumes"] if volumes else []))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["init", "up", "status", "probe", "down", "test"])
    parser.add_argument("--volumes", action="store_true", help="Remove only this environment's volumes on down")
    args = parser.parse_args()
    if args.volumes and args.action != "down":
        parser.error("--volumes applies only to down")
    state = None
    try:
        if args.action == "test":
            state = initialize("it-" + uuid.uuid4().hex[:12], ephemeral=True)
            from .probe import run
            try:
                up(state)
                run(state)
                print("Restarting GMS/frontend; retaining this test's volumes", flush=True)
                compose(state, "restart", "gms", "frontend")
                run(state, after_restart=True)
            finally:
                compose(state, "logs", "--no-color", check=False)
                down(state, volumes=True)
            print("DataHub D0 probes and cleanup passed", flush=True)
            return 0
        state = initialize("dev") if args.action == "init" else load(RUNS / "dev")
        if args.action == "init":
            print(f"Initialized {state['project']}; secrets in {state['directory']} (mode 0600)")
        elif args.action == "up": up(state)
        elif args.action == "down": down(state, args.volumes)
        elif args.action == "status":
            print(compose(state, "ps", capture=True)[1])
        elif args.action == "probe":
            from .probe import run
            run(state)
        return 0
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
        print(redact(str(error), state) if state else str(error), flush=True)
        return 1
