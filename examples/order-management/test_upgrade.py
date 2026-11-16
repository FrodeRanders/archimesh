import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import local
import turbo_version
import upgrade


class UpgradeTest(unittest.TestCase):
    def test_version_pin_keeps_legacy_runs_explicit_until_upgrade(self):
        self.assertEqual("2.194.0",turbo_version.deployed_version({}))
        self.assertEqual("2.203.2",turbo_version.deployed_version({"turboVersion":turbo_version.VERSION}))
        self.assertTrue(all(image.endswith(":"+turbo_version.REVISION[:7]) for image in turbo_version.images().values()))

    def test_build_failure_restores_configuration_without_stopping_services(self):
        state={"run":"0123456789ab","mode":"compose","env":{}}
        with tempfile.TemporaryDirectory() as temp, patch.object(local,"directory",return_value=Path(temp)), \
             patch.object(local,"turbo_api"),patch.object(local,"compose") as compose:
            root=Path(temp);original=json.dumps(state);(root/"state.json").write_text(original)
            (root/"compose.override.json").write_text("old configuration")
            def build(candidate):
                self.assertEqual(turbo_version.VERSION,candidate["turboVersion"])
                (root/"compose.override.json").write_text("new configuration")
                raise RuntimeError("build failed")
            with self.assertRaisesRegex(RuntimeError,"build failed"): upgrade.run(state,build)
            self.assertEqual("old configuration",(root/"compose.override.json").read_text())
            self.assertEqual(original,(root/"state.json").read_text())
            compose.assert_not_called()
            self.assertNotIn("turboVersion",state)

    def test_identity_comparison_ignores_refresh_evidence_but_detects_native_id_change(self):
        original={"nodes":[{"label":"IntegrationExternalBinding","properties":{"canonicalId":"canonical-a","resourceId":"native-a","fence":1,"identityProofId":"old"}}]}
        refreshed=json.loads(json.dumps(original));refreshed["nodes"][0]["properties"].update(fence=10,identityProofId="renewed")
        self.assertEqual(upgrade.bindings(original),upgrade.bindings(refreshed))
        refreshed["nodes"][0]["properties"]["resourceId"]="native-b"
        self.assertNotEqual(upgrade.bindings(original),upgrade.bindings(refreshed))


if __name__=="__main__": unittest.main()
