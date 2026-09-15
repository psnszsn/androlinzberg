#!/usr/bin/env python3
"""Regression checks for native provenance gates, including misleading stock files."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("native_audit", Path(__file__).with_name("native-client-audit.py"))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class NativeAuditTest(unittest.TestCase):
    def test_command_paths_preserve_spaces_and_macro_arguments(self):
        model = {"tokens": [{"text": "zig"}, {"text": "-DVALUE=a b"},
                            {"path": 0, "prefix": "-I", "suffix": ""}],
                 "commands": [[0, 1, 2]]}
        self.assertEqual(audit.decode_commands(model, [Path("/a b/include")]),
                         [["zig", "-DVALUE=a b", "-I/a b/include"]])

    def test_stock_text_does_not_excuse_disabled_android_branch(self):
        name = audit.CORE + "android_hardware_SensorManager.cpp"
        self.assertTrue(audit.boundary_errors(name, {"macros": {"__ANDROID_HOST__": "1"}, "headers": []}, Path("/repo")))
        self.assertFalse(audit.boundary_errors(name, {"macros": {"__ANDROID__": "1"}, "headers": []}, Path("/repo")))

    def test_binder_facade_must_be_consumed_not_merely_on_search_path(self):
        repo = Path("/repo")
        name = audit.CORE + "android_util_Binder.cpp"
        wrong = [Path("/aosp/include/binder") / (name + ".h") for name in ("Binder", "Parcel", "IPCThreadState")]
        probe = {"macros": {}, "headers": wrong}
        self.assertEqual(len(audit.boundary_errors(name, probe, repo)), 3)
        probe["headers"] = [repo / "src/native/binder/include/binder" / path.name for path in wrong]
        self.assertFalse(audit.boundary_errors(name, probe, repo))

    def test_sources_headers_and_generated_inputs_have_distinct_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            package, reference = repo / "package", repo / "reference"
            package.mkdir(); reference.mkdir()
            source, original = package / "Client.cpp", reference / "Client.cpp"
            source.write_text("same"); original.write_text("same")
            self.assertEqual(audit.classify(source, package, reference, repo)["kind"], "unchanged")
            original.write_text("different")
            self.assertEqual(audit.classify(source, package, reference, repo)["kind"], "modified")
            original.unlink()
            self.assertEqual(audit.classify(source, package, reference, repo)["kind"], "missing_reference")
            generated = repo / ".zig-cache/generated.h"
            generated.parent.mkdir(); generated.write_text("generated")
            self.assertEqual(audit.classify(generated, package, reference, repo)["kind"], "generated")
            owned = repo / "Owned.cpp"; owned.write_text("host")
            self.assertEqual(audit.classify(owned, package, reference, repo)["kind"], "owned")

    def test_dependency_file_escaped_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            header = Path(temp) / "header with spaces.h"
            header.touch()
            escaped = str(header).replace(" ", "\\ ")
            self.assertEqual(audit.dependency_paths("audit: \\\n " + escaped + "\n"), [header])


if __name__ == "__main__":
    unittest.main()
