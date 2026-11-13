import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import bootstrap
import live
import local


class LiveConfigurationTest(unittest.TestCase):
    def test_probe_retries_only_explicit_busy_without_changing_request(self):
        state=self.state(); body={"profile":"datasets","correlation":"same-review"}
        busy=local.ApiError("POST","/prepare",409,{"code":"INTEGRATION_BUSY"})
        with patch.object(local,"request_json",side_effect=[busy,{"state":"VERIFIED"}]) as request, \
             patch.object(live,"wait_for",side_effect=lambda action,*args: action() or action()):
            self.assertEqual("VERIFIED",live.request(state,"POST","/prepare",body)["state"])
            self.assertEqual(request.call_args_list[0],request.call_args_list[1])
        uncertain=local.ApiError("POST","/execute",409,{"code":"PROJECTION_OUTCOME_UNCERTAIN_REQUIRES_REVIEW"})
        with patch.object(local,"request_json",side_effect=uncertain) as request:
            with self.assertRaises(local.ApiError): live.request(state,"POST","/execute",body)
            self.assertEqual(1,request.call_count)
        self.assertNotIn("private-token",str(local.ApiError("POST","/prepare",500,{"code":"private-token","message":"secret"})))

    def state(self):
        return {"run":"0123456789ab","project":"northstar-demo-0123456789ab","env":{
            "DEMO_ARCHIMESH_IMAGE":"demo:server","DEMO_ARCHIMESH_PORT":"18081",
            "DEMO_TURBO_IMAGE":"demo:backend","DEMO_TURBO_DB_IMAGE":"demo:db",
            "DEMO_TURBO_UI_IMAGE":"demo:ui","DEMO_TURBO_UI_PORT":"18920"}}

    def test_approval_and_authority_distinguish_physical_and_logical_data(self):
        target,source,worker=live.documents(self.state())
        definition=target["targets"][0]
        self.assertNotIn("publicationAccess",definition)
        self.assertNotIn("centralEditAccess",definition)
        fields=definition["mapping"]["fields"]
        self.assertEqual("DATAHUB_MASTER",fields["DataHubDataset"]["name"]["authority"])
        self.assertEqual("TURBO_EA_MASTER",fields["DataObject"]["name"]["authority"])
        self.assertEqual(target["instanceId"],source["ownerInstanceId"])
        self.assertEqual([live.PRINCIPAL],source["projections"][0]["principals"])
        self.assertEqual(source["projections"][0]["nativePrefixes"],worker["nativePrefixes"])
        self.assertEqual("http://127.0.0.1:8081",worker["owner"]["baseUrl"])
        self.assertNotIn("access_token",json.dumps([target,source,worker]))

    def test_overlay_keeps_bootstrap_loopback_and_avoids_datahub_kafka_collision(self):
        state=self.state()
        with tempfile.TemporaryDirectory() as temp, patch.object(local,"RUNS",Path(temp)), \
             patch.object(local,"datahub",return_value={"project":"isolated-datahub"}):
            (Path(temp)/state["run"]).mkdir()
            bootstrap.override(state)
            live.overlay(state)
            document=json.loads((local.directory(state)/"compose.override.json").read_text())
            worker=document["services"]["dataset-bridge"]
            server=document["services"]["archimesh"]
            self.assertEqual("service:archimesh",worker["network_mode"])
            self.assertEqual("archimesh-kafka:9092",server["environment"]["KAFKA_BOOTSTRAP_SERVERS"])
            self.assertFalse(any("ports" in value for key,value in document["services"].items() if key in ("kafka","dataset-bridge")))
            self.assertEqual("isolated-datahub_default",document["networks"]["datahub"]["name"])
            self.assertEqual(server["env_file"],worker["env_file"])
            self.assertTrue(worker["read_only"])


if __name__ == "__main__": unittest.main()
