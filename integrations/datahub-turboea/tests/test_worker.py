import http.server
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"src"))
from datahub_turboea.owner import OwnerError
from datahub_turboea.worker import DataHubDiscovery,DatasetBridge,ApplicationBridge,DiscoveryError

URN="urn:li:dataset:(urn:li:dataPlatform:postgres,orders,DEV)"


class WorkerTest(unittest.TestCase):
    def test_product_discovery_uses_product_type_and_rejects_wrong_native_entities(self):
        product="urn:li:dataProduct:orders"; mode={"wrong":False}
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self_test.assertIn("[DATA_PRODUCT]",body["query"])
                data=json.dumps({"data":{"searchAcrossEntities":{"start":0,"count":1,"total":1,
                    "searchResults":[{"entity":{"urn":URN if mode["wrong"] else product}}]}}}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
            def log_message(self,*args): pass
        self_test=self
        with http.server.ThreadingHTTPServer(("127.0.0.1",0),Handler) as server:
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            try:
                discovery=DataHubDiscovery("http://127.0.0.1:"+str(server.server_port),lambda:"token",external_type="dataProduct")
                self.assertEqual([product],discovery.entities())
                mode["wrong"]=True
                with self.assertRaises(DiscoveryError): discovery.entities()
            finally: server.shutdown(); thread.join()

    def test_product_and_dataset_profiles_keep_separate_native_scopes(self):
        class Discovery:
            def entities(self): return ["urn:li:dataProduct:orders"]
        class Owner:
            def prepare_projection(self,profile,native_id,correlation):
                self_test.assertEqual("products",profile); self_test.assertEqual("urn:li:dataProduct:orders",native_id)
                return {"state":"VERIFIED"}
        self_test=self
        self.assertEqual(1,DatasetBridge(Owner(),Discovery(),"products",["urn:li:dataProduct:"],"dataProduct").cycle()["verified"])
        with self.assertRaises(ValueError): DatasetBridge(Owner(),Discovery(),"datasets",["urn:li:dataProduct:"])

    def test_application_polling_is_model_independent_and_keeps_stable_correlations(self):
        class Owner:
            def __init__(self): self.verified=set(); self.correlations={}; self.writes=0
            def prepare_application(self,profile,native_id,correlation):
                previous=self.correlations.setdefault(native_id,correlation)
                self_test.assertEqual(previous,correlation)
                return {"intentId":native_id,"version":1,"state":"VERIFIED" if native_id in self.verified else "PREPARED"}
            def execute_application(self,profile,native_id,correlation,plan):
                self.writes+=1; self.verified.add(native_id); return {"state":"VERIFIED"}
        self_test=self; owner=Owner()
        for _ in range(2):
            self.assertEqual(2,ApplicationBridge(owner,"apps",["app-1","app-2"]).cycle()["verified"])
        self.assertEqual(2,owner.writes)
        self.assertNotEqual(owner.correlations["app-1"],owner.correlations["app-2"])

    def test_application_uncertainty_never_triggers_automatic_execution_or_recovery(self):
        class Owner:
            def prepare_application(self,*args): return {"state":"UNCERTAIN"}
            def execute_application(self,*args): self_test.fail("Must never redispatch")
        self_test=self
        self.assertEqual(1,ApplicationBridge(Owner(),"apps",["app-1"]).cycle()["blocked"])
        with self.assertRaises(ValueError): ApplicationBridge(Owner(),"apps",[])
        with self.assertRaises(ValueError): ApplicationBridge(Owner(),"apps","app-1")

    def test_polling_uses_the_owner_and_preserves_one_identity_after_worker_restart(self):
        class Discovery:
            def datasets(self): return [URN]
        class Owner:
            def __init__(self): self.writes=0; self.verified=False; self.correlations=[]
            def prepare_projection(self,profile,urn,correlation):
                self.correlations.append(correlation)
                return {"intentId":"one","version":1,"state":"VERIFIED" if self.verified else "PREPARED"}
            def execute_projection(self,*args):
                self.writes+=1; self.verified=True
                return {"state":"VERIFIED"}
        owner=Owner()
        for _ in range(2):
            bridge=DatasetBridge(owner,Discovery(),"datasets",["urn:li:dataset:("])
            self.assertEqual(1,bridge.cycle()["verified"])
        self.assertEqual(1,owner.writes)
        self.assertEqual(owner.correlations[0],owner.correlations[1])

    def test_uncertain_create_is_not_redispatched(self):
        class Discovery:
            def datasets(self): return [URN]
        class Owner:
            def __init__(self): self.writes=0
            def prepare_projection(self,*args): return {"intentId":"one","version":1,"state":"UNCERTAIN" if self.writes else "PREPARED"}
            def execute_projection(self,*args):
                self.writes+=1
                raise OwnerError(409,"PROJECTION_OUTCOME_UNCERTAIN_REQUIRES_REVIEW")
        owner=Owner(); bridge=DatasetBridge(owner,Discovery(),"datasets",["urn:li:dataset:("])
        self.assertEqual(1,bridge.cycle()["blocked"])
        self.assertEqual(1,bridge.cycle()["blocked"])
        self.assertEqual(1,owner.writes)

    def test_discovery_pages_exact_ids_and_does_not_accept_partial_or_denied_results(self):
        mode={"error":False}; calls=[]
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append((self.path,body["variables"]["start"],self.headers["Authorization"]))
                start=body["variables"]["start"]
                urn=URN if start==0 else URN.replace("orders","lines")
                value={"errors":[{"message":"PRIVATE"}]} if mode["error"] else {"data":{"searchAcrossEntities":{
                    "start":start,"count":1,"total":2,"searchResults":[{"entity":{"urn":urn}}]}}}
                data=json.dumps(value).encode(); self.send_response(200); self.send_header("Content-Length",str(len(data))); self.end_headers(); self.wfile.write(data)
            def log_message(self,*args): pass
        with http.server.ThreadingHTTPServer(("127.0.0.1",0),Handler) as server:
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            try:
                discovery=DataHubDiscovery("http://127.0.0.1:"+str(server.server_port),lambda:"synthetic-token")
                self.assertEqual(2,len(discovery.datasets())); self.assertEqual([0,1],[c[1] for c in calls])
                self.assertTrue(all(c[0]=="/api/graphql" and c[2]=="Bearer synthetic-token" for c in calls))
                discovery.max_pages=1
                with self.assertRaises(DiscoveryError): discovery.datasets()
                mode["error"]=True
                with self.assertRaisesRegex(DiscoveryError,"denied or unavailable"): discovery.datasets()
            finally: server.shutdown(); thread.join()

    def test_private_http_requires_explicit_approval(self):
        with self.assertRaises(ValueError): DataHubDiscovery("http://gms:8080",lambda:"token")
        DataHubDiscovery("http://gms:8080",lambda:"token",approved_plain_http=True)
        for url in ("http://user:secret@localhost", "http://localhost/other", "http://localhost?token=x"):
            with self.subTest(url=url),self.assertRaises(ValueError): DataHubDiscovery(url,lambda:"token")


if __name__=="__main__": unittest.main()
