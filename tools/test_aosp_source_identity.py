#!/usr/bin/env python3
"""Source provenance failures must fail the acceptance gate."""
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("aosp-source-identity.py")
SPEC = importlib.util.spec_from_file_location("source_identity", SCRIPT)
identity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(identity)


class SourceIdentityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.reference = self.root / "reference"
        self.package = self.root / "package with spaces"
        self.reference.mkdir()
        self.package.mkdir()
        self.classes = self.root / "framework-classes.jar"
        self.classes.write_bytes(b"fixture build identity")
        self.response = self.root / "sources.rsp"
        self.paths = []

    def source(self, name, original, compiled=None):
        baseline, packaged = self.reference / name, self.package / name
        baseline.parent.mkdir(parents=True, exist_ok=True)
        packaged.parent.mkdir(parents=True, exist_ok=True)
        baseline.write_text(original)
        packaged.write_text(original if compiled is None else compiled)
        self.paths.append(packaged)
        return packaged

    def report(self):
        self.response.write_text("\n".join(shlex.quote(str(path)) for path in self.paths))
        return identity.compare(self.reference, self.package, [self.response], self.classes)

    def test_originals_and_server_adaptations_are_reported_separately(self):
        self.source("Client.java", "package android.app; class Client {}")
        self.source("Server.java", "package com.android.server.pm; class Server {}",
                    "package com.android.server.pm; class Server { int host; }")
        self.paths.append(self.paths[0])
        report = self.report()
        self.assertTrue(report["passed"])
        self.assertEqual(report["unchanged"], 1)
        self.assertEqual(report["modified_servers"], ["Server.java"])
        self.assertEqual(len(report["classes_jar"]["sha256"]), 64)

    def test_comments_and_renames_cannot_disguise_modified_clients(self):
        self.source("Client.java", "package android.app; class Client {}",
                    "/* package com.android.server.pm; */\npackage android.app; class Client { int host; }")
        self.source("Renamed.kt", "package android.app\nclass Renamed",
                    'package com.android.server.pm\nclass Renamed')
        report = self.report()
        self.assertFalse(report["passed"])
        self.assertEqual(report["modified_clients"], ["Client.java", "Renamed.kt"])
        self.assertEqual(report["modified_servers"], [])

    def test_missing_reference_is_a_failure(self):
        self.source("Client.java", "package android.app; class Client {}")
        (self.reference / "Client.java").unlink()
        report = self.report()
        self.assertFalse(report["passed"])
        self.assertEqual(report["missing_reference"], ["Client.java"])

    def test_owned_client_outside_package_is_a_failure(self):
        self.source("Client.java", "package android.app; class Client {}")
        host = self.root / "Adapter.kt"
        host.write_text("package dev.artzig\nclass Adapter")
        owned = self.root / "Replacement.kt"
        owned.write_text("package com.android.internal\nclass Replacement")
        self.paths.extend([host, owned])
        report = self.report()
        self.assertFalse(report["passed"])
        self.assertEqual(report["owned_clients"], [str(owned)])
        self.assertEqual(report["host_sources"], [str(host)])

    def test_empty_selection_and_self_comparison_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "no sources"):
            self.report()
        with self.assertRaisesRegex(ValueError, "independent"):
            identity.compare(self.package, self.package, [self.response], self.classes)

    def test_cli_writes_failed_report_and_returns_failure(self):
        self.source("Client.java", "package android.app; class Client {}",
                    "package android.app; class Client { int changed; }")
        self.report()
        output = self.root / "report.json"
        result = subprocess.run([sys.executable, "-B", str(SCRIPT),
            "--reference", str(self.reference), "--package", str(self.package),
            "--sources", str(self.response), "--classes", str(self.classes),
            "--output", str(output)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(json.loads(output.read_text())["passed"])


if __name__ == "__main__":
    unittest.main()
