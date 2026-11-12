"""Installer regressions, isolated from the user's Archi installation and Maven cache."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PLUGIN = "org.gautelis.archimesh.plugin"


class ClientPluginScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="archimesh-plugin-")
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.client = self.work / "client workspace"
        self.client.mkdir()
        self.target = self.client / PLUGIN / "target"
        self.dropins = self.work / "dropins"
        self.installed = self.dropins / "eclipse" / "plugins"
        self.archi = self.work / "archi"
        for bundle in ("com.archimatetool.model", "com.archimatetool.editor"):
            dest = self.archi / bundle / (bundle + ".jar")
            dest.parent.mkdir(parents=True)
            dest.write_text(bundle)
        self.env = dict(os.environ, ARCHI_DIR=str(self.archi))
        # Fail if the client flow accidentally invokes system Maven again.
        bin_dir = self.work / "bin"
        bin_dir.mkdir()
        self.executable(bin_dir / "mvn", "#!/bin/sh\necho 'system Maven invoked' >&2\nexit 97\n")
        self.env["PATH"] = str(bin_dir) + os.pathsep + os.environ["PATH"]
        self.executable(self.client / "mvnw", """#!/bin/sh
set -eu
test "$*" = '-q -DskipTests package'
test -f target-platform/plugins/com.archimatetool.model.jar
test -f target-platform/plugins/com.archimatetool.editor.jar
echo build >> wrapper-calls
mkdir -p org.gautelis.archimesh.plugin/target
echo runtime > org.gautelis.archimesh.plugin/target/org.gautelis.archimesh.plugin-5.8.0-SNAPSHOT.jar
""")

    def executable(self, path, text):
        path.write_text(text)
        path.chmod(0o755)

    def install(self, *args):
        return subprocess.run(
            [str(ROOT / "scripts/install-archimesh-plugin.sh"),
             "--client-dir", str(self.client), "--dropins", str(self.dropins),
             "--no-prune-duplicates", *args],
            cwd=self.work, env=self.env, text=True, capture_output=True,
        )

    def test_build_uses_wrapper_and_prints_exact_artifact_then_reuses_it(self):
        built = self.install()
        self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
        jar = self.target / (PLUGIN + "-5.8.0-SNAPSHOT.jar")
        self.assertIn("Plugin JAR: " + str(jar), built.stdout)
        self.assertEqual((self.installed / jar.name).read_text(), "runtime\n")
        reused = self.install("--no-build")
        self.assertEqual(reused.returncode, 0, reused.stdout + reused.stderr)
        self.assertEqual((self.client / "wrapper-calls").read_text(), "build\n")

    def test_no_build_excludes_newer_auxiliary_jars(self):
        self.target.mkdir(parents=True)
        runtime = self.target / (PLUGIN + "-5.8.0-SNAPSHOT.jar")
        runtime.write_text("runtime")
        os.utime(runtime, (1, 1))
        for suffix in ("sources", "javadoc", "tests"):
            (self.target / (PLUGIN + "-5.8.0-SNAPSHOT-" + suffix + ".jar")).write_text(suffix)
        result = self.install("--no-build")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([p.name for p in self.installed.iterdir()], [runtime.name])

    def test_failed_build_or_missing_artifact_preserves_installed_plugin(self):
        self.installed.mkdir(parents=True)
        old = self.installed / (PLUGIN + "-old.jar")
        old.write_text("working plugin")
        self.executable(self.client / "mvnw", "#!/bin/sh\nexit 42\n")
        for args in ((), ("--no-build",)):
            with self.subTest(args=args):
                result = self.install(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(old.read_text(), "working plugin")
        self.assertIn("Plugin JAR not found", result.stderr)

    def test_patch_ini_puts_single_property_after_vmargs(self):
        self.target.mkdir(parents=True)
        (self.target / (PLUGIN + "-5.8.0-SNAPSHOT.jar")).write_text("runtime")
        app = self.work / "Archi.app"
        ini = app / "Contents/Eclipse/Archi.ini"
        ini.parent.mkdir(parents=True)
        prop = "-Dorg.eclipse.equinox.p2.reconciler.dropins.directory="
        for original in ("-startup\nlauncher.jar\n-vmargs\n-Xmx2G\n", "-startup\nlauncher.jar\n"):
            with self.subTest(original=original):
                ini.write_text(prop + "/old\n" + original)
                for _ in range(2):
                    result = self.install("--no-build", "--archi-app", str(app), "--patch-ini")
                    self.assertEqual(result.returncode, 0, result.stderr)
                lines = ini.read_text().splitlines()
                arg = prop + str(self.dropins)
                self.assertEqual(sum(line.startswith(prop) for line in lines), 1)
                self.assertEqual(lines.index(arg), lines.index("-vmargs") + 1)


if __name__ == "__main__":
    unittest.main()
