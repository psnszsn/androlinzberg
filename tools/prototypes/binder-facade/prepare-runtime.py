#!/usr/bin/env python3
"""Build-only experiment: copy this worktree's runtime, never edit production sources."""

from pathlib import Path
import argparse
import shutil
import difflib
import subprocess
import hashlib
import json

p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
p.add_argument('aosp', type=Path)
a = p.parse_args()
repo = Path(__file__).resolve().parents[3]
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
shutil.copytree(repo / 'src/binder', out / 'binder', dirs_exist_ok=True)
f = out / 'binder/ndk.zig'
original = f.read_text()
s = original
# The bounded fix owns outgoing Binder entries until the Parcel is destroyed.
# Includes the existing internal range-copy and reset paths; broader overwrite,
# incoming delivery accounting and failed export rollback still need a production audit.
s = s.replace('    fd: fd_t = -1,\n};', '    fd: fd_t = -1,\n    binder: ?*AIBinder = null,\n};', 1)
s = s.replace(
    '        for (p.entries.items) |e| if (e.fd >= 0) closeFd(e.fd);',
    '        for (p.entries.items) |e| {\n            if (e.fd >= 0) closeFd(e.fd);\n            if (e.binder) |b| AIBinder_decStrong(b);\n        }',
)
s = s.replace(
    '                p.entries.append(alloc, copy)',
    '                if (copy.binder) |b| AIBinder_incStrong(b);\n                p.entries.append(alloc, copy)',
)
s = s.replace(
    '    if (binder) |b| {\n        parcel.writeI32(1);',
    '    if (binder) |b| {\n        AIBinder_incStrong(b); // retained through send/reply, independent of caller wrappers\n        parcel.writeI32(1);',
    1,
)
s = s.replace(
    '.kind = .binder_local, .value = id })', '.kind = .binder_local, .value = id, .binder = b })', 1
)
s = s.replace(
    '.kind = .binder_remote, .value = b.id })', '.kind = .binder_remote, .value = b.id, .binder = b })', 1
)
s += """
// Private prototype seam; not a new public NDK API.
export fn AZKPrototype_Parcel_clone(p: *const AParcel) callconv(.c) *AParcel {
    const copy = AParcel.create();
    copy.appendRange(@constCast(p), 0, p.data.items.len);
    return copy;
}
"""
# Broker subscriptions are per peer/object, while NDK observers are per recipient.
old = """    r.mu.unlock(g_io);
    if (!found) return STATUS_NAME_NOT_FOUND;
    var body: [@sizeOf(ObjectRef)]u8 = undefined;
    @memcpy(&body, std.mem.asBytes(&ObjectRef.remote(binder.id)));
    r.sendFrame(.{ .tag = Client.Message.Tag.unlink_death, .bytes_len = body.len }, &body, &.{}) catch {};"""
new = """    var remaining = false;
    for (r.death_links.items) |l| {
        if (l.global_id == binder.id) remaining = true;
    }
    r.mu.unlock(g_io);
    if (!found) return STATUS_NAME_NOT_FOUND;
    if (!remaining) {
        var body: [@sizeOf(ObjectRef)]u8 = undefined;
        @memcpy(&body, std.mem.asBytes(&ObjectRef.remote(binder.id)));
        r.sendFrame(.{ .tag = Client.Message.Tag.unlink_death, .bytes_len = body.len }, &body, &.{}) catch {};
    }"""
assert old in s
s = s.replace(old, new, 1)
assert s != original
f.write_text(s)
(out / 'runtime.patch').write_text(
    ''.join(
        difflib.unified_diff(
            original.splitlines(True),
            s.splitlines(True),
            fromfile='src/binder/ndk.zig',
            tofile='prototype/binder/ndk.zig',
        )
    )
)
shutil.copy(a.aosp / 'libnativehelper/include_jni/jni.h', out / 'jni.h')
(out / 'art.zig').write_text('pub const jni = @import("jni_c");\n')
(out / 'build.zig').write_text("""const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const tc = b.addTranslateC(.{ .root_source_file = b.path("jni.h"), .target = target, .optimize = optimize });
    const art = b.createModule(.{ .root_source_file = b.path("art.zig"), .target = target, .optimize = optimize });
    art.addImport("jni_c", tc.createModule());
    const lib = b.addLibrary(.{ .name = "binder_ndk", .linkage = .dynamic, .root_module = b.createModule(.{
        .root_source_file = b.path("binder/ndk.zig"), .target = target, .optimize = optimize, .link_libc = true,
        .imports = &.{.{ .name = "art", .module = art }},
    }) });
    b.installArtifact(lib);
    const broker = b.addExecutable(.{ .name = "azk-binder-broker", .root_module = b.createModule(.{
        .root_source_file = b.path("binder/broker_main.zig"), .target = target, .optimize = optimize,
    }) });
    b.installArtifact(broker);
    const ndk_test = b.addExecutable(.{ .name = "azk-ndk-test", .root_module = b.createModule(.{
        .root_source_file = b.path("binder/ndk_test.zig"), .target = target, .optimize = optimize, .link_libc = true,
    }) });
    ndk_test.root_module.linkLibrary(lib);
    const run = b.addRunArtifact(ndk_test);
    run.addFileArg(broker.getEmittedBin());
    b.step("ndk-test", "Run the existing native ABI regression against the isolated build").dependOn(&run.step);
}
""")
(out / 'source-provenance.json').write_text(
    json.dumps(
        {
            'repo_commit': subprocess.check_output(
                ['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True
            ).strip(),
            'ndk_source_sha256': hashlib.sha256(original.encode()).hexdigest(),
            'broker_source_sha256': hashlib.sha256((out / 'binder/Broker.zig').read_bytes()).hexdigest(),
        },
        indent=2,
    )
)
print(out)
