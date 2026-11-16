import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import live
import local
import publication


class PublicationDemoTest(unittest.TestCase):
    def test_demo_permissions_publish_only_the_custom_architectural_relation(self):
        state={"run":"0123456789ab","project":"northstar-demo-0123456789ab"}
        target=live.documents(state,publication_enabled=True)[0]["targets"][0]
        access=target["publicationAccess"]
        self.assertEqual([publication.RELATION],access["relationTypes"])
        self.assertEqual(["attributes.operations"],access["fields"])
        self.assertNotIn(live.TYPE,access["cardTypes"])
        self.assertNotIn(live.PRODUCT_TYPE,access["cardTypes"])
        self.assertEqual("DATAHUB_MASTER",target["mapping"]["fields"][live.TYPE]["name"]["authority"])
        profile=publication.definition()
        self.assertEqual(["TAG"],profile["sourceModes"])
        self.assertTrue(all(s["referenceOnly"] and not s["allowCreate"] and not s["fields"] for s in profile["elements"]))
        self.assertEqual("demo.accessType",profile["derivations"][0]["accessModeProperty"])
        self.assertEqual({"READ":"READ","WRITE":"WRITE"},profile["derivations"][0]["accessValues"])
        self.assertNotIn("publicationAccess",live.documents(state)[0]["targets"][0],"existing live-only runs remain unchanged")

    def test_link_uses_real_preview_and_reviewed_inbound_baseline(self):
        calls=[]
        def api(state,method,path,body=None):
            calls.append((method,path,body))
            if "/preview?" in path: return {"elementId":None,"digest":"reviewed"}
            if path.endswith("/import"): return {"elementId":"elem:existing","cardId":"native","canonicalId":"canonical"}
            if "/resource/" in path: return {"digest":"current-master-digest"}
            return {"state":"VERIFIED"}
        with patch.object(publication,"request",side_effect=api):
            result=publication.link({},"native","elem:existing")
        self.assertEqual("elem:existing",result["elementId"])
        self.assertEqual("LINK",next(body for method,path,body in calls if path.endswith("/import"))["mode"])
        self.assertEqual({"elementId":"elem:existing","inspectionDigest":"current-master-digest"},calls[-1][2])
        self.assertFalse(any("adopt-baseline" in path for _,path,_ in calls))

    def test_missing_curated_description_restores_rendered_master_text(self):
        self.assertEqual({"description":"Ingested source text"},publication.curated_baseline(404,None,"Ingested source text"))
        with patch("datahub_local.probe.unwrap",return_value={"description":"","lastModified":{"actor":"demo"}}):
            self.assertEqual({"description":"","lastModified":{"actor":"demo"}},publication.curated_baseline(200,{},"Ignored fallback"))

    def test_existing_different_occurrence_is_not_silently_rebound(self):
        with patch.object(publication,"request",return_value={"elementId":"elem:other"}) as request:
            with self.assertRaisesRegex(RuntimeError,"different model occurrence"): publication.link({},"native","elem:expected")
            self.assertEqual(1,request.call_count)

    def test_unknown_metamodel_is_not_replaced(self):
        bad={"key":publication.RELATION,"source_type_key":"Application","target_type_key":"DataObject","cardinality":"n:m",
             "attributes_schema":[{"key":"operations","type":"multiselect","options":[{"key":"DELETE"}]}]}
        with patch.object(publication,"request"), patch.object(local,"turbo_api"):
            calls=[]
            def api(method,path,body=None): calls.append((method,path)); return [bad]
            with self.assertRaisesRegex(RuntimeError,"compatible demo logical access values"): publication.metamodel(api)
            self.assertEqual([("GET","/api/v1/metamodel/relation-types")],calls)

    def test_ambiguous_publication_stops_without_retry_or_baseline_adoption(self):
        pin={"profileId":publication.PROFILE,"version":"1.0.0","digest":"a"*64}
        spec={"mode":"TAG","ref":"demo-publication-r3","elements":["elem:a"],"relationships":[],"stewards":[],"profile":pin}
        calls=[]
        def api(state,method,path,body=None):
            calls.append((method,path,body))
            if path.endswith("/export"): return {"model":{"headRevision":3}}
            if path.endswith("/tags"): return [{"tagName":"demo-publication-r3","revision":3}]
            if path.endswith("/profiles/v1"): return {"profiles":[{"pin":pin}]}
            if path.endswith("/explain/v1"): return {"preview":{"digest":"b"*64,"plan":{"resources":[]}}}
            if path.endswith("/run"): return {"state":"PENDING"}
            return {"policy":{"version":1,"policy":spec},"job":{"state":"AMBIGUOUS","code":"REQUIRES_REVIEW"}}
        with tempfile.TemporaryDirectory() as temp, patch.object(local,"directory",return_value=Path(temp)), \
             patch.object(publication,"enabled",return_value=True),patch.object(publication,"request",side_effect=api), \
             patch.object(publication,"wait_for",side_effect=lambda action,*args:action()):
            (Path(temp)/"publication-demo.json").write_text(json.dumps({"bindings":[{"elementId":"elem:a"}]}))
            (Path(temp)/"artifacts").mkdir()
            with self.assertRaisesRegex(RuntimeError,"Demo publication stopped"): publication.publish({})
        self.assertEqual(1,sum(method=="POST" and path.endswith("/run") for method,path,_ in calls))
        self.assertFalse(any("adopt" in path for _,path,_ in calls))


if __name__=="__main__": unittest.main()
