#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


sys.dont_write_bytecode = True
MODULE_PATH = Path(__file__).with_name("aosp-closure.py")
SPEC = importlib.util.spec_from_file_location("aosp_closure", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
aosp_closure = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(aosp_closure)


class MethodAbiTest(unittest.TestCase):
    def test_host_method_and_abstract_subclass_gaps_are_blockers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aosp = root / "aosp"
            host = root / "framework"
            stubs = root / "framework-stubs"
            classes = root / "classes"
            for directory in (aosp / "seed", aosp / "host", host / "host", stubs / "seed", classes / "java/lang"):
                directory.mkdir(parents=True, exist_ok=True)

            (aosp / "host/Target.java").write_text(
                """package host;
public class Target {
    public static Target create() { return null; }
    public boolean attach() { return true; }
    public void missing() {}
    public void missingChain() {}
}
"""
            )
            (host / "host/Target.java").write_text(
                """package host;
public class Target {
    public void attach() {}
}
"""
            )
            (aosp / "seed/Seed.java").write_text(
                """package seed;
import host.Target;
public abstract class Seed {
    public abstract void required();
    public Target getTarget() { return null; }
    public void exercise() {
        Target.create();
        Target target = null;
        target.missing();
        if (!target.attach()) {}
        getTarget().missingChain();
    }
}
"""
            )
            (stubs / "seed/Child.java").write_text(
                """package seed;
public class Child extends Seed {}
"""
            )
            # Classes only needs one entry to reject an accidentally empty live
            # build input; member validation reads the source fixtures above.
            (classes / "java/lang/Object.class").write_bytes(b"")
            manifest = root / "manifest.txt"
            sources = root / "sources.zig"
            manifest.write_text("")
            sources.write_text("")

            indexed = aosp_closure.SourceIndex(aosp, None)
            hosted = aosp_closure.Stubs(stubs, framework_root=host)
            resolver = aosp_closure.Resolver(
                aosp,
                indexed,
                aosp_closure.Manifest(manifest),
                aosp_closure.SourceRules(sources),
                aosp_closure.Classes([classes]),
                hosted,
            )

            resolver.refs_of("seed/Seed.java")
            blockers = resolver.parsed_abi_blockers["seed/Seed.java"]
            self.assertIn("existing host class host.Target lacks method create", blockers)
            self.assertIn("existing host class host.Target lacks method missing", blockers)
            self.assertIn("existing host class host.Target lacks method missingChain", blockers)
            self.assertIn(
                "existing host class host.Target method attach returns void, stock requires boolean",
                blockers,
            )
            self.assertIn(
                "existing host class seed.Child does not implement abstract method required",
                blockers,
            )
            self.assertEqual({"seed.Child"}, set(hosted.audit_by_fqn))

            resolver.nodes = {}
            closure = resolver.closure(["seed.Seed", "host.Target"], absent={"seed.Seed", "host.Target"})
            target_blockers = {
                blocker
                for node in closure.values()
                for blocker in node.abi_blockers
                if "host.Target" in blocker
            }
            self.assertEqual(set(), target_blockers)


if __name__ == "__main__":
    unittest.main()
