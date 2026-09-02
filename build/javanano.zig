//! Build the AOSP `protoc-gen-javanano` protoc plugin (the vendored protobuf
//! fork carries the generator under src/google/protobuf/compiler/javanano) as a
//! zig c++ executable. Run as a protoc plugin by build/generators.zig, it
//! generates the nano-runtime message classes
//! (com.android.internal.logging.nano.MetricsProto) — replacing the hand-written
//! stub whose "harvested" constant values were fabricated. Links the zig-built
//! libprotobuf+libprotoc (build/protobuf.zig, with_protoc).
const std = @import("std");

pub const Options = struct {
    /// external/protobuf in the root AOSP dependency.
    root: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode = .ReleaseFast,
    protobuf: *std.Build.Step.Compile,
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    const exe = b.addExecutable(.{ .name = "protoc-gen-javanano", .root_module = mod });
    mod.addIncludePath(o.root.path(b, "src"));
    mod.addIncludePath(o.root.path(b, "config"));
    mod.addCSourceFiles(.{ .root = o.root.path(b, "src/google/protobuf/compiler/javanano"), .files = &.{
        "javanano_enum.cc",    "javanano_enum_field.cc",    "javanano_extension.cc",
        "javanano_field.cc",   "javanano_file.cc",          "javanano_generator.cc",
        "javanano_helpers.cc", "javanano_main.cc",          "javanano_map_field.cc",
        "javanano_message.cc", "javanano_message_field.cc", "javanano_primitive_field.cc",
    }, .flags = &.{ "-std=c++17", "-w" } });
    mod.linkLibrary(o.protobuf);
    return exe;
}
