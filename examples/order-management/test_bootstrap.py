import io
from contextlib import redirect_stdout
import json
from pathlib import Path
import tarfile
import tempfile
import shlex
import unittest
from unittest.mock import patch

import bootstrap
import local


class BootstrapTest(unittest.TestCase):
    def test_connection_summary_contains_run_urls_and_matching_archi_launch_identity(self):
        state = {"run":"0123456789ab","env":{"DEMO_ARCHIMESH_PORT":"18081", "DEMO_TURBO_UI_PORT":"18920"}}
        with tempfile.TemporaryDirectory() as temp, patch.object(local,"directory",return_value=Path(temp)), \
             patch.object(local,"datahub",return_value={"env":{"DATAHUB_FRONTEND_PORT":"19002"},"directory":"/private/demo-datahub"}):
            (Path(temp)/"artifacts").mkdir()
            (Path(temp)/"artifacts/archimesh-model.json").write_text(json.dumps({"model":{"modelId":"prepared-model"}}))
            output = io.StringIO()
            with redirect_stdout(output): bootstrap.access(state)
            summary = output.getvalue()
            for text in ("http://127.0.0.1:18081/admin-ui/", "http://127.0.0.1:18920", "http://127.0.0.1:19002",
                         "ws://127.0.0.1:18081", "prepared-model (HEAD)", "catalog in Archi: disabled", "access.json"):
                self.assertIn(text,summary)
            launch = shlex.split(next(line.strip() for line in summary.splitlines() if line.startswith("  env ")))
            self.assertIn("ARCHIMESH_BOOTSTRAP_USER=northstar-demo-admin",launch)
            self.assertIn("ARCHIMESH_BOOTSTRAP_ROLES=admin",launch)
            self.assertIn("ARCHIMESH_STARTUP_PULL_WS_BASE_URL=ws://127.0.0.1:18081",launch)
            self.assertIn("ARCHIMESH_STARTUP_PULL_MODEL_ID=prepared-model",launch)
            access = json.loads((Path(temp)/"access.json").read_text())
            self.assertEqual("prepared-model",access["archiConnection"]["modelId"])

    def test_connection_command_does_not_require_docker_or_start_services(self):
        state = {"mode":"compose"}
        with patch.object(bootstrap,"preflight") as preflight, patch.object(local,"load",return_value=state), \
             patch.object(bootstrap,"access") as access, patch("sys.argv",["bootstrap.py","connection","--run","0123456789ab"]), \
             redirect_stdout(io.StringIO()):
            bootstrap.main()
            preflight.assert_not_called()
            access.assert_called_once_with(state)

    def archive(self, path, kind, name):
        with tarfile.open(path,"w:gz") as stream:
            member = tarfile.TarInfo("turbo-ea-" + bootstrap.REVISION + "/" + name)
            member.type = kind
            if kind == tarfile.REGTYPE:
                body = b"2.194.0\n"
                member.size = len(body)
                stream.addfile(member,io.BytesIO(body))
            else:
                member.linkname = "/outside"
                stream.addfile(member)

    def test_source_archive_rejects_links_and_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for kind,name in ((tarfile.SYMTYPE,"backend/link"),(tarfile.REGTYPE,"backend/../../outside")):
                self.archive(root / "source.tar.gz",kind,name)
                with self.assertRaises(RuntimeError): bootstrap.extract_source(root / "source.tar.gz",root / "out")
            self.assertFalse((root / "outside").exists())

    def test_container_overlay_uses_private_service_addresses_and_loopback_ports(self):
        state = {"run":"0123456789ab","project":"northstar-demo-0123456789ab","env":{
            "DEMO_ARCHIMESH_IMAGE":"demo-server:fixture","DEMO_ARCHIMESH_PORT":"18081",
            "DEMO_TURBO_IMAGE":"demo-backend:fixture","DEMO_TURBO_DB_IMAGE":"demo-db:fixture",
            "DEMO_TURBO_UI_IMAGE":"demo-ui:fixture","DEMO_TURBO_UI_PORT":"18920"}}
        with tempfile.TemporaryDirectory() as temp, patch.object(local,"RUNS",Path(temp)):
            (Path(temp)/state["run"]).mkdir()
            bootstrap.override(state)
            path = Path(temp)/state["run"]/"compose.override.json"
            document = json.loads(path.read_text())
            server = document["services"]["archimesh"]
            self.assertEqual("bolt://neo4j:7687",server["environment"]["NEO4J_URI"])
            self.assertEqual("kafka:9092",server["environment"]["KAFKA_BOOTSTRAP_SERVERS"])
            self.assertEqual("false",server["environment"]["ARCHIMESH_INTEGRATIONS_ENABLED"])
            for service in document["services"].values():
                self.assertNotIn("container_name",service)
                self.assertNotIn("privileged",service)
                self.assertNotIn("volumes",service)
                self.assertTrue(all(p.startswith("127.0.0.1:") for p in service.get("ports",[])))
            self.assertEqual(0o600,path.stat().st_mode & 0o777)

    def test_application_build_context_excludes_local_credentials(self):
        ignored=(bootstrap.HERE / "Dockerfile.archimesh.dockerignore").read_text().splitlines()
        self.assertEqual("**",ignored[0])
        for pattern in ("**/.env","**/.env.*","**/*.pem","**/*.key"):
            self.assertIn(pattern,ignored)
            self.assertGreater(ignored.index(pattern),ignored.index("!admin-web/**"))

    def test_clean_test_build_failure_cleans_only_its_recorded_run(self):
        state = {"run":"0123456789ab","mode":"compose"}
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(bootstrap,"preflight"), patch.object(local,"initialize",return_value=state), \
                 patch.object(local,"directory",return_value=Path(temp)), patch.object(bootstrap,"override"), \
                 patch.object(bootstrap,"build",side_effect=RuntimeError("Synthetic build failure")), \
                 patch.object(local,"down") as cleanup, patch("sys.argv",["bootstrap.py","test"]):
                with self.assertRaisesRegex(RuntimeError,"Synthetic build failure"): bootstrap.main()
                cleanup.assert_called_once_with(state,volumes=True)


if __name__ == "__main__": unittest.main()
