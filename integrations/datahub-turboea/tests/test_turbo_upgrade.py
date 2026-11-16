import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"tools"))
from datahub_local import turbo_upgrade as upgrade


class TurboUpgradeTest(unittest.TestCase):
    def test_trial_is_private_and_cleanup_stays_in_its_generated_project_on_failure(self):
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command,1 if len(calls)==1 else 0,b"",b"")
        with tempfile.TemporaryDirectory() as temp, patch.object(upgrade,"free_port",return_value=18888), \
             patch.object(upgrade.subprocess,"run",side_effect=run):
            directory=Path(temp)/"trial"
            with self.assertRaisesRegex(RuntimeError,"migration trial command"):
                with upgrade.migration_trial(directory,Path(temp)/"unused.dump","db:reviewed","backend:reviewed","private-signing-key"):
                    self.fail("Failed startup must not yield a live trial")
            project=(directory/"project.txt").read_text()
            self.assertRegex(project,r"^turboea-upgrade-trial-[0-9a-f]{12}$")
            self.assertTrue(all(command[3]==project for command in calls))
            self.assertEqual(["down","--volumes","--remove-orphans"],calls[-1][-3:])
            self.assertEqual(0o600,(directory/"compose.json").stat().st_mode&0o777)
            document=json.loads((directory/"compose.json").read_text())
            services=document["services"]
            self.assertEqual(services["db"]["environment"]["POSTGRES_PASSWORD"],services["backend"]["environment"]["POSTGRES_PASSWORD"])
            self.assertNotIn("ports",services["db"])
            self.assertEqual(["127.0.0.1:18888:8000"],services["backend"]["ports"])
            self.assertEqual("false",services["backend"]["environment"]["RESET_DB"])
            self.assertNotIn("private-signing-key",(directory/"trial.log").read_text())

    def test_binary_backups_are_private_and_reject_container_names(self):
        def run(command,**kwargs):
            kwargs["stdout"].write(b"binary-backup")
            return subprocess.CompletedProcess(command,0,b"",b"")
        with tempfile.TemporaryDirectory() as temp, patch.object(upgrade.subprocess,"run",side_effect=run) as command:
            path=Path(temp)/"database.dump"
            upgrade.database_backup("a"*64,path)
            self.assertEqual(b"binary-backup",path.read_bytes())
            self.assertEqual(0o600,path.stat().st_mode&0o777)
            self.assertIn("--format=custom",command.call_args.args[0])
            with self.assertRaisesRegex(RuntimeError,"container identifier"):
                upgrade.database_backup("agency-database",Path(temp)/"forbidden.dump")
            self.assertEqual(1,command.call_count)


if __name__=="__main__": unittest.main()
