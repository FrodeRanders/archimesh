#!/usr/bin/env python3
"""Build deterministic metadata, Archi and ArchiMesh artifacts; no services required."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
STAMP = "2026-01-15T12:00:00Z"
XSI = "http://www.w3.org/2001/XMLSchema-instance"
ARCHI = "http://www.archimatetool.com/archimate"
ET.register_namespace("xsi", XSI)
ET.register_namespace("archimate", ARCHI)


def canonical(kind, key):
    return f"urn:demo:northstar:{kind}:{key}"


def archi_id(key):
    return "id-" + uuid.uuid5(uuid.NAMESPACE_URL, canonical("archi", key)).hex


def build():
    scenario = json.loads((ROOT / "scenario.json").read_text())
    for group, kind in (("applications", "application"), ("infrastructure", "infrastructure"),
                        ("logicalData", "logical-data"), ("datasets", "dataset"),
                        ("products", "data-product"), ("functions", "function")):
        for item in scenario[group]:
            item["canonicalId"] = canonical(kind, item["id"])
            if group == "datasets":
                item["urn"] = f"urn:li:dataset:(urn:li:dataPlatform:{item['platform']},{item['nativeName']},DEV)"
            elif group in ("applications", "products"):
                entity = "application" if group == "applications" else "dataProduct"
                item["urn"] = f"urn:li:{entity}:northstar-demo-{item['id']}"
    elements, relationships = [], []
    def element(key, type_, name, documentation, canonical_id=None):
        record = {"id": archi_id(key), "archimateType": type_, "name": name,
                  "documentation": documentation + (f"\nDemo canonical reference: {canonical_id}" if canonical_id else "")}
        elements.append(record)
        return record["id"]
    def relation(type_, source, target, name="", **extra):
        key = f"{type_}:{source}:{target}:{name}"
        record = {"id": archi_id(key), "archimateType": type_ + "Relationship",
                  "sourceId": archi_id(source), "targetId": archi_id(target),
                  "name": name, "documentation": "Synthetic Northstar demonstration relationship."}
        # Access type is an ArchiMate semantic property, preserved separately below.
        record.update(extra)
        relationships.append(record)
    for app in scenario["applications"]:
        app["archiElementId"] = element("app:" + app["id"], "ApplicationComponent", app["name"],
                                       app["description"] + " Sourcing: " + app["sourcing"], app["canonicalId"])
    for item in scenario["logicalData"]:
        element("data:" + item["id"], "DataObject", item["name"], item["description"], item["canonicalId"])
    for item in scenario["infrastructure"]:
        element("infra:" + item["id"], "Node", item["name"], item["description"], item["canonicalId"])
    for function in scenario["functions"]:
        element("fn:" + function["id"], "ApplicationFunction", function["name"], function["description"], function["canonicalId"])
        relation("Assignment", "app:oms", "fn:" + function["id"])
    for source, target, label in scenario["functionFlows"]:
        relation("Flow", "fn:" + source, "fn:" + target, label)
    for function, data, access in scenario["functionAccesses"]:
        relation("Access", "fn:" + function, "data:" + data, access, accessType=access)
    for app, data, crud in scenario["accesses"]:
        access = "Read" if crud == "R" else "ReadWrite"
        relation("Access", "app:" + app, "data:" + data, crud, accessType=access)
    for app in scenario["applications"]:
        for infrastructure in app["infrastructure"]:
            # Association says dependency without falsely assigning a software component to a node.
            relation("Association", "app:" + app["id"], "infra:" + infrastructure, "deployment dependency")
    for key, name, function, consumer in (
        ("ordering", "Sales order submission", "capture", "storefront"),
        ("tracking", "Order tracking", "status", "storefront")):
        element("service:" + key, "ApplicationService", name, "Service exposed by the in-house order application.")
        relation("Realization", "fn:" + function, "service:" + key)
        relation("Serving", "service:" + key, "app:" + consumer)
    return scenario, elements, relationships


def package(elements, relationships):
    elements = [{**e, "id":"elem:" + e["id"]} for e in elements]
    relationships = [{**r, "id":"rel:" + r["id"], "sourceId":"elem:" + r["sourceId"],
                      "targetId":"elem:" + r["targetId"]} for r in relationships]
    ops = []
    roots = [("application", "Application"), ("relations", "Relations"),
             ("technology", "Technology & Physical")]
    for key, name in roots:
        ops.append({"type":"CreateFolder", "folder":{"id":"folder:root-" + key,
                    "folderType":key.upper(), "name":name}})
    for element in elements:
        ops.append({"type":"CreateElement", "element":element})
        folder = "technology" if element["archimateType"] == "Node" else "application"
        ops.append({"type":"MoveElementToFolder", "elementId":element["id"], "folderId":"folder:root-" + folder})
    for relationship in relationships:
        record = {k:v for k,v in relationship.items() if k != "accessType"}
        ops.append({"type":"CreateRelationship", "relationship":record})
        if "accessType" in relationship:
            ops.append({"type":"SetProperty", "targetId":record["id"], "key":"demo.accessType", "value":relationship["accessType"]})
        ops.append({"type":"MoveRelationshipToFolder", "relationshipId":record["id"], "folderId":"folder:root-relations"})
    for i, op in enumerate(ops, 1):
        op["causal"] = {"lamport":i, "clientId":"northstar-demo"}
    model = "northstar-order-management-demo-v1"
    snapshot = {"format":"archimate-materialized-v1", "modelId":model,
                "headRevision":len(ops), "elements":elements, "relationships":relationships}
    for key in ("folders", "views", "elementFolderMembers", "relationshipFolderMembers", "viewFolderMembers",
                "viewObjects", "viewObjectChildMembers", "connections"):
        snapshot[key] = []
    snapshot["folders"] = [op["folder"] for op in ops if op["type"] == "CreateFolder"]
    snapshot["elementFolderMembers"] = [{"folderId":op["folderId"], "elementId":op["elementId"]}
                                         for op in ops if op["type"] == "MoveElementToFolder"]
    snapshot["relationshipFolderMembers"] = [{"folderId":op["folderId"], "relationshipId":op["relationshipId"]}
                                              for op in ops if op["type"] == "MoveRelationshipToFolder"]
    return {"format":"archi-model-export-v1", "exportedAt":STAMP,
            "model":{"modelId":model,"modelName":"Northstar Trade — order to cash", "headRevision":len(ops)},
            "snapshot":snapshot, "opBatches":[{"modelId":model, "opBatchId":archi_id("initial-batch"),
            "baseRevision":0, "assignedRevisionRange":{"from":1,"to":len(ops)}, "timestamp":STAMP, "ops":ops}], "tags":[]}


def archi(elements, relationships):
    root = ET.Element(f"{{{ARCHI}}}model", {"name":"Northstar Trade — order to cash",
                      "id":archi_id("model"), "version":"5.0.0"})
    folders = {}
    for key, name in (("business","Business"), ("application","Application"),
                      ("technology","Technology & Physical"), ("motivation","Motivation"),
                      ("strategy","Strategy"), ("implementation_migration","Implementation & Migration"),
                      ("other","Other"), ("relations","Relations"), ("diagrams","Views")):
        folders[key] = ET.SubElement(root,"folder", {"name":name,"id":archi_id("folder:" + key),"type":key})
    for item in elements + relationships:
        is_relationship = "sourceId" in item
        folder = "relations" if is_relationship else "technology" if item["archimateType"] == "Node" else "application"
        attrs = {f"{{{XSI}}}type":"archimate:" + item["archimateType"], "id":item["id"], "name":item["name"]}
        if is_relationship:
            attrs.update(source=item["sourceId"], target=item["targetId"])
            if "accessType" in item:
                attrs["accessType"] = {"Read":"1", "Write":"2", "ReadWrite":"3"}[item["accessType"]]
        child = ET.SubElement(folders[folder],"element",attrs)
        ET.SubElement(child,"documentation").text = item["documentation"]
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def mermaid(elements, relationships):
    # Projection of the semantic graph: view coordinates are deliberately unnecessary.
    ids = {e["id"]:"n" + str(i) for i,e in enumerate(elements)}
    lines = ["flowchart LR"]
    for e in elements:
        lines.append(f'  {ids[e["id"]]}["{e["name"]}<br/>{e["archimateType"]}"]')
    for r in relationships:
        label = r["archimateType"].removesuffix("Relationship") + (": " + r["name"] if r["name"] else "")
        lines.append(f'  {ids[r["sourceId"]]} -->|"{label}"| {ids[r["targetId"]]}')
    return "\n".join(lines) + "\n"


def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    scenario, elements, relationships = build()
    for filename, data in (("metadata.json", scenario), ("archimesh-model.json", package(elements, relationships))):
        (output / filename).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    (output / "order-management.archimate").write_bytes(archi(elements, relationships))
    (output / "semantic-graph.mmd").write_text(mermaid(elements, relationships))
    from samples import write_samples
    write_samples(output)
    files = [output / name for name in ("metadata.json","archimesh-model.json","order-management.archimate","semantic-graph.mmd")]
    files.extend(sorted((output / "data").glob("*.csv")))
    checksums = {str(p.relative_to(output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    (output / "sha256.json").write_text(json.dumps(checksums, indent=2) + "\n")
    return {"elements":len(elements),"relationships":len(relationships),"datasets":len(scenario["datasets"]),
            "products":len(scenario["products"]),"applications":len(scenario["applications"])}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "generated")
    print(json.dumps(generate(parser.parse_args().output), indent=2))
