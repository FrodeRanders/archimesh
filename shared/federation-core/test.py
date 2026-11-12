#!/usr/bin/env python3
"""Compile/test the dependency-free contract library with Java 21; no Maven or repositories."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent
sources = sorted(str(p) for kind in ("main", "test") for p in (root / "src" / kind / "java").rglob("*.java"))
with tempfile.TemporaryDirectory(prefix="federation-core-") as output:
    subprocess.run(["javac", "--release", "21", "-d", output, *sources], check=True)
    subprocess.run(["java", "-cp", output, "org.gautelis.federation.ContractChecks"], check=True)
