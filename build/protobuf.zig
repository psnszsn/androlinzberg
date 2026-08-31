//! Build the vendored protobuf (artzig/vendor/external/protobuf, 3.21.12) as a
//! static library with zig c++, the same technique as build/skia.zig. Building
//! it ourselves means libprotobuf and every consumer (the sysprop_java /
//! javastream host generators) share one toolchain and one C++ stdlib (libc++):
//! a nixpkgs protobuf is libstdc++ and will not link against zig-built (libc++)
//! tool code. Source lists come from build/gen/protobuf_sources.zig, generated
//! from the protobuf cmake source lists by tools/gen-protobuf-sources.py.
const std = @import("std");
const gen = @import("gen/protobuf_sources.zig");

pub const Options = struct {
    /// artzig/vendor/external/protobuf
    root: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode = .ReleaseFast,
    /// also compile libprotoc (the compiler library; needed by javastream).
    with_protoc: bool = false,
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    const lib = b.addLibrary(.{ .name = "protobuf", .linkage = .static, .root_module = mod });
    const src = o.root.path(b, "src");
    mod.addIncludePath(src);
    mod.addIncludePath(o.root.path(b, "config")); // AOSP's generated config.h

    const flags: []const []const u8 = &.{ "-std=c++17", "-w" };
    var files = std.ArrayList([]const u8).empty;
    // Skip gzip_stream.cc: it needs <zlib.h> and the host generators do not use
    // gzip streams.
    for (gen.libprotobuf) |s| {
        if (std.mem.indexOf(u8, s, "gzip_stream") != null) continue;
        files.append(b.allocator, s) catch @panic("oom");
    }
    if (o.with_protoc) files.appendSlice(b.allocator, &gen.libprotoc) catch @panic("oom");
    mod.addCSourceFiles(.{ .root = src, .files = files.items, .flags = flags });
    return lib;
}
