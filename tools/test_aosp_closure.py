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


class AidlIncludesTest(unittest.TestCase):
    CONFIG = '''fn addAidlIncludes(run: anytype, aosp: anytype, compat: anytype) void {
    // run.addPrefixedDirectoryArg("-I", aosp.path("commented/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("direct/aidl"));
    inline for (.{
        "loop/aidl",
        // "disabled/aidl",
    }) |path| run.addPrefixedDirectoryArg("-I", aosp.path(path));
    run.addPrefixedDirectoryArg("-I", compat);
}
fn aidlJava(b: anytype, aosp: anytype) void {
    const permission_aidl = "staged/aidl/";
    const unused = "unused/aidl";
    _ = compat.addCopyDirectory(aosp.path(permission_aidl), "", .{});
    _ = compat.addCopyDirectory(aosp.path("literal/aidl"), "", .{});
    _ = compat.addCopyDirectory(b.path("aidl-compat"), "", .{});
}
fn nativeCode(run: anytype, aosp: anytype) void {
    run.addPrefixedDirectoryArg("-I", aosp.path("native/include"));
}
'''

    def test_only_configured_aidl_search_and_staging_roots_are_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            generators = root / "generators.zig"
            generators.write_text(self.CONFIG)
            expected = {"direct/aidl", "loop/aidl", "staged/aidl", "literal/aidl"}
            self.assertEqual(expected, set(aosp_closure.aidl_include_roots(generators)))
            for directory, name in (("direct/aidl", "Direct"), ("loop/aidl", "Loop"),
                                    ("staged/aidl", "Staged"), ("literal/aidl", "Literal"),
                                    ("unconfigured/aidl", "Missing")):
                source = root / directory / f"example/{name}.aidl"
                source.parent.mkdir(parents=True)
                source.write_text(f"package example; interface {name} {{ void call(); }}")
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes"
            (classes / "java/lang").mkdir(parents=True)
            (classes / "java/lang/Object.class").write_bytes(b"")

            def resolver():
                return aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                    aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                    aosp_closure.Classes([classes]), aosp_closure.Stubs(root / "stubs"), generators)

            configured = resolver()
            for name in ("Direct", "Loop", "Staged", "Literal"):
                self.assertTrue(configured.node(f"example.{name}").aidl_root)
            self.assertFalse(configured.node("example.Missing").aidl_root)
            generators.write_text(self.CONFIG.replace('"loop/aidl"', '"replacement/aidl"'))
            self.assertFalse(resolver().node("example.Loop").aidl_root)

    def test_repository_roots_include_audio_and_exported_connectivity(self) -> None:
        roots = aosp_closure.aidl_include_roots(aosp_closure.REPO / "build/generators.zig")
        for root in ("frameworks/av/media/libaudioclient/aidl", "frameworks/base/media/aidl",
                     "frameworks/av/aidl", "system/hardware/interfaces/media/aidl",
                     "packages/modules/Connectivity/framework/aidl-export",
                     "frameworks/native/libs/permission/aidl"):
            self.assertIn(root, roots)

    def test_unreadable_or_unrecognized_configuration_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "generators.zig"
            with self.assertRaises(FileNotFoundError):
                aosp_closure.aidl_include_roots(config)
            for text in ("", self.CONFIG.replace("addAidlIncludes", "renamed"),
                         self.CONFIG.replace('const permission_aidl = "staged/aidl/";', "")):
                config.write_text(text)
                with self.assertRaises(ValueError):
                    aosp_closure.aidl_include_roots(config)


