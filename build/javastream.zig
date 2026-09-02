//! Build the AOSP `protoc-gen-javastream` protoc plugin
//! (frameworks-base/tools/streaming_proto) as a zig c++ executable. Run as a
//! protoc plugin by build/generators.zig, it generates the
//! framework `*Proto` message classes (android.util.proto stream style) —
//! replacing the hand-written *Proto stubs. Links our zig-built libprotoc
//! (build/protobuf.zig, with_protoc), one libc++ stdlib across tool and library.
const std = @import("std");

pub const Options = struct {
    /// frameworks/base/tools/streaming_proto in the root AOSP dependency.
    root: std.Build.LazyPath,
    /// external/protobuf in the root AOSP dependency.
    protobuf_src: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode = .ReleaseFast,
    /// the zig-built static libprotobuf+libprotoc (build/protobuf.zig, with_protoc).
    protobuf: *std.Build.Step.Compile,
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    const exe = b.addExecutable(.{ .name = "protoc-gen-javastream", .root_module = mod });
    mod.addIncludePath(o.root);
    mod.addIncludePath(o.protobuf_src.path(b, "src"));
    mod.addIncludePath(o.protobuf_src.path(b, "config"));
    const flags: []const []const u8 = &.{ "-std=c++17", "-w", "-DSTATIC_ANDROIDFW_FOR_TOOLS" };
    mod.addCSourceFiles(.{ .root = o.root, .files = &.{
        "java/main.cpp",
        "java/java_proto_stream_code_generator.cpp",
        "Errors.cpp",
        "stream_proto_utils.cpp",
        "string_utils.cpp",
    }, .flags = flags });
    mod.linkLibrary(o.protobuf);
    return exe;
}
