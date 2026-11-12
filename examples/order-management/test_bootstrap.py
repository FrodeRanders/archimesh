import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import bootstrap
import local


class BootstrapTest(unittest.TestCase):
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
