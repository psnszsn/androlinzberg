//! Stock Linux Perfetto C SDK and its pinned generators, compiled with Zig/libc++.
const std = @import("std");
const sdk_sources = @import("gen/perfetto_sources.zig");
const declarations = @import("generator_sources.zig");

pub const Options = struct {
    root: std.Build.LazyPath,
    protobuf_root: std.Build.LazyPath,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
};

pub const Result = struct {
    sdk: *std.Build.Step.Compile,
    base: *std.Build.Step.Compile,
    headers: std.Build.LazyPath,
};

const flags = [_][]const u8{ "-std=c++17", "-fPIC", "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-fno-exceptions", "-fno-rtti", "-w" };

fn module(b: *std.Build, o: Options, headers: std.Build.LazyPath) *std.Build.Module {
    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    mod.addIncludePath(o.root);
    mod.addIncludePath(o.root.path(b, "include"));
    mod.addIncludePath(o.root.path(b, "src/profiling/memory/include"));
    mod.addIncludePath(headers);
    return mod;
}

fn addSources(b: *std.Build, mod: *std.Build.Module, root: std.Build.LazyPath, groups: []const sdk_sources.SourceGroup) void {
    for (groups) |group| {
        var options: std.ArrayList([]const u8) = .empty;
        options.appendSlice(b.allocator, &flags) catch @panic("OOM");
        for (group.defines) |define| options.append(b.allocator, b.fmt("-D{s}", .{define})) catch @panic("OOM");
        mod.addCSourceFiles(.{ .root = root, .files = group.files, .flags = options.items });
    }
}

