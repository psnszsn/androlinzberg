#!/usr/bin/env python3
"""The native runner must reject a crashed service even when its client passes."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class NativeRunnerTest(unittest.TestCase):
    def run_fixture(self, server_exit, args=None, file_arg=None, death_mode=None):
        with tempfile.TemporaryDirectory(prefix="azk-runner-test-") as temporary:
            directory = Path(temporary)
            broker = directory / "broker"
            broker.write_text(f"#!{sys.executable}\nimport sys\n"
                              "print('ready', flush=True)\nsys.stdin.read()\n")
            probe = directory / "probe"
            probe.write_text(f"#!{sys.executable}\nimport sys, os\n"
                             "if sys.argv[1] == 'server':\n"
                             "    assert os.environ['AZK_BINDER_SYSTEM_COOKIE'] == 'facade-v2-system'\n"
                             "    assert os.environ['AZK_BINDER_PACKAGE'] == 'dev.artzig.facade.v2.server'\n"
                             "    print('ready', flush=True)\n"
                             "    sys.stdin.read()\n"
                             f"    sys.exit({server_exit})\n"
                             f"assert sys.argv[1:] == {(args or ['client']) + ([str(Path(file_arg).resolve())] if file_arg else [])!r}\n"
                             "print('READY_DEATH', flush=True)\n")
            broker.chmod(0o755)
            probe.chmod(0o755)
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("binder-facade-test.py")),
                 "--broker", str(broker), "--probe", str(probe),
                 "--output", str(directory / "results"),
                 *["--probe-arg=" + arg for arg in (args or [])],
                 *(["--probe-file-arg=" + file_arg] if file_arg else []),
                 *([death_mode] if death_mode else [])],
                capture_output=True, text=True, timeout=15,
            )
            journal = json.loads((directory / "results/run/results.json").read_text())
            return result, journal

    def test_clean_service_and_client_pass(self):
        result, journal = self.run_fixture(0)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(journal["child_exit_codes"], [0, 0, 0])
        self.assertNotIn("error", journal)

    def test_client_arguments_preserve_spaces_and_option_prefixes(self):
        result, journal = self.run_fixture(0, ["--javatest", "/path with spaces/library.so", "--task-outputs"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(journal["child_exit_codes"], [0, 0, 0])

    def test_client_file_argument_resolves_before_chdir(self):
        result, journal = self.run_fixture(0, ["--javatest"], "./relative path/library.so")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(journal["child_exit_codes"], [0, 0, 0])

    def test_replacement_preserves_server_credentials(self):
        result, journal = self.run_fixture(0, death_mode="--restart-service")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(journal["service_restarted"])
        self.assertEqual(journal["child_exit_codes"], [0, 0, 0, 0])

    def test_complete_session_retirement(self):
        result, journal = self.run_fixture(0, death_mode="--retire-broker")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(journal["broker_retired_exit"], 0)
        self.assertEqual(journal["child_exit_codes"], [0, 0, 0])

    def test_failed_service_cannot_be_hidden_by_client_success(self):
        result, journal = self.run_fixture(7)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(journal["server_exit"], 7)
        self.assertEqual(journal["child_exit_codes"], [0, 7, 0])
        self.assertIn("service exited unexpectedly", journal["error"])


if __name__ == "__main__":
    unittest.main()
