//! Hand-ported Soong genrules for the ART host build. Produces the generated
//! source/header directories that build/gen/art.zig consumes through
//! `Options.gen_dirs`, so no Soong build output is needed.
//!
//!  - operator_out : art/tools/generate_operator_out.py per header (one dir per module)
//!  - mterp        : art/runtime/interpreter/mterp/gen_mterp.py → mterp_x86_64ng.S
//!  - asm_defines  : compile asm_defines.cc to assembly with the module's own flags,
//!                   then art/tools/cpp-define-generator/make_header.py → asm_defines.h
//!  - aconfig      : vendored output of `aconfig create-cpp-lib` (src/gen/aconfig)
const std = @import("std");
const art = @import("gen/art.zig");

pub const Options = struct {
    vendor: std.Build.LazyPath,
    /// the art.zig Options (for asmDefinesFlags); gen_dirs in it are ignored here
    art_opts: art.Options,
    python: []const u8 = "python3",
};

pub fn build(b: *std.Build, o: Options) []const art.GenDir {
    var dirs: std.ArrayList(art.GenDir) = .empty;

    // --- operator_out: <key>/gensrcs/<header dir>/<name>.operator_out.cc
    for (art.operator_out) |op| {
        const wf = b.addWriteFiles();
        for (op.headers) |hdr| {
            const run = b.addSystemCommand(&.{ o.python, "art/tools/generate_operator_out.py", op.local, hdr });
            run.setCwd(o.vendor);
            run.addFileInput(o.vendor.path(b, hdr));
            run.addFileInput(o.vendor.path(b, "art/tools/generate_operator_out.py"));
            // Python 3.14 warns about the pinned AOSP script's codecs.open().
            // Keep other warning classes and all stderr visible.
            run.setEnvironmentVariable("PYTHONWARNINGS", "ignore::DeprecationWarning");
            const out = run.captureStdOut(.{});
            const name = b.fmt("gensrcs/{s}.operator_out.cc", .{hdr[0 .. hdr.len - 2]});
            _ = wf.addCopyFile(out, name);
        }
        dirs.append(b.allocator, .{ .key = op.key, .dir = wf.getDirectory() }) catch @panic("OOM");
    }

    // --- mterp (x86_64ng): gen_mterp.py <out.S> <fragments...>
    {
        const frags = [_][]const u8{
            "arithmetic.S", "array.S", "control_flow.S", "floating_point.S",
            "invoke.S",     "main.S",  "object.S",       "other.S",
        };
        const run = b.addSystemCommand(&.{ o.python, "art/runtime/interpreter/mterp/gen_mterp.py" });
        run.setCwd(o.vendor);
        run.addFileInput(o.vendor.path(b, "art/runtime/interpreter/mterp/gen_mterp.py"));
        const out_s = run.addOutputFileArg("mterp_x86_64ng.S");
        for (frags) |f| {
            const rel = b.fmt("art/runtime/interpreter/mterp/x86_64ng/{s}", .{f});
            run.addArg(rel);
            run.addFileInput(o.vendor.path(b, rel));
        }
        const wf = b.addWriteFiles();
        _ = wf.addCopyFile(out_s, "mterp_x86_64ng.S");
        dirs.append(b.allocator, .{ .key = "soong/.intermediates/art/runtime/libart_mterp.x86_64ng/gen", .dir = wf.getDirectory() }) catch @panic("OOM");
    }

    // --- asm_defines.h
    {
        const cc = b.addSystemCommand(&.{ b.graph.zig_exe, "c++", "-S", "-o" });
        const asm_out = cc.addOutputFileArg("asm_defines.s");
        cc.addFileArg(o.vendor.path(b, "art/tools/cpp-define-generator/asm_defines.cc"));
        cc.addArgs(art.asmDefinesFlags(b, o.art_opts));
        cc.addArg("-Wno-everything");
        cc.addFileInput(o.vendor.path(b, "art/tools/cpp-define-generator/asm_defines.def"));

        const mk = b.addSystemCommand(&.{ o.python, "art/tools/cpp-define-generator/make_header.py" });
        mk.setCwd(o.vendor);
        mk.addFileArg(asm_out);
        const hdr = mk.captureStdOut(.{});
        const wf = b.addWriteFiles();
        _ = wf.addCopyFile(hdr, "asm_defines.h");
        dirs.append(b.allocator, .{
            .key = "soong/.intermediates/art/tools/cpp-define-generator/cpp-define-generator-asm-support/linux_glibc_x86_64/gen",
            .dir = wf.getDirectory(),
        }) catch @panic("OOM");
    }

    // --- aconfig flag library: vendored
    dirs.append(b.allocator, .{
        .key = "soong/.intermediates/art/build/flags/art-aconfig-flags-lib/linux_glibc_x86_64_static/gen",
        .dir = b.path("src/gen/aconfig"),
    }) catch @panic("OOM");

    return dirs.items;
}