pub fn build(b: *std.Build, o: Options) Result {
    const flag_inputs = b.addWriteFiles();
    const response = flag_inputs.add("perfetto-flags.rsp", b.fmt("--flags {s}\n", .{
        std.mem.join(b.allocator, " ", &declarations.perfetto_native_build_flags) catch @panic("OOM"),
    }));
    const flag_generator = b.addSystemCommand(&.{"python3"});
    flag_generator.addFileArg(o.root.path(b, "gn/write_buildflag_header.py"));
    flag_generator.addArg("--rsp");
    flag_generator.addFileArg(response);
    flag_generator.addArg("--out");
    const build_flags = flag_generator.addOutputFileArg("perfetto_build_flags.h");
    flag_generator.setName("generate stock Perfetto Linux build flags");

    const version_generator = b.addSystemCommand(&.{"python3"});
    version_generator.addFileArg(o.root.path(b, "tools/write_version_header.py"));
    version_generator.addArg("--changelog");
    version_generator.addFileArg(o.root.path(b, "CHANGELOG"));
    version_generator.addArgs(&.{ "--no_git", "--cpp_out" });
    const version = version_generator.addOutputFileArg("perfetto_version.gen.h");
    version_generator.setName("generate stock Perfetto version from pinned changelog");

    const base_headers = b.addWriteFiles();
    _ = base_headers.addCopyFile(build_flags, "perfetto_build_flags.h");
    const base_mod = module(b, o, base_headers.getDirectory());
    addSources(b, base_mod, o.root, &sdk_sources.base);
    const base = b.addLibrary(.{ .name = "perfetto_base", .linkage = .static, .root_module = base_mod });

    var plugins: std.StringHashMapUnmanaged(*std.Build.Step.Compile) = .empty;
    for (sdk_sources.plugins) |plugin| {
        const mod = module(b, o, base_headers.getDirectory());
        mod.addIncludePath(o.protobuf_root.path(b, "src"));
        mod.addIncludePath(o.protobuf_root.path(b, "config"));
        mod.addCSourceFile(.{ .file = o.root.path(b, plugin.source), .flags = &(flags ++ [_][]const u8{
            "-DNDEBUG", "-DPERFETTO_IMPLEMENTATION", "-DGOOGLE_PROTOBUF_NO_RTTI", "-DGOOGLE_PROTOBUF_NO_STATIC_INITIALIZER",
        }) });
        mod.linkLibrary(base);
        mod.linkLibrary(o.protobuf);
        const exe = b.addExecutable(.{ .name = plugin.name, .root_module = mod });
        plugins.put(b.allocator, plugin.name, exe) catch @panic("OOM");
    }

    const headers = b.addWriteFiles();
    _ = headers.addCopyFile(build_flags, "perfetto_build_flags.h");
    _ = headers.addCopyFile(version, "perfetto_version.gen.h");
    var generated_sources: std.ArrayList(std.Build.LazyPath) = .empty;
    for (declarations.perfetto_native_protos) |entry| {
        const run = b.addRunArtifact(o.protoc);
        run.addPrefixedFileArg("--plugin=protoc-gen-plugin=", plugins.get(entry.plugin).?.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg(b.fmt("--plugin_out={s}:", .{entry.options}), entry.plugin);
        run.addPrefixedDirectoryArg("-I", o.root);
        for (entry.inputs) |input| {
            run.addFileArg(o.root.path(b, input));
            const stem = input[0 .. input.len - ".proto".len];
            const header = b.fmt("{s}{s}.h", .{ stem, entry.suffix });
            _ = headers.addCopyFile(output.path(b, header), header);
            generated_sources.append(b.allocator, output.path(b, b.fmt("{s}{s}.cc", .{ stem, entry.suffix }))) catch @panic("OOM");
        }
        run.setName(b.fmt("generate stock Perfetto {s} messages", .{entry.plugin}));
    }
    const mod = module(b, o, headers.getDirectory());
    addSources(b, mod, o.root, &sdk_sources.sdk);
    for (generated_sources.items) |source| mod.addCSourceFile(.{
        .file = source,
        .flags = &(flags ++ [_][]const u8{ "-DNDEBUG", "-DPERFETTO_IMPLEMENTATION" }),
    });
    mod.linkLibrary(base);
    const sdk = b.addLibrary(.{ .name = "perfetto_c", .linkage = .static, .root_module = mod });
    return .{ .sdk = sdk, .base = base, .headers = headers.getDirectory() };
}

pub const Generated = @import("graphics.zig").Generated;

fn protoPlugin(b: *std.Build, aosp: std.Build.LazyPath, protobuf: *std.Build.Step.Compile, sdk_headers: std.Build.LazyPath, name: []const u8) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = b.graph.host, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    for ([_][]const u8{ "external/perfetto", "external/perfetto/include", "external/protobuf/src" }) |path| mod.addIncludePath(aosp.path(b, path));
    mod.addIncludePath(sdk_headers);
    const plugin_flags: []const []const u8 = &.{ "-std=c++17", "-DNDEBUG", "-fno-exceptions", "-fno-rtti", "-DGOOGLE_PROTOBUF_NO_RTTI", "-DGOOGLE_PROTOBUF_NO_STATIC_INITIALIZER" };
    const directory = if (std.mem.eql(u8, name, "ipc_plugin")) "ipc" else "protozero";
    mod.addCSourceFile(.{ .file = aosp.path(b, b.fmt("external/perfetto/src/{s}/protoc_plugin/{s}.cc", .{ directory, name })), .flags = plugin_flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "external/perfetto/src/base"), .files = &.{ "string_utils.cc", "logging.cc", "crash_keys.cc" }, .flags = plugin_flags });
    mod.linkLibrary(protobuf);
    return b.addExecutable(.{ .name = name, .root_module = mod });
}

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, protobuf: *std.Build.Step.Compile, protoc: *std.Build.Step.Compile, sdk_headers: std.Build.LazyPath) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    inline for (.{ "protozero_plugin", "cppgen_plugin", "ipc_plugin" }, .{ &declarations.perfetto_zero_protos, &declarations.perfetto_cpp_protos, &declarations.perfetto_ipc_protos }) |name, inputs| {
        const plugin = protoPlugin(b, aosp, protobuf, sdk_headers, name);
        const run = b.addRunArtifact(protoc);
        run.addPrefixedDirectoryArg("-I", aosp.path(b, "external/perfetto"));
        run.addPrefixedDirectoryArg("-I", aosp.path(b, "external/protobuf/src"));
        run.addPrefixedFileArg("--plugin=protoc-gen-plugin=", plugin.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg(if (std.mem.eql(u8, name, "protozero_plugin")) "--plugin_out=wrapper_namespace=pbzero:" else "--plugin_out=wrapper_namespace=gen:", "protos");
        for (inputs) |file| run.addFileArg(aosp.path(b, b.fmt("external/perfetto/{s}", .{file})));
        _ = headers.addCopyDirectory(output, "", .{});
        _ = sources.addCopyDirectory(output, "", .{});
    }
    // SF's protobuf declarations share schemas with protozero, but are not
    // linked into the client SDK, which uses the pinned lightweight cppgen.
    const cpp = b.addRunArtifact(protoc);
    cpp.addPrefixedDirectoryArg("-I", aosp.path(b, "external/perfetto"));
    cpp.addPrefixedDirectoryArg("-I", aosp.path(b, "external/protobuf/src"));
    const cpp_output = cpp.addPrefixedOutputDirectoryArg("--cpp_out=", "protos");
    for (declarations.perfetto_zero_protos) |file| cpp.addFileArg(aosp.path(b, b.fmt("external/perfetto/{s}", .{file})));
    _ = headers.addCopyDirectory(cpp_output, "", .{});
    _ = headers.addCopyFile(sdk_headers.path(b, "perfetto_build_flags.h"), "perfetto_build_flags.h");
    _ = headers.addCopyFile(sdk_headers.path(b, "perfetto_version.gen.h"), "perfetto_version.gen.h");
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
}

pub const cpp_flags: []const []const u8 = &.{ "-std=c++17", "-DNDEBUG", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" };

pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, generated: Generated) void {
    for ([_][]const u8{ "external/perfetto", "external/perfetto/include", "external/perfetto/src/profiling/memory/include" }) |path| mod.addIncludePath(aosp.path(b, path));
    mod.addIncludePath(generated.headers);
}