class SourceIndexTest(unittest.TestCase):
    def test_source_packages_can_differ_within_one_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "utils/Helper.java": "package example.utils; class Helper {}",
                "utils/Context.java": "package example; class Context {}",
                "utils/Api.aidl": "package wire; interface Api {}",
                "utils/Client.kt": "package client\nclass Client",
            }
            for name, declaration in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(declaration)
            cache = root / "index.json"
            for index in (aosp_closure.SourceIndex(root, cache),
                          aosp_closure.SourceIndex(root, cache)):
                for fqn, kind, path in (
                    ("example.utils.Helper", "java", "utils/Helper.java"),
                    ("example.Context", "java", "utils/Context.java"),
                    ("wire.Api", "aidl", "utils/Api.aidl"),
                    ("client.Client", "kt", "utils/Client.kt"),
                ):
                    self.assertEqual({kind: [path]}, index.lookup(fqn))
                self.assertEqual({}, index.lookup("example.utils.Context"))

    def test_proto_multiple_files_and_default_outer_name_collision(self) -> None:
        declaration = '''syntax = "proto2";
package wire;
option java_package = "org.chromium.net.httpflags";
option java_multiple_files = true;
message FlagValue { message ConstrainedValue { optional bool value = 1; } }
message Flags { map<string, FlagValue> flags = 1; }
service HiddenService {}
'''
        names = aosp_closure.proto_java_names("flags.proto", declaration)
        self.assertEqual({"FlagsOuterClass", "Flags", "FlagsOrBuilder", "FlagValue", "FlagValueOrBuilder"},
                         {name.rsplit(".", 1)[1] for name in names})
        self.assertTrue(all(name.startswith("org.chromium.net.httpflags.") for name in names))
        self.assertEqual(["org.chromium.net.httpflags.FlagsOuterClass"],
                         aosp_closure.proto_java_names("flags.proto", declaration.replace(
                             "java_multiple_files = true", "java_multiple_files = false")))

    def test_cronet_uses_the_pinned_stable_module_without_mixing_tot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for variant, names in (("stable", ("Engine",)), ("tot", ("Engine", "FutureEngine"))):
                for name in names:
                    path = root / f"external/cronet/{variant}/java/org/chromium/net/{name}.java"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(f"package org.chromium.net; public class {name} {{}}")
            index = aosp_closure.SourceIndex(root, None)
            self.assertEqual({"java": ["external/cronet/stable/java/org/chromium/net/Engine.java"]},
                             index.lookup("org.chromium.net.Engine"))
            self.assertEqual({}, index.lookup("org.chromium.net.FutureEngine"))

    def test_khronos_apis_are_not_assumed_to_be_boot_classes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "javax/microedition/khronos/egl").mkdir(parents=True)
            (root / "javax/microedition/khronos/egl/EGLConfig.class").write_bytes(b"")
            classes = aosp_closure.Classes([root])
            self.assertTrue(classes.has("javax.crypto.Cipher"))
            self.assertTrue(classes.has("javax.microedition.khronos.egl.EGLConfig"))
            self.assertFalse(classes.has("javax.microedition.khronos.egl.EGLContext"))
            self.assertFalse(classes.has("javax.microedition.khronos.opengles.GL11"))
            self.assertTrue(classes.has("android.system.Os"))
            self.assertFalse(classes.has("android.system.virtualmachine.VirtualizationFrameworkInitializer"))
            self.assertFalse(classes.has("android.system.virtualizationservice.IVirtualizationService"))
            self.assertFalse(classes.has("android.system.virtualizationcommon.DeathReason"))

    def test_wildcard_import_closure_includes_legacy_egl_and_static_owner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, text in {
                "seed/Renderer.java": """package seed;
import javax.microedition.khronos.egl.*;
import static config.Flags.*;
class Renderer { EGLContext context; int flag = ENABLED; }
""",
                "javax/microedition/khronos/egl/EGLContext.java": """
package javax.microedition.khronos.egl;
public class EGLContext { backend.EGLImpl implementation; }
""",
                "backend/EGLImpl.java": "package backend; public class EGLImpl {}",
                "config/Flags.java": "package config; public class Flags { public static int ENABLED; }",
            }.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            resolver = aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            closure = resolver.closure(["seed.Renderer"])
            self.assertEqual({"seed.Renderer", "javax.microedition.khronos.egl.EGLContext",
                              "backend.EGLImpl", "config.Flags"}, set(closure))
            self.assertTrue(all(node.kind == "java" for node in closure.values()))

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
            self.assertEqual("proto generator (build/generator_sources.zig)",
                             resolver.node(fqn).generated)
            rendered = aosp_closure.render_closure(resolver, [fqn], resolver.closure([fqn]))
            self.assertIn("F aosp enums.proto", rendered)
            self.assertIn('path = "enums.proto"', rendered)
            self.assertIn('"android/view/ViewProtoEnums.java"', rendered)
            self.assertFalse(resolver.index.lookup("wire.ViewProtoEnums"))

    def test_multifile_proto_is_found_from_same_package_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "stats.proto").write_text('''syntax = "proto2";
package wire;
option java_package = "usage";
option java_multiple_files = true;
// message Fake {}
message IntervalStatsProto { message Event { optional string text = 1 [default = "}"]; } }
message OtherStats { optional int32 count = 1; }
''')
            (root / "Caller.java").write_text(
                "package usage; class Caller { long value = IntervalStatsProto.Event.TEXT; }")
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            index = aosp_closure.SourceIndex(root, None)
            resolver = aosp_closure.Resolver(root, index,
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            closure = resolver.closure(["usage.Caller"])
            self.assertEqual("generated", closure["usage.IntervalStatsProto"].kind)
            rendered = aosp_closure.render_closure(resolver, ["usage.Caller"], closure)
            self.assertIn('path = "stats.proto"', rendered)
            self.assertIn('"usage/IntervalStatsProto.java"', rendered)
            self.assertTrue(index.lookup("usage.OtherStats"))
            for absent in ("usage.Event", "usage.Fake", "wire.IntervalStatsProto"):
                self.assertFalse(index.lookup(absent))

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

    def test_generic_aidl_parcelable_includes_field_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "android/example"
            directory.mkdir(parents=True)
            (directory / "Queue.aidl").write_text("""package android.example;
import android.example.Grantor;
@VintfStability parcelable Queue<T, Flavor> { Grantor[] grantors; int quantum; }
""")
            (directory / "Grantor.aidl").write_text(
                "package android.example;\nparcelable Grantor { int fdIndex; }")
            manifest, sources = root / "manifest", root / "sources"
            manifest.write_text("")
            sources.write_text("")
            classes = root / "classes/java/lang"
            classes.mkdir(parents=True)
            (classes / "Object.class").write_bytes(b"")
            resolver = aosp_closure.Resolver(root, aosp_closure.SourceIndex(root, None),
                aosp_closure.Manifest(manifest), aosp_closure.SourceRules(sources),
                aosp_closure.Classes([root / "classes"]), aosp_closure.Stubs(root / "stubs"))
            closure = resolver.closure(["android.example.Queue"])
            self.assertEqual("aidl-structured", closure["android.example.Queue"].kind)
            self.assertIn("android.example.Grantor", closure)
            rendered = aosp_closure.render_closure(resolver, ["android.example.Queue"], closure)
            self.assertIn('path = "android/example/Queue.aidl"', rendered)
            self.assertIn('path = "android/example/Grantor.aidl"', rendered)

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
            annotations = root / "frameworks/libs/modules-utils/java/android/ravenwood/annotation"
            tests = root / "frameworks/base/ravenwood"
            production.mkdir(parents=True)
            annotations.mkdir(parents=True)
            tests.mkdir(parents=True)
            (production / "RavenwoodEnvironment.java").write_text(
                "package com.android.internal.ravenwood; public class RavenwoodEnvironment {}")
            (annotations / "RavenwoodKeep.java").write_text(
                "package android.ravenwood.annotation; public @interface RavenwoodKeep {}")
            (tests / "Test.java").write_text("package test; public class Test {}")
            index = aosp_closure.SourceIndex(root, None)
            self.assertIn("com.android.internal.ravenwood.RavenwoodEnvironment", index.by_fqn)
            self.assertIn("android.ravenwood.annotation.RavenwoodKeep", index.by_fqn)
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
            # Production indexes all repository-owned JVM sources in src/java.
            host = root / "src/java"
            stubs = host
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
            hosted = aosp_closure.Stubs(host)
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
            self.assertEqual({"host.Target", "seed.Child", "seed.Factory"},
                             set(hosted.by_fqn))

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
