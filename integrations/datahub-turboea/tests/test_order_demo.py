"""The demo adapter remains independent and handles unknown creates conservatively."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1] / "tools"))
from datahub_local.order_demo import turbo_seed, aspects, contains


class OrderDemoTest(unittest.TestCase):
    def fixture(self):
        return {"applications":[{"id":"oms","canonicalId":"urn:demo:app:oms","urn":"urn:li:application:demo-oms",
                "name":"Fixture name","description":"Fixture description","sourcing":"in-house","infrastructure":[]}],
                "logicalData":[],"infrastructure":[],"accesses":[],"datasets":[],"products":[],"description":"Synthetic"}

    def test_unknown_create_stops_retry_and_preserves_intent(self):
        posts = []
        def api(method,path,body=None):
            if path.endswith("/types"): return [{"key":key} for key in ("Application","DataObject","ITComponent")]
            if path.endswith("/relation-types"):
                return [{"key":key,"source_type_key":"Application","target_type_key":target} for key,target in
                        (("relAppToDataObj","DataObject"),("relAppToITC","ITComponent"))]
            posts.append(body)
            raise OSError("Synthetic response lost after server commit")
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "journal.json"
            with self.assertRaises(OSError): turbo_seed(api,self.fixture(),path)
            with self.assertRaisesRegex(RuntimeError,"unknown outcome"): turbo_seed(api,self.fixture(),path)
            self.assertEqual(1,len(posts))
            self.assertEqual("applications:oms",json.loads(path.read_text())["pending"]["key"])
            self.assertEqual(0o600,path.stat().st_mode & 0o777)

    def test_application_projection_comes_from_turbo_readback(self):
        records = aspects(self.fixture(),{"oms":{"id":"native-42","name":"Turbo name","description":"Turbo description"}},"synthetic-instance")
        properties = next(value for urn,aspect,value in records if aspect == "applicationProperties")
        self.assertEqual("Turbo name",properties["name"])
        self.assertEqual("Turbo description",properties["description"])
        self.assertEqual("native-42",properties["customProperties"]["demo.source.nativeId"])

    def test_confirmed_native_id_is_reused_and_changed_fixture_rejected(self):
        posts = []
        def api(method,path,body=None):
            if path.endswith("/types"): return [{"key":key} for key in ("Application","DataObject","ITComponent")]
            if path.endswith("/relation-types"):
                return [{"key":key,"source_type_key":"Application","target_type_key":target} for key,target in
                        (("relAppToDataObj","DataObject"),("relAppToITC","ITComponent"))]
            posts.append(body)
            return {"id":"native-card-42"}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "journal.json"
            first = turbo_seed(api,self.fixture(),path)
            second = turbo_seed(api,self.fixture(),path)
            self.assertEqual(first,second)
            self.assertEqual("native-card-42",second["bindings"]["applications:oms"])
            altered = self.fixture(); altered["applications"][0]["name"] = "Different fixture"
            with self.assertRaisesRegex(RuntimeError,"unchanged demo fixture"): turbo_seed(api,altered,path)
            self.assertEqual(1,len(posts))

    def test_readback_checks_nested_values_while_allowing_server_defaults(self):
        expected = {"fields":[{"name":"order_id","type":{"string":{}}}]}
        actual = {"fields":[{"name":"order_id","type":{"string":{}},"nullable":False}],"created":123}
        self.assertTrue(contains(actual,expected))
        actual["fields"][0]["name"] = "wrong_id"
        self.assertFalse(contains(actual,expected))
        self.assertFalse(contains({},expected))


if __name__ == "__main__": unittest.main()
