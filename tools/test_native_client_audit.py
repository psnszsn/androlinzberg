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
    def test_exact_patch_rejects_extra_client_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, compiled, patch = (root / name for name in ("input", "compiled", "change.patch"))
            original.write_text("before\n")
            compiled.write_text("after\n")
            patch.write_text("--- a/input\n+++ b/input\n@@ -1 +1 @@\n-before\n+after\n")
            self.assertTrue(audit.exact_patch_matches(original, compiled, patch))
            compiled.write_text("after\nextra\n")
            self.assertFalse(audit.exact_patch_matches(original, compiled, patch))
            self.assertEqual(original.read_text(), "before\n")

    def test_network_host_policy_branch_is_required(self):
        name = "system/netd/client/FwmarkClient.cpp"
        probe = {"macros": {}, "headers": []}
        self.assertTrue(audit.boundary_errors(name, probe, Path("/repo")))
        probe["macros"]["__ANDROID_HOST__"] = "1"
        self.assertEqual(audit.boundary_errors(name, probe, Path("/repo")), [])

    def test_exact_patch_selects_only_the_matching_translation_unit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, compiled, patch = (root / name for name in ("input", "compiled", "change.patch"))
            original.write_text("before\n")
            compiled.write_text("after\n")
            patch.write_text("--- a/other\n+++ b/other\n@@ -1 +1 @@\n-wrong\n+wrong2\n"
                             "diff --git a/input b/input\n"
                             "--- a/input\n+++ b/input\n@@ -1 +1 @@\n-before\n+after\n")
            self.assertTrue(audit.exact_patch_matches(original, compiled, patch))
            original.rename(root / "unlisted")
            self.assertFalse(audit.exact_patch_matches(root / "unlisted", compiled, patch))

    def test_rust_wrapper_records_declared_environment_only(self):
        command = ["/build-command", "--arg", "python3", "--arg", "/repo/tools/rust-compile.py",
                   "--arg", "/tmp/cache.d", "--arg", "/runner", "--arg", "/pinned/bin/rustc",
                   "--env", "CARGO_PKG_VERSION", "1.2.3", "--arg", "root.rs",
                   "--arg", "--cfg", "--arg", 'feature="a b"',
                   "--prefix", "--emit=link,dep-info=", "/a b/input.d"]
        argv, env = audit.native_rust_audit.unwrap(command)
        self.assertEqual(argv, ["/runner", "/pinned/bin/rustc", "root.rs", "--cfg",
                                'feature="a b"', "--emit=link,dep-info=/a b/input.d"])
        self.assertEqual(env, {"CARGO_PKG_VERSION": "1.2.3"})
        self.assertRaises(ValueError, audit.native_rust_audit.unwrap, command + ["--unknown"])

    def test_rust_dependencies_keep_spaces_generated_includes_and_env(self):
        text = "out.d: root.rs /generated/bindings.rs /path/with\\ space.rs\n\nroot.rs:\n# env-dep:OUT_DIR=/generated\n# env-dep:UNSET\n"
        paths, env = audit.native_rust_audit.dep_inputs(text)
        self.assertEqual(paths, ["/generated/bindings.rs", "/path/with space.rs", "root.rs"])
        self.assertEqual(env, {"OUT_DIR": "/generated", "UNSET": None})
        for invalid in ("", "out.d:", "# env-dep:ONLY=1"):
            self.assertRaises(ValueError, audit.native_rust_audit.dep_inputs, invalid)

    def test_rust_effective_cfg_query_does_not_recompile(self):
        command = ["/runner", "/rustc", "root.rs", "--emit=link,dep-info=out.d", "-o", "out.a",
                   "--cfg", 'feature="real"', "-Copt-level=2"]
        self.assertEqual(audit.native_rust_audit.print_command(command, "cfg"),
                         ["/runner", "/rustc", "root.rs", "--cfg", 'feature="real"', "-Copt-level=2", "--print=cfg"])

    def test_recorded_options_preserve_literal_quotes_and_spaces(self):
        import struct
        def elf_record(record):
            names = b'\0.shstrtab\0.GCC.command.line\0'
            payload = record + b'\0'
            data = bytearray(64 + 3 * 64)
            data[:64] = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01' + b'\0' * 10,
                                    1, 62, 1, 0, 0, 64, 0, 64, 0, 0, 64, 3, 1)
            for index, name, content in ((1, 1, names), (2, 11, payload)):
                struct.pack_into('<IIQQQQIIQQ', data, 64 + index * 64,
                                 name, 1, 0, 0, len(data), len(content), 0, 0, 1, 0)
                data += content
            return bytes(data)
        data = elf_record(b'/zig /a\\ b/source.cpp -D VALUE="a\\ b" -c -o /tmp/out.o')
        records = audit.command_records(data)
        self.assertEqual(records, [('', ['/zig', '/a b/source.cpp', '-D', 'VALUE="a b"', '-c', '-o', '/tmp/out.o'])])
        self.assertRaises(ValueError, audit.command_records, data[:-1])
        name = 'a_very_long_source_name.cpp.o'
        table = (name + '/\n').encode()
        def member(label, value):
            header = f'{label:<16}{0:<12}{0:<6}{0:<6}{0:<8}{len(value):<10}`\n'.encode()
            return header + value + (b'\n' if len(value) % 2 else b'')
        archive = b'!<arch>\n' + member('//', table) + member('/0', data)
        self.assertEqual(audit.command_records(archive), [(name, records[0][1])])
        self.assertRaises(ValueError, audit.command_records, archive[:-3])

    def test_explicit_source_language_and_bookkeeping_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "api level.cpp"
            source.touch()
            compiler = Path(directory) / "zig"
            command = [str(compiler), "-x", "c", str(source), "-D", 'VALUE="a b"',
                       "-MD", "-MV", "-MF", "old.d", "-c", "-o", "old.o"]
            path, replay = audit.replay_record(command, compiler)
            self.assertEqual(path, source.resolve())
            self.assertEqual(replay, [str(compiler), "clang", "-x", "c", str(source), "-D", 'VALUE="a b"'])

    def test_unreadable_records_do_not_pass_as_original(self):
        for data in (b'BC\xc0\xde', b'!<thin>\n', b'\x7fELF\x01\x01', b'not ELF'):
            with self.assertRaises(ValueError):
                audit.command_records(data)
        with self.assertRaises(ValueError):
            audit.replay_record(['/bin/untrusted', '/source.cpp', '-c', '-o', 'x'], Path('/compiler'))

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

    def test_thread_priority_uses_the_host_policy_below_original_jni(self):
        name = "system/core/libutils/Threads.cpp"
        self.assertTrue(audit.boundary_errors(name, {"macros": {}, "headers": []}, Path("/repo")))
        self.assertFalse(audit.boundary_errors(name, {"macros": {"setpriority": "azk_setpriority"}, "headers": []}, Path("/repo")))

    def test_process_and_account_files_keep_identity_below_original_jni(self):
        macros = {"__ANDROID__": "1", "getpwnam": "azk_android_getpwnam", "getgrnam": "azk_android_getgrnam",
                  "open": "azk_process_open", "fopen": "azk_process_fopen",
                  "setuid": "azk_process_setuid", "setgid": "azk_process_setgid"}
        name = audit.CORE + "android_util_Process.cpp"
        self.assertFalse(audit.boundary_errors(name, {"macros": macros, "headers": []}, Path("/repo")))
        del macros["setuid"]
        self.assertTrue(audit.boundary_errors(name, {"macros": macros, "headers": []}, Path("/repo")))
        name = "bionic/libc/bionic/grp_pwd_file.cpp"
        self.assertTrue(audit.boundary_errors(name, {"macros": {}, "headers": []}, Path("/repo")))
        self.assertFalse(audit.boundary_errors(name, {"macros": {"open": "azk_android_account_open"}, "headers": []}, Path("/repo")))

    def test_binder_facade_must_be_consumed_not_merely_on_search_path(self):
        repo = Path("/repo")
        name = audit.CORE + "android_util_Binder.cpp"
        wrong = [Path("/aosp/include/binder") / (name + ".h") for name in ("Binder", "Parcel", "IPCThreadState")]
        probe = {"macros": {}, "headers": wrong}
        self.assertEqual(len(audit.boundary_errors(name, probe, repo)), 3)
        probe["headers"] = [repo / "src/native/binder/include/binder" / path.name for path in wrong]
        self.assertFalse(audit.boundary_errors(name, probe, repo))

    def test_skia_buffer_source_cannot_silently_compile_an_empty_unix_branch(self):
        probe = {"macros": {"SK_BUILD_FOR_UNIX": "", "SK_VULKAN": "",
                            "__ANDROID_API__": "36"}, "headers": []}
        name = audit.SKIA_ANDROID_BUFFER
        self.assertTrue(audit.boundary_errors(name, probe, Path("/repo")))
        probe["macros"]["SK_BUILD_FOR_ANDROID"] = ""
        self.assertTrue(audit.boundary_errors(name, probe, Path("/repo")))
        del probe["macros"]["SK_BUILD_FOR_UNIX"]
        self.assertFalse(audit.boundary_errors(name, probe, Path("/repo")))

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

    def test_c_registrar_requires_exact_export_and_cpp_requires_arguments(self):
        self.assertTrue(audit.registrar_matches("register_android_graphics_classes", "register_android_graphics_classes"))
        self.assertFalse(audit.registrar_matches("register_android_graphics_classes_backup", "register_android_graphics_classes"))
        self.assertTrue(audit.registrar_matches("android::register_pdf(_JNIEnv*)", "android::register_pdf("))
        self.assertFalse(audit.registrar_matches("android::register_pdf_backup(_JNIEnv*)", "android::register_pdf("))

    def test_buffer_queue_caller_selection_requires_binder_branch_and_transport(self):
        repo = Path("/repo")
        source = "frameworks/native/libs/gui/BufferQueueThreadState.cpp"
        probe = {"macros": {}, "headers": [repo / "src/native/binder/include/binder/IPCThreadState.h"]}
        self.assertFalse(audit.boundary_errors(source, probe, repo))
        probe["macros"]["NO_BINDER"] = "1"
        self.assertTrue(audit.boundary_errors(source, probe, repo))
        probe["macros"].clear()
        probe["headers"].clear()
        self.assertTrue(audit.boundary_errors(source, probe, repo))

    def test_debug_requires_wide_allocator_counters_and_private_kernel_query(self):
        probe = {"macros": {"__ANDROID__": "1", "mallinfo": "mallinfo2",
                            "mallinfo2(...)": "azk_android_mallinfo(__VA_ARGS__)",
                            "mallopt": "azk_android_mallopt", "LoadKernelConfigs": "LoadDebugStackConfigs"},
                 "headers": []}
        name = audit.CORE + "android_os_Debug.cpp"
        self.assertFalse(audit.boundary_errors(name, probe, Path("/repo")))
        for key in ("mallinfo", "mallinfo2(...)", "LoadKernelConfigs"):
            invalid = {"macros": dict(probe["macros"]), "headers": []}
            del invalid["macros"][key]
            self.assertTrue(audit.boundary_errors(name, invalid, Path("/repo")))

    def test_dependency_file_escaped_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            header = Path(temp) / "header with spaces.h"
            header.touch()
            escaped = str(header).replace(" ", "\\ ")
            self.assertEqual(audit.dependency_paths("audit: \\\n " + escaped + "\n"), [header])


if __name__ == "__main__":
    unittest.main()
