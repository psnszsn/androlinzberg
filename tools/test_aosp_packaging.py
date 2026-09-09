#!/usr/bin/env python3
"""Archive contracts used by Zig's local file dependencies."""
import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "aosp_packager", Path(__file__).resolve().parents[1] / "aosp/package-artzig-aosp.py"
)
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)


class ArchiveTest(unittest.TestCase):
    def test_source_roots_permissions_links_and_reproducibility(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "checkout"
            (source / "frameworks/base").mkdir(parents=True)
            (source / "tools").mkdir()
            data = source / "frameworks/base/source.java"
            data.write_bytes(b"original AOSP input\n")
            executable = source / "tools/compiler"
            executable.write_bytes(b"#!/bin/sh\nexit 0\n")
            link = source / "tools/source-link"
            link.symlink_to("../frameworks/base/source.java")
            paths = ["frameworks/base/source.java", "tools/compiler", "tools/source-link"]
            previous = packager.AOSP_ROOT
            try:
                packager.AOSP_ROOT = source
                data.chmod(0o664)
                executable.chmod(0o775)
                first = root / "first.tar.gz"
                packager.write_archive(first, paths)
                data.chmod(0o644)
                executable.chmod(0o755)
                second = root / "second.tar.gz"
                packager.write_archive(second, paths)
            finally:
                packager.AOSP_ROOT = previous
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with tarfile.open(first) as archive:
                self.assertEqual(archive.extractfile(paths[0]).read(), data.read_bytes())
                self.assertEqual(archive.getmember(paths[0]).mode, 0o644)
                self.assertEqual(archive.getmember(paths[1]).mode, 0o755)
                self.assertEqual(archive.getmember(paths[2]).linkname, "../frameworks/base/source.java")
                for entry in archive:
                    self.assertIn(entry.name.split("/")[0], {"frameworks", "tools"})
                    self.assertEqual((entry.uid, entry.gid, entry.mtime), (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
