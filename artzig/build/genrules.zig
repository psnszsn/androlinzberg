//! Hand-ported Soong genrules for the ART host build. Produces the generated
//! source/header artifacts that build/gen/art.zig consumes through its typed
//! `GeneratedArtifacts`, so no Soong build output is needed.
//!
//!  - operator_out : art/tools/generate_operator_out.py per unique header (exact files)
//!  - mterp        : art/runtime/interpreter/mterp/gen_mterp.py → mterp_x86_64ng.S
//!  - asm_defines  : compile asm_defines.cc to assembly with the module's own flags,
//!                   then art/tools/cpp-define-generator/make_header.py → asm_defines.h
//!  - aconfig      : vendored output of `aconfig create-cpp-lib` (src/gen/aconfig)
const std = @import("std");
const art = @import("gen/art.zig");

pub const Options = struct {
    vendor: std.Build.LazyPath,
    /// the art.zig Options used by asmDefinesFlags
    art_opts: art.Options,
    python: []const u8 = "python3",
};

pub fn build(b: *std.Build, o: Options) art.GeneratedArtifacts {
    var compiler_operators: []const std.Build.LazyPath = undefined;
    var dex2oat_operators: []const std.Build.LazyPath = undefined;
    var libartbase_operators: []const std.Build.LazyPath = undefined;
    var dexfile_operators: []const std.Build.LazyPath = undefined;
    var runtime_operators: []const std.Build.LazyPath = undefined;

    // --- operator_out: <key>/gensrcs/<header dir>/<name>.operator_out.cc
    for (art.operator_out) |op| {
        var outputs: std.ArrayList(std.Build.LazyPath) = .empty;
        var seen: std.StringHashMapUnmanaged(void) = .empty;
        for (op.headers) |hdr| {
            const entry = seen.getOrPut(b.allocator, hdr) catch @panic("OOM");
            if (entry.found_existing) continue;
            const run = b.addSystemCommand(&.{ o.python, "art/tools/generate_operator_out.py", op.local, hdr });
            run.setCwd(o.vendor);
            run.addFileInput(o.vendor.path(b, hdr));
            run.addFileInput(o.vendor.path(b, "art/tools/generate_operator_out.py"));
            // Python 3.14 warns about the pinned AOSP script's codecs.open().
            // Keep other warning classes and all stderr visible.
            run.setEnvironmentVariable("PYTHONWARNINGS", "ignore::DeprecationWarning");
            const stem = std.fs.path.stem(hdr);
            const out = run.captureStdOut(.{ .basename = b.fmt("{s}.operator_out.cc", .{stem}) });
            outputs.append(b.allocator, out) catch @panic("OOM");
        }
        const sources = outputs.toOwnedSlice(b.allocator) catch @panic("OOM");
        switch (op.group) {
            .compiler_operators => compiler_operators = sources,
            .dex2oat_operators => dex2oat_operators = sources,
            .libartbase_operators => libartbase_operators = sources,
            .dexfile_operators => dexfile_operators = sources,
            .runtime_operators => runtime_operators = sources,
        }
    }

    // --- mterp (x86_64ng): gen_mterp.py <out.S> <fragments...>
    const mterp = blk: {
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
        break :blk out_s;
    };

    // --- asm_defines.h
    const asm_defines = blk: {
        const cc = b.addSystemCommand(&.{ b.graph.zig_exe, "c++", "-S", "-o" });
        const asm_out = cc.addOutputFileArg("asm_defines.s");
        cc.addFileArg(o.vendor.path(b, "art/tools/cpp-define-generator/asm_defines.cc"));
        cc.addArgs(art.asmDefinesFlags(b, o.art_opts));
        art.addAsmDefinesInputs(b, o.art_opts, cc);
        cc.addArg("-Wno-everything");
        cc.addFileInput(o.vendor.path(b, "art/tools/cpp-define-generator/asm_defines.def"));

        const mk = b.addSystemCommand(&.{ o.python, "art/tools/cpp-define-generator/make_header.py" });
        mk.setCwd(o.vendor);
        mk.addFileArg(asm_out);
        break :blk mk.captureStdOut(.{ .basename = "asm_defines.h" });
    };

    // Pinned AOSP's x86 memory optimization is shared by the x86 and x86_64
    // backends, but its construction switch is guarded only by the former.
    // Apply the minimal source patch as a cacheable graph node rather than
    // enabling and compiling the unused 32-bit backend.
    const compiler_optimization = blk: {
        const run = b.addSystemCommand(&.{ "patch", "--batch", "--forward", "--quiet", "--output" });
        const output = run.addOutputFileArg("optimization.cc");
        run.addFileArg(o.vendor.path(b, "art/compiler/optimizing/optimization.cc"));
        run.addFileArg(b.path("build/patches/art-x86_64-memory-optimization.patch"));
        break :blk output;
    };

    // --- aconfig flag library: vendored
    return .{
        .aconfig = .{
            .sources = &.{b.path("src/gen/aconfig/com_android_art_flags.cc")},
            .include_roots = &.{b.path("src/gen/aconfig")},
        },
        .compiler_operators = .{ .sources = compiler_operators },
        .compiler_optimization = compiler_optimization,
        .dex2oat_operators = .{ .sources = dex2oat_operators },
        .libartbase_operators = .{ .sources = libartbase_operators },
        .dexfile_operators = .{ .sources = dexfile_operators },
        .runtime_operators = .{ .sources = runtime_operators },
        .mterp = mterp,
        .asm_defines = .{ .sources = &.{}, .include_roots = &.{asm_defines.dirname()} },
    };
}
