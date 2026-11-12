#!/usr/bin/env python3
"""Optional Archi 5.8 load/validate and view synthesis, using an installed model SDK."""
import argparse
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET

from generate import build, generate

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugins",type=Path,required=True,help="Archi's Eclipse/plugins directory")
    parser.add_argument("--artifacts",type=Path,default=HERE / "generated")
    args = parser.parse_args()
    model_dirs = sorted(args.plugins.glob("com.archimatetool.model_*"))
    if len(model_dirs) != 1 or not model_dirs[0].is_dir():
        parser.error("Expected one unpacked Archi model bundle")
    model = model_dirs[0]
    bundle = model / "com.archimatetool.model.jar"
    # Use Archi's own ArchiMate relationship matrix to check allowed endpoint types.
    keys = {e.attrib["relationship"]:e.attrib["char"] for e in ET.parse(model / "model/relationships-keys.xml").getroot()}
    allowed = {(s.attrib["concept"],t.attrib["concept"]):t.attrib["relations"]
               for s in ET.parse(model / "model/relationships.xml").getroot() for t in s}
    _, elements, relationships = build()
    types = {e["id"]:e["archimateType"] for e in elements}
    for relation in relationships:
        endpoints = (types[relation["sourceId"]],types[relation["targetId"]])
        if keys[relation["archimateType"]] not in allowed.get(endpoints,""):
            raise ValueError("Invalid ArchiMate relation: " + str((relation["archimateType"],endpoints)))
    generate(args.artifacts)
    jars = [bundle]
    for prefix in ("org.eclipse.emf.common_","org.eclipse.emf.ecore_","org.eclipse.emf.ecore.xmi_",
                   "org.eclipse.core.runtime_","org.eclipse.equinox.common_","org.eclipse.osgi_"):
        matches = sorted(args.plugins.glob(prefix + "*.jar"))
        if len(matches) != 1: parser.error("Expected one bundle for " + prefix)
        jars.extend(matches)
    subprocess.run(["java","--class-path",os.pathsep.join(map(str,jars)),str(HERE / "ValidateArchi.java"),
                    str(args.artifacts / "order-management.archimate"),
                    str(args.artifacts / "order-management-synthesized.archimate")],check=True)
    print("ArchiMate endpoint matrix passed: " + str(len(relationships)) + " relationships")


if __name__ == "__main__": main()
