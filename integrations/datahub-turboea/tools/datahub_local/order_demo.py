"""Synthetic demo adapter; deliberately separate from the production bridge runtime.

The caller supplies only its locally generated DataHub state and Turbo EA API.
This module has no dependency on ArchiMesh, Java or a model repository.
"""
import hashlib
import json
import os
from pathlib import Path

from .probe import Api, require, unwrap, wait_for


def save(path, value):
    temporary = Path(str(path) + ".tmp")
    with open(temporary,"w", opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def turbo_seed(api, fixture, journal_path):
    """Create once, record native IDs; an uncertain POST always requires review."""
    digest = hashlib.sha256(json.dumps(fixture, sort_keys=True).encode()).hexdigest()
    journal = json.loads(journal_path.read_text()) if journal_path.exists() else {
        "fixtureSha256":digest, "bindings":{}, "pending":None}
    require(journal["fixtureSha256"] == digest, "unchanged demo fixture for saved journal")
    require(not journal["pending"], "previous POST has an unknown outcome; inspect journal and remote state before proceeding")
    types = {t["key"] for t in api("GET", "/api/v1/metamodel/types")}
    require({"Application", "DataObject", "ITComponent"}.issubset(types), "Turbo EA demo card profile")
    relation_types = {t["key"]:t for t in api("GET", "/api/v1/metamodel/relation-types")}
    for key, source, target in (("relAppToDataObj", "Application", "DataObject"),
                                ("relAppToITC", "Application", "ITComponent")):
        require(key in relation_types and relation_types[key]["source_type_key"] == source and
                relation_types[key]["target_type_key"] == target, "Turbo EA demo relation profile " + key)
    def create(key, path, payload):
        if key in journal["bindings"]:
            return journal["bindings"][key]
        journal["pending"] = {"key":key,"path":path,"payload":payload}
        save(journal_path, journal)
        record = api("POST", path, payload)  # Intentionally never retried.
        require(bool(record.get("id")), "native ID returned by Turbo EA")
        journal["bindings"][key] = record["id"]
        journal["pending"] = None
        save(journal_path, journal)
        return record["id"]
    for group, type_ in (("applications", "Application"), ("logicalData", "DataObject"),
                         ("infrastructure", "ITComponent")):
        for item in fixture[group]:
            description = item["description"] + "\nSynthetic Northstar demo."
            if group == "applications":
                description += " Sourcing: " + item["sourcing"]
            if group == "logicalData":
                description += "\nRelated physical datasets (distinct identities):\n" + "\n".join(
                    d["urn"] for d in fixture["datasets"] if d["logicalData"] == item["id"])
            payload = {"type":type_,"name":item["name"],"description":description,
                       "external_id":item["canonicalId"],"attributes":{},"strict_attributes":True}
            create(group + ":" + item["id"], "/api/v1/cards", payload)
    for app, logical, crud in fixture["accesses"]:
        create(f"access:{app}:{logical}", "/api/v1/relations", {
            "type":"relAppToDataObj", "source_id":journal["bindings"]["applications:" + app],
            "target_id":journal["bindings"]["logicalData:" + logical],
            "attributes":{"crudCreate":"C" in crud,"crudRead":"R" in crud,
                          "crudUpdate":"U" in crud,"crudDelete":"D" in crud},
            "description":"Explicit demo architectural access; not technical dataset lineage."})
    for app in fixture["applications"]:
        for infrastructure in app["infrastructure"]:
            create(f"infra:{app['id']}:{infrastructure}", "/api/v1/relations", {
                "type":"relAppToITC", "source_id":journal["bindings"]["applications:" + app["id"]],
                "target_id":journal["bindings"]["infrastructure:" + infrastructure],
                "description":"Illustrative on-premises deployment dependency (uses)."})
    return journal


def aspects(fixture, turbo_apps, project):
    """Build expected DataHub aspects; native Application mapping is demo-specific."""
    records = []
    domain = "urn:li:domain:northstar-demo-trade"
    records.append((domain,"domainProperties",{"name":"Northstar Trade (demo)",
                    "description":fixture["description"]}))
    for team in ("order-team", "warehouse-team", "sales-data-team"):
        records.append(("urn:li:corpuser:northstar-demo-" + team,"corpUserInfo",
                        {"active":True,"displayName":"Northstar demo " + team}))
    for app in fixture["applications"]:
        source = turbo_apps[app["id"]]
        records.append((app["urn"],"applicationProperties",{
            "name":source["name"], "description":source["description"], "customProperties":{
                "demo.canonicalId":app["canonicalId"], "demo.source.system":"turboea",
                "demo.source.instance":project, "demo.source.nativeId":source["id"],
                "demo.mappingProfile":"enterprise-application-as-business-asset-group-v1"}}))
        records.append((app["urn"],"status",{"removed":False}))
    apps = {a["id"]:a for a in fixture["applications"]}
    datasets = {d["id"]:d for d in fixture["datasets"]}
    for dataset in fixture["datasets"]:
        urn = dataset["urn"]
        records.append((urn,"datasetProperties",{"name":dataset["name"],"description":dataset["description"],
                        "customProperties":{"demo.canonicalId":dataset["canonicalId"],
                                            "demo.source.system":"datahub","demo.synthetic":"true"}}))
        fields = []
        for name, type_ in dataset["fields"]:
            data_type = {"string":"StringType","int":"NumberType","decimal":"NumberType","timestamp":"TimeType"}[type_]
            fields.append({"fieldPath":name,"nativeDataType":type_,
                           "type":{"type":{"com.linkedin.schema." + data_type:{}}}})
        records.append((urn,"schemaMetadata",{"schemaName":dataset["nativeName"],
                        "platform":"urn:li:dataPlatform:" + dataset["platform"], "version":0,
                        "hash":hashlib.sha256(json.dumps(dataset["fields"]).encode()).hexdigest(),
                        "platformSchema":{"com.linkedin.schema.OtherSchema":{"rawSchema":json.dumps(dataset["fields"])}},
                        "fields":fields}))
        records.append((urn,"applications",{"applications":[apps[a]["urn"] for a in dataset["applications"]]}))
        records.append((urn,"upstreamLineage",{"upstreams":[{"dataset":datasets[u]["urn"],"type":"TRANSFORMED"}
                                                           for u in dataset["upstreams"]]}))
        records.append((urn,"domains",{"domains":[domain]}))
        records.append((urn,"status",{"removed":False}))
    for product in fixture["products"]:
        records.append((product["urn"],"dataProductProperties",{"name":product["name"],
                        "description":product["description"] + "\nDemo canonical reference: " + product["canonicalId"],
                        "assets":[{"destinationUrn":datasets[d]["urn"]} for d in product["datasets"]]}))
        ownership = {"owners":[{"owner":"urn:li:corpuser:northstar-demo-" + product["team"],"type":"TECHNICAL_OWNER"}]}
        records.extend([(product["urn"],"ownership",ownership), (product["urn"],"domains",{"domains":[domain]}),
                        (product["urn"],"status",{"removed":False})])
        for dataset in product["datasets"]:
            records.append((datasets[dataset]["urn"],"ownership",ownership))
    return records


def datahub_seed(state, records):
    admin = Api(state, "datahub")
    wait_for(lambda:admin.graphql("{ __typename }"), lambda r:r[0] == 200 and r[1].get("data"), "DataHub demo readiness", 300)
    for urn, aspect, value in records:
        status, response = admin.upsert(urn, aspect, value)
        if status >= 300:
            save(Path(state["directory"]) / "order-demo-ingest-error.json", response)
        require(status < 300, f"demo aspect ingest {aspect} for {urn}: HTTP {status}")


def contains(actual, expected):
    """Allow server-added default fields while checking every submitted value."""
    if isinstance(expected,dict):
        return isinstance(actual,dict) and all(k in actual and contains(actual[k],v) for k,v in expected.items())
    if isinstance(expected,list):
        return isinstance(actual,list) and len(actual) == len(expected) and all(contains(a,e) for a,e in zip(actual,expected))
    return actual == expected


def datahub_verify(state, records):
    admin = Api(state,"datahub")
    for urn, aspect, expected in records:
        def matches(response):
            actual = unwrap(response[1]) if response[0] == 200 else {}
            return response[0] == 200 and contains(actual,expected)
        wait_for(lambda:admin.aspect(urn, aspect), matches, "persisted demo aspect " + aspect)
    return {"entities":len({r[0] for r in records}),"verifiedAspects":len(records)}


def turbo_verify(api, fixture, journal):
    apps = {}
    for group, type_ in (("applications","Application"),("logicalData","DataObject"),("infrastructure","ITComponent")):
        for item in fixture[group]:
            record = api("GET", "/api/v1/cards/" + journal["bindings"][group + ":" + item["id"]])
            require(record["type"] == type_ and record["name"] == item["name"] and
                    record["external_id"] == item["canonicalId"], "persisted Turbo EA demo card")
            if group == "applications": apps[item["id"]] = record
    for app in fixture["applications"]:
        app_id = journal["bindings"]["applications:" + app["id"]]
        relations = api("GET", "/api/v1/relations?card_id=" + app_id)
        actual = {r["id"]:r for r in relations}
        for a, logical, crud in fixture["accesses"]:
            if a != app["id"]: continue
            relation = actual[journal["bindings"][f"access:{a}:{logical}"]]
            require(relation["type"] == "relAppToDataObj" and relation["source_id"] == app_id and
                    relation["target_id"] == journal["bindings"]["logicalData:" + logical] and
                    relation["attributes"] == {"crudCreate":"C" in crud,"crudRead":"R" in crud,
                                               "crudUpdate":"U" in crud,"crudDelete":"D" in crud}, "persisted demo CRUD relation")
        for infrastructure in app["infrastructure"]:
            relation = actual[journal["bindings"][f"infra:{app['id']}:{infrastructure}"]]
            require(relation["type"] == "relAppToITC" and relation["source_id"] == app_id and
                    relation["target_id"] == journal["bindings"]["infrastructure:" + infrastructure], "persisted demo deployment relation")
    return apps
