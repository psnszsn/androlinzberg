#!/usr/bin/env python3
"""Public exec-boundary tests; helper fixtures record argv without starting ART."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

LAUNCHER = Path(sys.argv.pop(1)).resolve()


class ProcessEntryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / "image"
        (self.image / "bin").mkdir(parents=True)
        (self.image / "lib").mkdir()
        (self.image / "lib/libandroid_platform.so").touch()
        self.launcher = self.image / "bin/androlinzberg"
        shutil.copy2(LAUNCHER, self.launcher)
        helpers = self.image / "libexec/androlinzberg"
        helpers.mkdir(parents=True)
        for name in ("standalone", "system-server", "app-process", "metadata", "jvm-test"):
            helper = helpers / ("azk-" + name)
            helper.write_text(f"#!{sys.executable}\nimport json,sys\nprint(json.dumps(sys.argv))\n")
            helper.chmod(0o555)
        self.env = dict(os.environ)
        self.env.pop("ARTZIG_INSTALL_ROOT", None)
        self.env["HOME"] = str(self.root / "absent-home")

    def run_cli(self, *args, executable=None):
        return subprocess.run([str(executable or self.launcher), *args], cwd=self.root,
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_installed_dispatch_is_independent_of_working_directory(self):
        cases = [
            (("--standalone", "app.apk"), "standalone", ["app.apk"]),
            (("--system-server",), "system-server", ["--system-server"]),
            (("--runtime-process", "/app.apk"), "app-process", ["--runtime-process", "/app.apk"]),
            (("--inspect-apk-package", "/app.apk"), "metadata", ["--inspect-apk-package", "/app.apk"]),
            (("--javatest", "--join", "/instance", "Test", "arg"), "jvm-test",
             ["--join", "/instance", "--javatest", "Test", "arg"]),
        ]
        for args, name, expected in cases:
            with self.subTest(name=name):
                result = self.run_cli(*args)
                self.assertEqual(result.returncode, 0, result.stderr)
                argv = json.loads(result.stdout)
                self.assertEqual(Path(argv[0]).name, "azk-" + name)
                self.assertEqual(argv[1:], expected)
        self.assertFalse((self.root / "absent-home").exists())

    def test_help_needs_no_runtime_or_writable_home(self):
        bare = self.root / "bare-launcher"
        shutil.copy2(LAUNCHER, bare)
        result = self.run_cli("--help", executable=bare)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--standalone", result.stdout)
        self.assertFalse((self.root / "absent-home").exists())

    def test_no_working_directory_fallback_and_explicit_image(self):
        bare = self.root / "bare-launcher"
        shutil.copy2(LAUNCHER, bare)
        (self.root / "zig-out").symlink_to(self.image, target_is_directory=True)
        self.assertNotEqual(self.run_cli("app.apk", executable=bare).returncode, 0)
        self.env["ARTZIG_INSTALL_ROOT"] = str(self.image)
        self.assertEqual(self.run_cli("app.apk", executable=bare).returncode, 0)
        self.env["ARTZIG_INSTALL_ROOT"] = "image"
        result = self.run_cli("app.apk", executable=bare)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("InstallRootMustBeAbsolute", result.stderr)

    def test_system_request_does_not_fall_back_to_standalone(self):
        result = self.run_cli("--system", "app.apk")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SystemDeploymentNotImplemented", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_missing_join_value_fails_before_exec(self):
        result = self.run_cli("--join")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MissingSessionDirectory", result.stderr)


if __name__ == "__main__":
    unittest.main()
