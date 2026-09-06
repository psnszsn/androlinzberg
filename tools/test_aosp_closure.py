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


class SourceIndexTest(unittest.TestCase):
    def test_proto_outer_class_uses_declared_java_package(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "enums.proto").write_text('''syntax = "proto2";
package wire;
option java_package = "android.view";
option java_outer_classname = "ViewProtoEnums";
enum State { UNKNOWN = 0; }
''')
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            resolver = aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            fqn = "android.view.ViewProtoEnums"
            self.assertEqual("generated", resolver.node(fqn).kind)
            self.assertEqual("generated", aosp_closure.classify_stub(resolver, fqn)[0])
            rendered = aosp_closure.render_closure(resolver, [fqn], resolver.closure([fqn]))
            self.assertIn("F aosp enums.proto", rendered)
            self.assertIn('path = "enums.proto"', rendered)
            self.assertIn('"android/view/ViewProtoEnums.java"', rendered)
            self.assertFalse(resolver.index.lookup("wire.ViewProtoEnums"))

    def test_logtags_and_colliding_static_import_owners(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "seed").mkdir()
            (root / "seed/Caller.java").write_text("""package seed;
import static first.Flags.FIRST;
import static second.Flags.SECOND;
class Caller { void call() { EventLogTags.writeEvent(FIRST + SECOND); } }
""")
            (root / "seed/EventLogTags.logtags").write_text(
                "option java_package seed;\n100 event (Value|1)\n")
            for package in ("first", "second"):
                (root / package).mkdir()
                (root / package / "flags.aconfig").write_text(f'package: "{package}"\n')
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            resolver = aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            refs = resolver.refs_of("seed/Caller.java")
            self.assertIn("first.Flags", refs)
            self.assertIn("second.Flags", refs)
            self.assertIn("seed.EventLogTags", refs)
            closure = resolver.closure(["seed.Caller"])
            self.assertEqual("generated", closure["seed.EventLogTags"].kind)
            rendered = aosp_closure.render_closure(resolver, ["seed.Caller"], closure)
            self.assertIn("F aosp seed/EventLogTags.logtags", rendered)
            self.assertIn('output = "seed/EventLogTags.java"', rendered)

    def test_aidl_interface_after_nested_parcelable_forward_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "android/example/IExample.aidl"
            source.parent.mkdir(parents=True)
            source.write_text("""package android.example;
parcelable Device.Address;
interface IExample { void call(); }
""")
            manifest = root / "manifest.txt"
            sources = root / "sources.zig"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            resolver = aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            self.assertEqual("aidl-interface", resolver.node("android.example.IExample").kind)

    def test_live_aidl_excludes_frozen_versions_from_closure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            live = "hardware/interfaces/example/aidl/android/example/Props.aidl"
            frozen = "hardware/interfaces/example/aidl/aidl_api/android.example/1/android/example/Props.aidl"
            for path in (live, frozen):
                source = root / path
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package android.example; parcelable Props { int value; }")
            index = aosp_closure.SourceIndex(root, None)
            self.assertEqual(index.lookup("android.example.Props")["aidl"], [live])
            (root / live).unlink()
            index = aosp_closure.SourceIndex(root, None)
            self.assertEqual(index.lookup("android.example.Props")["aidl"], [frozen])

    def test_production_ravenwood_is_indexed_but_test_tree_is_not(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = root / "frameworks/base/core/java/com/android/internal/ravenwood"
            tests = root / "frameworks/base/ravenwood"
            production.mkdir(parents=True)
            tests.mkdir(parents=True)
            (production / "RavenwoodEnvironment.java").write_text(
                "package com.android.internal.ravenwood; public class RavenwoodEnvironment {}")
            (tests / "Test.java").write_text("package test; public class Test {}")
            index = aosp_closure.SourceIndex(root, None)
            self.assertIn("com.android.internal.ravenwood.RavenwoodEnvironment", index.by_fqn)
            self.assertNotIn("test.Test", index.by_fqn)


class SourceRulesTest(unittest.TestCase):
    def test_checkout_root_produces_buildable_specs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java_sources.zig"
            path.write_text('''
framework.addRoot("A", SourceFiles.Root.dependency(b, aosp, ""));
framework.addRoot("CJ", SourceFiles.Root.dependency(b, aosp, "frameworks/base/core/java"));
try c.file("$A/build/make/tools/aconfig/Reader.java");
''')
            rules = aosp_closure.SourceRules(path)
            source = "build/make/tools/aconfig/Reader.java"
            self.assertEqual(rules.expand("$A/" + source), source)
            self.assertEqual(rules.spec_for(source), "$A/" + source)
            self.assertEqual(rules.selected(source), (True, None))
            self.assertEqual(rules.spec_for("frameworks/base/core/java/android/app/Activity.java"),
                             "$CJ/android/app/Activity.java")


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
            (stubs / "seed/Factory.java").write_text(
                """package seed;
public class Factory {
    public Seed create() { return new Seed(); }
}
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
            self.assertIn(
                "existing host class seed.Factory instantiates abstract class seed.Seed",
                blockers,
            )
            self.assertEqual({"seed.Child", "seed.Factory"}, set(hosted.audit_by_fqn))

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
