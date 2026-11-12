#!/usr/bin/env python3
"""Build the versioned contract JAR independently of any application or Maven."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent
target = root / "target"
target.mkdir(exist_ok=True)
artifact = target / "federation-core-0.2.0.jar"
with tempfile.TemporaryDirectory(prefix="federation-core-") as classes:
    sources = sorted(str(p) for p in (root / "src/main/java").rglob("*.java"))
    subprocess.run(["javac", "--release", "21", "-d", classes, *sources], check=True)
    subprocess.run(["jar", "--create", "--file", str(artifact), "-C", classes, "."], check=True)
print(artifact)
