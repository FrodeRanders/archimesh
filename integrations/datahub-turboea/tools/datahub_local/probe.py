"""Synthetic D0 API observations. No agency URL/credential input is accepted."""
import hashlib
import http.client
import json
from pathlib import Path
import time
from urllib.parse import quote

from .harness import VERSION, compose, redact, session_token

DATASET = "urn:li:dataset:(urn:li:dataPlatform:postgres,bridge_d0.customer,DEV)"
HIDDEN = "urn:li:dataset:(urn:li:dataPlatform:postgres,bridge_d0.restricted,DEV)"
PRODUCT = "urn:li:dataProduct:bridge-d0-customer"
APPLICATION = "urn:li:application:bridge-d0-customer-app"


class Api:
    def __init__(self, state, actor=None):
        self.port = int(state["env"]["DATAHUB_GMS_PORT"])
        self.token = session_token(state, actor) if actor else None

    def request(self, method, path, data=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        headers = {"Accept": "application/json", "X-RestLi-Protocol-Version": "2.0.0"}
        if self.token: headers["Authorization"] = "Bearer " + self.token
        body = None
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            raw = response.read(16_777_217)
            if len(raw) > 16_777_216: raise RuntimeError("API response exceeds probe limit")
            try: value = json.loads(raw)
            except ValueError: value = {"nonJsonBytes": len(raw)}
            return response.status, value
        finally:
            connection.close()

    def upsert(self, urn, aspect, value, async_=False, change_type="UPSERT"):
        return self.request("POST", "/aspects?action=ingestProposal", {
            "proposal": {"entityType": urn.split(":")[2], "entityUrn": urn,
                         "changeType": change_type, "aspectName": aspect,
                         "aspect": {"value": json.dumps(value), "contentType": "application/json"}},
            "async": str(async_).lower()})

    def aspect(self, urn, aspect):
        return self.request("GET", "/aspects/" + quote(urn, safe="") +
                            "?aspect=" + quote(aspect) + "&version=0")

    def graphql(self, query, variables=None):
        return self.request("POST", "/api/graphql", {"query": query, "variables": variables or {}})


def unwrap(value):
    aspect = value.get("aspect", value)
    if "value" in aspect and isinstance(aspect["value"], str):
        return json.loads(aspect["value"])
    # Rest.li union envelopes carry the fully-qualified aspect name.
    if len(aspect) == 1 and next(iter(aspect)).startswith("com.linkedin."):
        return next(iter(aspect.values()))
    return aspect


def wait_for(operation, predicate, description, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = operation()
            if predicate(result): return result
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("Timed out waiting for " + description)


def frontend_auth(state):
    port = int(state["env"]["DATAHUB_FRONTEND_PORT"])
    def request(path, body, cookie=None):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
        headers = {"Content-Type": "application/json"}
        if cookie: headers["Cookie"] = cookie
        try:
            connection.request("POST", path, json.dumps(body), headers)
            response = connection.getresponse()
            raw = response.read(1_048_576)
            cookies = [v.split(";", 1)[0] for k, v in response.getheaders() if k.lower() == "set-cookie"]
            return response.status, raw, "; ".join(cookies)
        finally: connection.close()
    query = {"query": "{ __typename }"}
    anonymous, _, _ = wait_for(lambda: request("/api/graphql", query),
                               lambda r: r[0] in (401, 403, 303), "frontend auth readiness")
    password = (Path(state["directory"]) / "user.props").read_text().strip().split("=", 1)[1]
    status, _, cookie = request("/logIn", {"username": "datahub", "password": password})
    require(status == 200 and cookie, "generated frontend login")
    authenticated, raw, _ = request("/api/graphql", query, cookie)
    require(authenticated == 200 and json.loads(raw).get("data"), "frontend authenticated GraphQL proxy")
    bad_status, _, _ = request("/logIn", {"username": "datahub", "password": "wrong-synthetic-password"})
    require(bad_status in (400, 401, 403), "frontend rejects incorrect password")
    return {"anonymousStatus": anonymous, "loginStatus": status,
            "authenticatedQueryStatus": authenticated, "badPasswordStatus": bad_status}


def require(condition, label):
    if not condition: raise RuntimeError("Probe failed: " + label)


def seed(admin):
    for user in ("bridge-d0-reader", "bridge-d0-writer", "bridge-d0-denied"):
        status, _ = admin.upsert("urn:li:corpuser:" + user, "corpUserInfo", {
            "active": True, "displayName": user})
        require(status < 300, "create synthetic principal")
    # The upstream all-users view policy would otherwise conceal permission mistakes.
    status, raw = admin.aspect("urn:li:dataHubPolicy:view-entity-page-all", "dataHubPolicyInfo")
    require(status == 200, "read default visibility policy")
    policy = unwrap(raw)
    policy["state"] = "INACTIVE"
    require(admin.upsert("urn:li:dataHubPolicy:view-entity-page-all", "dataHubPolicyInfo", policy)[0] < 300,
            "disable all-users visibility in synthetic environment")
    for actor, privileges in (
        ("reader", ["VIEW_ENTITY_PAGE", "GET_ENTITY_PRIVILEGE", "SEARCH_PRIVILEGE"]),
        ("writer", ["VIEW_ENTITY_PAGE", "GET_ENTITY_PRIVILEGE", "SEARCH_PRIVILEGE", "EDIT_ENTITY"])):
        policy = {"displayName": "Synthetic D0 " + actor, "description": "Disposable integration fixture",
                  "state": "ACTIVE", "type": "METADATA", "editable": True,
                  "actors": {"users": ["urn:li:corpuser:bridge-d0-" + actor]},
                  "resources": {"filter": {"criteria": [{"field": "URN", "condition": "EQUALS",
                                                            "values": [DATASET, PRODUCT]}]}},
                  "privileges": privileges}
        require(admin.upsert("urn:li:dataHubPolicy:bridge-d0-" + actor,
                             "dataHubPolicyInfo", policy)[0] < 300, "create scoped policy")
    for urn, name in ((DATASET, "Synthetic customer dataset"), (HIDDEN, "Restricted synthetic dataset")):
        require(admin.upsert(urn, "datasetProperties", {"name": name,
                            "description": "Ingested synthetic description", "customProperties": {
                                "d0_keep": "retained", "d0_drop": "initial"}})[0] < 300,
                "seed dataset properties")
        require(admin.upsert(urn, "status", {"removed": False})[0] < 300, "seed dataset lifecycle")
    require(admin.upsert(DATASET, "editableDatasetProperties", {"description": "Curated synthetic description"})[0] < 300,
            "seed curated description")
    require(admin.upsert(DATASET, "ownership", {"owners": [{"owner": "urn:li:corpuser:bridge-d0-writer",
                         "type": "TECHNICAL_OWNER"}]})[0] < 300, "seed explicit owner reference")
    require(admin.upsert(PRODUCT, "dataProductProperties", {"name": "Synthetic customer product",
                         "description": "A product and its dataset have distinct identities",
                         "assets": [{"destinationUrn": DATASET}]})[0] < 300, "seed OSS data product")
    require(admin.upsert(PRODUCT, "status", {"removed": False})[0] < 300, "seed product lifecycle")


def application_checks(state, after_restart):
    """OSS storage feasibility using a simulated Turbo EA snapshot, not a running bridge."""
    admin, reader, dataset_writer = Api(state, "datahub"), Api(state, "bridge-d0-reader"), Api(state, "bridge-d0-writer")
    writer = Api(state, "bridge-d0-app-writer")
    if not after_restart:
        require(admin.upsert("urn:li:corpuser:bridge-d0-app-writer", "corpUserInfo", {
            "active": True, "displayName": "Synthetic application projection writer"})[0] < 300, "create application writer")
        policy = {"displayName": "Synthetic application writer", "description": "Disposable scoped application projection",
                  "type": "METADATA", "state": "ACTIVE", "actors": {"users": ["urn:li:corpuser:bridge-d0-app-writer"]},
                  "resources": {"filter": {"criteria": [{"field": "URN", "condition": "EQUALS", "values": [APPLICATION]}]}},
                  "privileges": ["VIEW_ENTITY_PAGE", "GET_ENTITY_PRIVILEGE", "EDIT_ENTITY"]}
        require(admin.upsert("urn:li:dataHubPolicy:bridge-d0-app-writer", "dataHubPolicyInfo", policy)[0] < 300, "grant application-only write scope")
        _, raw = admin.aspect("urn:li:dataHubPolicy:bridge-d0-reader", "dataHubPolicyInfo")
        policy = unwrap(raw); policy["resources"]["filter"]["criteria"][0]["values"].append(APPLICATION)
        require(admin.upsert("urn:li:dataHubPolicy:bridge-d0-reader", "dataHubPolicyInfo", policy)[0] < 300, "grant application read scope")
        require(admin.upsert(APPLICATION, "applicationProperties", {"name": "Synthetic enterprise application",
                    "description": "Seeded reference", "customProperties": {"local.note": "preserve"}})[0] < 300,
                "seed OSS application properties")
        require(admin.upsert(APPLICATION, "status", {"removed": False})[0] < 300, "seed application lifecycle")
        require(admin.upsert(DATASET, "applications", {"applications": [APPLICATION]})[0] < 300,
                "seed explicit dataset-to-application association")
    _, current = wait_for(lambda: writer.aspect(APPLICATION, "applicationProperties"),
                          lambda r: r[0] == 200, "application writer policy propagation")
    if not after_restart:
        properties = unwrap(current)
        # Synthetic source evidence: the production bridge must fetch/verify Turbo EA and persist registry proof.
        properties.update(name="Updated synthetic enterprise application", description="Simulated Turbo EA authoritative description")
        properties.setdefault("customProperties", {}).update({"bridge.source.system": "turboea",
            "bridge.source.instance": "synthetic-turboea", "bridge.source.nativeId": "synthetic-card-application",
            "bridge.source.canonicalId": "urn:agency:application:synthetic"})
        wait_for(lambda: writer.upsert(APPLICATION, "applicationProperties", properties),
                 lambda r: r[0] < 300, "application mutation policy propagation")
    _, app = wait_for(lambda: reader.aspect(APPLICATION, "applicationProperties"),
                      lambda r: r[0] == 200 and unwrap(r[1]).get("name") == "Updated synthetic enterprise application",
                      "application projection readback")
    properties = unwrap(app)
    require(properties.get("customProperties", {}).get("local.note") == "preserve", "application update preserves unowned custom property")
    require(properties["customProperties"].get("bridge.source.nativeId") == "synthetic-card-application", "source native ID retained separately")
    require(properties["customProperties"].get("bridge.source.canonicalId") == "urn:agency:application:synthetic", "application source reference retained exactly")
    status, _ = writer.upsert(DATASET, "datasetProperties", {"description": "FORBIDDEN APPLICATION WRITER"})
    require(status == 403, "application writer cannot edit a dataset")
    dataset_write_status, _ = dataset_writer.upsert(APPLICATION, "applicationProperties", {"name": "FORBIDDEN DATASET WRITER"})
    require(dataset_write_status == 403, "dataset writer cannot edit an application")
    _, association = reader.aspect(DATASET, "applications")
    require(unwrap(association).get("applications") == [APPLICATION], "exact dataset application association retained")
    return {"applicationUrn": APPLICATION, "simulatedTurboEaSource": True, "properties": properties,
            "datasetAssociation": unwrap(association), "applicationWriterDatasetMutationStatus": status,
            "datasetWriterApplicationMutationStatus": dataset_write_status}


def run(state, after_restart=False):
    directory = Path(state["directory"])
    report_path = directory / ("restart-report.json" if after_restart else "report.json")
    report = {"baseline": VERSION, "project": state["project"], "afterRestart": after_restart,
              "passed": False, "checks": {}, "sourceReferences": {"dataset": DATASET, "product": PRODUCT}}
    admin = Api(state, "datahub")
    try:
        wait_for(lambda: admin.request("GET", "/health"), lambda r: r[0] == 200, "GMS health", 300)
        wait_for(lambda: admin.aspect("urn:li:corpuser:datahub", "corpUserInfo"),
                 lambda r: r[0] == 200, "authenticated root bootstrap", 180)
        report["checks"]["anonymousReadStatus"] = Api(state).aspect(DATASET, "datasetProperties")[0]
        require(report["checks"]["anonymousReadStatus"] == 401, "anonymous metadata request denied")
        invalid = Api(state); invalid.token = "invalid.synthetic.token"
        report["checks"]["invalidTokenStatus"] = invalid.aspect(DATASET, "datasetProperties")[0]
        require(report["checks"]["invalidTokenStatus"] == 401, "invalid bearer denied")
        if not after_restart: seed(admin)
        print("Checking source permissions and aspects", flush=True)
        reader, writer = Api(state, "bridge-d0-reader"), Api(state, "bridge-d0-writer")
        wait_for(lambda: reader.aspect(DATASET, "datasetProperties"), lambda r: r[0] == 200,
                 "reader policy propagation")
        denied_status = wait_for(lambda: Api(state, "bridge-d0-denied").aspect(DATASET, "datasetProperties"),
                                 lambda r: r[0] in (403, 404), "denied-user policy propagation")[0]
        report["checks"]["deniedReaderStatus"] = denied_status
        status, body = reader.aspect(HIDDEN, "datasetProperties")
        report["checks"]["restrictedAssetStatus"] = status
        require(status in (403, 404), "reader cannot read unapproved dataset")
        mutation_status, _ = reader.upsert(DATASET, "datasetProperties", {"description": "FORBIDDEN"})
        report["checks"]["readerWriteStatus"] = mutation_status
        require(mutation_status == 403, "read-only principal cannot mutate")
        mutation_status, _ = writer.upsert(HIDDEN, "datasetProperties", {"description": "FORBIDDEN"})
        report["checks"]["writerOutsideScopeStatus"] = mutation_status
        require(mutation_status == 403, "scoped writer cannot mutate restricted dataset")
        status, properties = wait_for(lambda: reader.aspect(PRODUCT, "dataProductProperties"),
                                      lambda r: r[0] == 200, "data-product reader policy propagation")
        require(status == 200 and unwrap(properties).get("assets", [{}])[0].get("destinationUrn") == DATASET,
                "OSS product retains exact dataset reference")
        report["checks"]["productMembership"] = unwrap(properties)
        status, curated = reader.aspect(DATASET, "editableDatasetProperties")
        require(status == 200 and unwrap(curated).get("description") == "Curated synthetic description",
                "curated aspect survives independently")
        report["checks"]["curatedProperties"] = unwrap(curated)
        if not after_restart:
            status, _ = writer.upsert(DATASET, "datasetProperties", {
                "name": "Synthetic customer dataset updated", "description": "Updated ingested description",
                "customProperties": {"d0_keep": "retained"}}, async_=True)
            require(status < 300, "scoped writer accepted")
            report["checks"]["asyncMutationAcknowledgement"] = status
        _, value = wait_for(lambda: reader.aspect(DATASET, "datasetProperties"),
                            lambda r: r[0] == 200 and unwrap(r[1]).get("name") == "Synthetic customer dataset updated",
                            "durable async mutation readback")
        require(unwrap(value).get("customProperties") == {"d0_keep": "retained"},
                "aspect upsert replacement observed")
        report["checks"]["datasetProperties"] = unwrap(value)
        status, ownership = reader.aspect(DATASET, "ownership")
        require(status == 200 and unwrap(ownership).get("owners", [{}])[0].get("owner") ==
                "urn:li:corpuser:bridge-d0-writer", "ownership aspect unaffected by property upsert")
        report["checks"]["ownership"] = unwrap(ownership)
        report["checks"]["applicationProjection"] = application_checks(state, after_restart)
        gql = "query($urn: String!) { dataset(urn: $urn) { urn name properties { description } editableProperties { description } } }"
        status, value = reader.graphql(gql, {"urn": DATASET})
        require(status == 200 and not value.get("errors") and value.get("data", {}).get("dataset", {}).get("urn") == DATASET,
                "GraphQL exact dataset lookup")
        report["checks"]["graphqlDataset"] = value["data"]
        search_query = "query($start: Int!) { searchAcrossEntities(input: {types: [DATASET], query: \"bridge_d0\", start: $start, count: 1}) { start count total searchResults { entity { urn } } } }"
        first = wait_for(lambda: admin.graphql(search_query, {"start": 0}),
                         lambda r: r[0] == 200 and not r[1].get("errors") and
                         r[1].get("data", {}).get("searchAcrossEntities", {}).get("total", 0) >= 2,
                         "search indexing")
        second = admin.graphql(search_query, {"start": 1})
        require(second[0] == 200 and not second[1].get("errors"), "second search page")
        page_a, page_b = first[1]["data"]["searchAcrossEntities"], second[1]["data"]["searchAcrossEntities"]
        require(page_a["searchResults"] and page_b["searchResults"] and
                page_a["searchResults"][0]["entity"]["urn"] != page_b["searchResults"][0]["entity"]["urn"],
                "distinct paginated entities")
        report["checks"]["adminSearchPages"] = [page_a, page_b]
        status, reader_search = reader.graphql(search_query.replace("count: 1", "count: 10"), {"start": 0})
        report["checks"]["readerSearchObservation"] = {"status": status, "response": reader_search}
        status, hidden_lookup = reader.graphql(gql, {"urn": HIDDEN})
        report["checks"]["readerRestrictedGraphqlObservation"] = {"status": status, "response": hidden_lookup}
        # Record actual OSS search behavior; a page gate must not be mistaken for search filtering.
        report["checks"]["frontendAuthentication"] = frontend_auth(state)
        status, value = admin.graphql("{ __schema { queryType { name } mutationType { name } types { name kind fields { name } } } }")
        require(status == 200 and not value.get("errors") and value.get("data", {}).get("__schema"), "GraphQL schema capture")
        schema_bytes = json.dumps(value, sort_keys=True, indent=2).encode()
        (directory / "graphql-schema.json").write_bytes(schema_bytes)
        report["graphqlSchemaSha256"] = hashlib.sha256(schema_bytes).hexdigest()
        status, openapi = admin.request("GET", "/openapi/v3/api-docs")
        require(status == 200 and openapi.get("openapi") and openapi.get("paths"), "OpenAPI schema capture")
        schema_bytes = json.dumps(openapi, sort_keys=True, indent=2).encode()
        (directory / "openapi.json").write_bytes(schema_bytes)
        report["openApiSha256"] = hashlib.sha256(schema_bytes).hexdigest()
        status, config = admin.request("GET", "/config")
        report["checks"]["serverConfig"] = config if status == 200 else {"status": status}
        if not after_restart:
            status, _ = admin.upsert(HIDDEN, "datasetProperties", {"name": "Restricted synthetic dataset",
                                      "description": None, "customProperties": {"d0_keep": "retained"}})
            report["checks"]["explicitNullUpsertStatus"] = status
            require(status in (200, 201, 400, 422), "explicit null has a classified response")
            _, null_value = admin.aspect(HIDDEN, "datasetProperties")
            report["checks"]["explicitNullReadback"] = unwrap(null_value)
            status, _ = admin.upsert(HIDDEN, "datasetProperties", {"patch": [
                {"op": "add", "path": "/description", "value": "Patched synthetic description"}]},
                change_type="PATCH")
            require(status < 300, "JSON patch supported for datasetProperties")
            _, patch_value = wait_for(lambda: admin.aspect(HIDDEN, "datasetProperties"),
                                      lambda r: r[0] == 200 and unwrap(r[1]).get("description") == "Patched synthetic description",
                                      "patch readback")
            require(unwrap(patch_value).get("customProperties", {}).get("d0_keep") == "retained",
                    "patch preserves unrelated aspect member")
            report["checks"]["patchReadback"] = unwrap(patch_value)
            for removed in (True, False):
                require(admin.upsert(HIDDEN, "status", {"removed": removed})[0] < 300, "set synthetic lifecycle")
                _, lifecycle = wait_for(lambda: admin.aspect(HIDDEN, "status"),
                                        lambda r: r[0] == 200 and unwrap(r[1]).get("removed") == removed,
                                        "lifecycle readback")
            report["checks"]["softDeleteAndRestore"] = True
        _, topics = compose(state, "exec", "-T", "broker", "kafka-topics", "--bootstrap-server", "broker:29092", "--list", capture=True)
        (directory / "topics.txt").write_text(topics)
        report["checks"]["availableEventTopics"] = [line for line in topics.splitlines() if line.startswith("MetadataChange")]
        _, retention = compose(state, "exec", "-T", "broker", "kafka-configs", "--bootstrap-server", "broker:29092",
                               "--entity-type", "topics", "--entity-name", "MetadataChangeLog_Versioned_v1", "--describe", "--all", capture=True)
        (directory / "event-topic-config.txt").write_text(retention)
        report["passed"] = True
        print(f"D0 {'restart' if after_restart else 'initial'} API probes passed; {report_path}", flush=True)
    except RuntimeError as error:
        report["failure"] = str(error)
        raise
    finally:
        report_path.write_text(redact(json.dumps(report, indent=2, sort_keys=True), state) + "\n")
