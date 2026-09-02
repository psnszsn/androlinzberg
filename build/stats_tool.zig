//! Build stats-log-api-gen and its compiled-in atoms.proto descriptor closure.
const std = @import("std");

pub const Options = struct {
    aosp: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
};

const proto_list = @embedFile("../tools/stats-proto-list.txt");

fn protoPaths(b: *std.Build) []const []const u8 {
    var paths = std.ArrayList([]const u8).empty;
    var lines = std.mem.tokenizeScalar(u8, proto_list, '\n');
    while (lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        const prefix = "aosp|";
        if (!std.mem.startsWith(u8, line, prefix)) @panic("stats proto input must be sourced from aosp");
        paths.append(b.allocator, line[prefix.len..]) catch @panic("OOM");
    }
    return paths.items;
}

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const protos = protoPaths(b);
    const generate = b.addSystemCommand(&.{
        "bash",         "-c",
        \\exec "$@" 2> >(grep -F -v 'frameworks/proto_logging/stats/atoms/display/display_atoms.proto:22:1: warning: Import frameworks/proto_logging/stats/enums/display/display_enums.proto is unused.' >&2)
        ,
        "stats-protoc",
    });
    generate.addArtifactArg(o.protoc);
    generate.addPrefixedDirectoryArg("-I", o.aosp);
    generate.addPrefixedDirectoryArg("-I", o.aosp.path(b, "external/protobuf/src"));
    const generated = generate.addPrefixedOutputDirectoryArg("--cpp_out=", "stats-proto-cpp");
    for (protos) |path| generate.addFileArg(o.aosp.path(b, path));
    generate.setName("generate stats atoms protobuf C++ closure");

    const mod = b.createModule(.{
        .target = o.target,
        .optimize = .ReleaseFast,
        .link_libc = true,
        .link_libcpp = true,
    });
    const exe = b.addExecutable(.{ .name = "stats-log-api-gen", .root_module = mod });
    const stats = o.aosp.path(b, "frameworks/proto_logging/stats/stats_log_api_gen");
    const libbase = o.aosp.path(b, "system/libbase");
    const liblog = o.aosp.path(b, "system/logging/liblog");
    const fmt = o.aosp.path(b, "external/fmtlib");
    const protobuf = o.aosp.path(b, "external/protobuf");
    inline for (.{
        stats,
        generated,
        libbase.path(b, "include"),
        liblog.path(b, "include"),
        o.aosp.path(b, "system/core/libcutils/include"),
        fmt.path(b, "include"),
        protobuf.path(b, "src"),
        protobuf.path(b, "config"),
    }) |include| mod.addIncludePath(include);

    const flags: []const []const u8 = &.{
        "-std=c++20",
        "-w",
        "-D_FILE_OFFSET_BITS=64",
        "-DWITH_VENDOR",
        "-DJAVA_INCLUDE_SRCS_DIR=\"java/\"",
        "-DCC_INCLUDE_HDRS_DIR=\"cc_hdrs/\"",
        "-DCC_INCLUDE_SRCS_DIR=\"cc_srcs/\"",
        "-include",
        b.pathResolve(&.{"build/gen/fmt-cstdlib-prefix.h"}),
    };
    mod.addCSourceFiles(.{ .root = stats, .files = &.{
        "Collation.cpp",
        "settings_provider.cpp",
        "utils.cpp",
        "java_writer.cpp",
        "java_writer_q.cpp",
        "java_writer_vendor.cpp",
        "main.cpp",
        "native_writer.cpp",
        "native_writer_vendor.cpp",
        "rust_writer.cpp",
    }, .flags = flags });

    var generated_cc = std.ArrayList([]const u8).empty;
    for (protos) |path| {
        const stem = path[0 .. path.len - ".proto".len];
        generated_cc.append(b.allocator, b.fmt("{s}.pb.cc", .{stem})) catch @panic("OOM");
    }
    mod.addCSourceFiles(.{ .root = generated, .files = generated_cc.items, .flags = flags });
    mod.addCSourceFiles(.{ .root = libbase, .files = &.{
        "abi_compatibility.cpp", "chrono_utils.cpp", "cmsg.cpp",        "file.cpp",
        "hex.cpp",               "logging.cpp",      "mapped_file.cpp", "parsebool.cpp",
        "parsenetaddress.cpp",   "process.cpp",      "result.cpp",      "stringprintf.cpp",
        "strings.cpp",           "threads.cpp",      "errors_unix.cpp",
    }, .flags = flags });
    mod.addCSourceFiles(.{ .root = libbase, .files = &.{"posix_strerror_r.cpp"}, .flags = &.{
        "-std=c++20",    "-w",                        "-D_FILE_OFFSET_BITS=64",
        "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE",
    } });
    mod.addCSourceFiles(.{ .root = liblog, .files = &.{
        "logger_write.cpp",    "logger_name.cpp", "log_event_list.cpp",
        "log_event_write.cpp", "log_time.cpp",    "logprint.cpp",
        "properties.cpp",
    }, .flags = flags });
    mod.addCSourceFiles(.{ .root = fmt.path(b, "src"), .files = &.{
        "format.cc", "os.cc",
    }, .flags = flags });
    mod.linkLibrary(o.protobuf);
    return exe;
}
