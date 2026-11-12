"""Guard the D0 harness's project isolation and bounded, secret-free evidence."""
import base64
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(HERE))
from datahub_local import harness
from datahub_local.probe import unwrap


class DataHubHarnessTest(unittest.TestCase):
    def test_secrets_and_ports_survive_repeated_init(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, "RUNS", Path(directory)):
            first = harness.initialize("it-0123456789ab", ephemeral=True)
            second = harness.initialize("it-0123456789ab", ephemeral=True)
            self.assertEqual(first, second)
            self.assertNotEqual(first["env"]["DATAHUB_GMS_PORT"], first["env"]["DATAHUB_FRONTEND_PORT"])
            for name in (".env", "user.props", "state.json"):
                self.assertEqual(0o600, (Path(first["directory"]) / name).stat().st_mode & 0o777)

    def test_rejects_foreign_project_and_path(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, "RUNS", Path(directory)):
            for name in ("../production", "turboea", "it-production", "dev/../prod"):
                with self.assertRaises(ValueError): harness.initialize(name)
            state = harness.initialize("dev")
            path = Path(state["directory"]) / "state.json"
            doc = json.loads(path.read_text()); doc["project"] = "datahub-production"
            path.write_text(json.dumps(doc))
            with self.assertRaises(ValueError): harness.load(path.parent)
            with self.assertRaises(ValueError): harness.load(Path(directory).parent)

    def test_local_bootstrap_claims_and_evidence_redaction(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, "RUNS", Path(directory)):
            state = harness.initialize("dev")
            token = harness.session_token(state, "datahub")
            payload = token.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            self.assertEqual("SESSION", claims["type"])
            self.assertEqual("datahub", claims["actorId"])
            evidence = harness.redact(token + " " + state["env"]["DATAHUB_DB_PASSWORD"], state)
            self.assertEqual("<token> <redacted>", evidence)
            self.assertEqual("<redacted>", harness.redact(state["private_values"][0], state))

    def test_compose_has_only_scoped_storage_and_loopback_frontdoors(self):
        doc = json.loads(harness.COMPOSE.read_text())
        self.assertNotIn("name", doc)
        self.assertEqual({"gms", "frontend", "mysql", "broker", "search", "system-update"}, set(doc["services"]))
        self.assertTrue(all("name" not in v for v in doc["volumes"].values()))
        for name, service in doc["services"].items():
            self.assertNotIn("container_name", service)
            self.assertNotIn("network_mode", service)
            self.assertNotIn("privileged", service)
            for port in service.get("ports", []):
                self.assertIn(name, ("gms", "frontend"))
                self.assertEqual("127.0.0.1", port["host_ip"])
            self.assertNotIn(":latest", service["image"])
        self.assertNotIn("${HOME}", harness.COMPOSE.read_text())
        self.assertNotIn(".aws", harness.COMPOSE.read_text())
        for service in ("gms", "frontend"):
            self.assertEqual("true", doc["services"][service]["environment"]["METADATA_SERVICE_AUTH_ENABLED"])
        self.assertEqual("service_completed_successfully", doc["services"]["gms"]["depends_on"]["system-update"]["condition"])

    def test_restli_aspect_envelopes(self):
        self.assertEqual({"name": "x"}, unwrap({"aspect": {"com.linkedin.dataset.DatasetProperties": {"name": "x"}}}))
        self.assertEqual({"name": "x"}, unwrap({"aspect": {"value": '{"name":"x"}', "contentType": "application/json"}}))

    def test_failed_probe_still_cleans_up_only_its_test_project(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, "RUNS", Path(directory)):
            state = harness.initialize("it-0123456789ab", ephemeral=True)
            with patch.object(harness, "initialize", return_value=state), patch.object(harness, "up"), \
                    patch.object(harness, "compose"), patch.object(harness, "down") as cleanup, \
                    patch("datahub_local.probe.run", side_effect=RuntimeError("Synthetic failure")), \
                    patch.object(sys, "argv", ["local.py", "test"]), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, harness.main())
            cleanup.assert_called_once_with(state, volumes=True)
            self.assertNotIn("probes and cleanup passed", output.getvalue())

    def test_cleanup_failure_makes_gate_fail(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(harness, "RUNS", Path(directory)):
            state = harness.initialize("it-0123456789ab", ephemeral=True)
            with patch.object(harness, "initialize", return_value=state), patch.object(harness, "up"), \
                    patch.object(harness, "compose"), patch.object(harness, "down", side_effect=RuntimeError("Cleanup failed")), \
                    patch("datahub_local.probe.run"), patch.object(sys, "argv", ["local.py", "test"]), \
                    redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, harness.main())
            self.assertNotIn("probes and cleanup passed", output.getvalue())


if __name__ == "__main__": unittest.main()
