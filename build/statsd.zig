//! Stock statsd: the session's telemetry daemon, built from its AOSP main with
//! its libkll, libsysutils, libprotoutil and libincident closure. As in the
//! credential stack it is a separate process; it does not link the island.
const std = @import("std");
const sources = @import("gen/statsd_sources.zig");
const keystore = @import("keystore.zig");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    aosp: LazyPath,
    aidl_tool: *std.Build.Step.Compile,
    stats_tool: *std.Build.Step.Compile,
    /// Runs Soong's prebuilt aconfig (build/generators.zig).
    runner: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    protobuf: *std.Build.Step.Compile,
    binder: *std.Build.Step.Compile,
    ndk: *std.Build.Step.Compile,
    hidl: *std.Build.Step.Compile,
};

const root = "packages/modules/StatsD";

const statsd_aidl = [_][]const u8{
    "android/os/IPendingIntentRef.aidl",        "android/os/IPullAtomCallback.aidl",
    "android/os/IPullAtomResultReceiver.aidl",  "android/os/IStatsCompanionService.aidl",
    "android/os/IStatsd.aidl",                  "android/os/IStatsQueryCallback.aidl",
    "android/os/IStatsSubscriptionCallback.aidl", "android/os/StatsDimensionsValueParcel.aidl",
    "android/os/StatsSubscriptionCallbackReason.aidl", "android/util/PropertyParcel.aidl",
    "android/util/StatsEventParcel.aidl",
};

const incident_aidl = [_][]const u8{ "IIncidentDumpCallback.aidl", "IIncidentManager.aidl", "IIncidentReportStatusListener.aidl" };

/// libsysutils' socket server, libcutils' reserved-socket client and server,
/// and the stock support closure; each entry is a root and its files.
const support = [_]struct { root: []const u8, files: []const []const u8 }{
    .{ .root = root ++ "/lib/libkll", .files = &.{ "compactor_stack.cpp", "encoding/encoder.cpp", "encoding/varint.cpp", "kll.cpp", "sampler.cpp" } },
    .{ .root = root ++ "/lib/libstatssocket", .files = &.{ "stats_buffer_writer.cpp", "stats_buffer_writer_queue.cpp", "stats_socket_loss_reporter.cpp", "statsd_writer.cpp", "utils.cpp" } },
    .{ .root = root ++ "/lib/libstatspull", .files = &.{"stats_pull_atom_callback.cpp"} },
    .{ .root = "system/core/libutils", .files = &.{"JenkinsHash.cpp"} },
    .{ .root = "system/core/libsysutils/src", .files = &.{ "SocketClient.cpp", "SocketListener.cpp" } },
    .{ .root = "system/core/libcutils", .files = &.{ "socket_local_client_unix.cpp", "socket_local_server_unix.cpp", "sockets.cpp", "sockets_unix.cpp" } },
    .{ .root = "frameworks/base/libs/protoutil/src", .files = &.{ "EncodedBuffer.cpp", "ProtoFileReader.cpp", "ProtoOutputStream.cpp", "ProtoReader.cpp", "protobuf.cpp" } },
    .{ .root = "frameworks/base/libs/incident/src", .files = &.{ "IncidentReportArgs.cpp", "incident_report.cpp" } },
    .{ .root = "frameworks/native/libs/incidentcompanion/src", .files = &.{"IncidentManager.cpp"} },
    .{ .root = "system/server_configurable_flags/libflags", .files = &.{"server_configurable_flags.cc"} },
};

const includes = [_][]const u8{
    root ++ "/statsd",
    root ++ "/statsd/src",
    root ++ "/lib/libkll",
    root ++ "/lib/libkll/include",
    root ++ "/lib/libstatssocket",
    root ++ "/lib/libstatssocket/include",
    root ++ "/lib/libstatspull/include",
    "external/googletest/googletest/include",
    "external/protobuf/src",
    "frameworks/base/libs/incident/include",
    "frameworks/base/libs/incident/include_priv",
    "frameworks/native/libs/incidentcompanion/include",
    "frameworks/base/libs/protoutil/include",
    "frameworks/libs/modules-utils/build/include",
    "system/core/libcutils/include",
    "system/core/libsysutils/include",
    "system/core/libutils/include",
    "system/server_configurable_flags/libflags/include",
};

/// statslog_statsd, the generated atom writers statsd reports its own state with.
fn statslog(b: *std.Build, o: Options, headers: *std.Build.Step.WriteFile, generated: *std.Build.Step.WriteFile) void {
    const stage = @import("stats_tool.zig").templates(b, o.aosp);
    for ([_]struct { name: []const u8, module: []const u8, namespace: []const u8 }{
        .{ .name = "statslog_statsd", .module = "statsd", .namespace = "android,os,statsd,util" },
        .{ .name = "stats_statsdsocketlog", .module = "statsdsocket", .namespace = "android,os,statsdsocket" },
    }) |log| {
        const run = b.addRunArtifact(o.stats_tool);
        run.setCwd(stage);
        run.addArgs(&.{ "--module", log.module, "--namespace", log.namespace, "--importHeader", b.fmt("{s}.h", .{log.name}), "--header" });
        _ = headers.addCopyFile(run.addOutputFileArg(b.fmt("{s}.h", .{log.name})), b.fmt("{s}.h", .{log.name}));
        run.addArg("--cpp");
        _ = generated.addCopyFile(run.addOutputFileArg(b.fmt("{s}.cpp", .{log.name})), b.fmt("{s}.cpp", .{log.name}));
    }
}

/// statsd_flags_c_lib in force-read-only mode: the session has no aconfig
/// storage, so each flag keeps its declared value.
fn flags(b: *std.Build, o: Options, headers: *std.Build.Step.WriteFile, generated: *std.Build.Step.WriteFile) void {
    const package = "com.android.os.statsd.flags";
    const cache = b.addRunArtifact(o.runner);
    cache.addFileArg(o.aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    cache.addArgs(&.{ "create-cache", "--package", package, "--container", "com.android.os.statsd" });
    for (@import("generator_sources.zig").input_dispatcher_stats_flags) |file| {
        cache.addArg("--declarations");
        cache.addFileArg(o.aosp.path(b, file));
    }
    cache.addArg("--cache");
    const flags_cache = cache.addOutputFileArg("flags.pb");
    const run = b.addRunArtifact(o.runner);
    run.addFileArg(o.aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    run.addArgs(&.{ "create-cpp-lib", "--cache" });
    run.addFileArg(flags_cache);
    run.addArgs(&.{ "--mode", "force-read-only", "--out" });
    const output = run.addOutputDirectoryArg("flags");
    _ = headers.addCopyDirectory(output.path(b, "include"), "", .{});
    _ = generated.addCopyFile(output.path(b, "com_android_os_statsd_flags.cc"), "com_android_os_statsd_flags.cc");
}

pub fn build(b: *std.Build, o: Options) LazyPath {
    const headers = b.addWriteFiles();
    const generated = b.addWriteFiles();
    // Isolate these bionic headers from the rest of its libc include tree.
    for ([_][]const u8{ "android/api-level.h", "bits/get_device_api_level_inlines.h", "sys/system_properties.h" }) |header|
        _ = headers.addCopyFile(o.aosp.path(b, b.fmt("bionic/libc/include/{s}", .{header})), header);
    statslog(b, o, headers, generated);
    flags(b, o, headers, generated);

    const proto = b.addRunArtifact(o.protoc);
    proto.addPrefixedDirectoryArg("-I", o.aosp);
    proto.addPrefixedDirectoryArg("-I", o.aosp.path(b, "external/protobuf/src"));
    const proto_out = proto.addPrefixedOutputDirectoryArg("--cpp_out=lite:", "protos");
    for (sources.protos) |file| proto.addFileArg(o.aosp.path(b, b.fmt("{s}/statsd/{s}", .{ root, file })));
    for ([_][]const u8{ "aggregator.proto", "kll-quantiles.proto" }) |name| {
        const kll = b.addRunArtifact(o.protoc);
        kll.addPrefixedDirectoryArg("-I", o.aosp.path(b, root ++ "/lib/libkll/proto"));
        const output = kll.addPrefixedOutputDirectoryArg("--cpp_out=lite:", "protos");
        kll.addFileArg(o.aosp.path(b, b.fmt("{s}/lib/libkll/proto/{s}", .{ root, name })));
        _ = headers.addCopyDirectory(output, "", .{});
        _ = generated.addCopyDirectory(output, "", .{});
    }

    const ndk_aidl = b.addRunArtifact(o.aidl_tool);
    ndk_aidl.addArg("--lang=ndk");
    ndk_aidl.addPrefixedDirectoryArg("-I", o.aosp.path(b, root ++ "/aidl"));
    ndk_aidl.addArg("-h");
    const ndk_headers = ndk_aidl.addOutputDirectoryArg("headers");
    ndk_aidl.addArg("-o");
    const ndk_sources = ndk_aidl.addOutputDirectoryArg("sources");
    for (statsd_aidl) |file| ndk_aidl.addFileArg(o.aosp.path(b, b.fmt("{s}/aidl/{s}", .{ root, file })));

    const incident = b.addRunArtifact(o.aidl_tool);
    incident.addArg("--lang=cpp");
    incident.addPrefixedDirectoryArg("-I", o.aosp.path(b, "frameworks/base/core/java"));
    incident.addPrefixedDirectoryArg("-I", o.aosp.path(b, "frameworks/native/libs/incidentcompanion/binder"));
    incident.addArg("-h");
    const incident_headers = incident.addOutputDirectoryArg("headers");
    incident.addArg("-o");
    const incident_sources = incident.addOutputDirectoryArg("sources");
    for (incident_aidl) |file| incident.addFileArg(o.aosp.path(b, b.fmt("frameworks/base/core/java/android/os/{s}", .{file})));

    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    mod.addIncludePath(b.path("src/native/binder/include"));
    for (@import("binder_cpp.zig").includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for (includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for (keystore.ndk_includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addIncludePath(headers.getDirectory());
    mod.addIncludePath(proto_out);
    mod.addIncludePath(proto_out.path(b, root ++ "/statsd"));
    mod.addIncludePath(ndk_headers);
    mod.addIncludePath(incident_headers);

    const cpp = std.mem.concat(b.allocator, []const u8, &.{ keystore.cppFlags(b), &keystore.bionic_header_defines, &.{ "-Wno-non-c-typedef-for-linkage", "-Wno-inconsistent-missing-override", "-include", b.pathResolve(&.{"src/native/stats/statsd_compat.h"}) } }) catch @panic("oom");
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, root ++ "/statsd"), .files = &sources.sources, .flags = cpp });
    for (support) |group| mod.addCSourceFiles(.{ .root = o.aosp.path(b, group.root), .files = group.files, .flags = cpp });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, root ++ "/lib/libstatssocket"), .files = &.{ "stats_event.c", "stats_socket.c" }, .flags = &.{ "-std=gnu11", "-D_GNU_SOURCE", "-D__ANDROID_HOST__" } });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "bionic/libc/bionic/get_device_api_level.cpp"), .language = .c, .flags = &.{ "-std=c11", "-D_Nonnull=" } });
    mod.addCSourceFiles(.{ .root = generated.getDirectory(), .files = &.{ "statslog_statsd.cpp", "stats_statsdsocketlog.cpp", "com_android_os_statsd_flags.cc", "aggregator.pb.cc", "kll-quantiles.pb.cc" }, .flags = cpp });
    mod.addCSourceFiles(.{ .root = proto_out.path(b, root ++ "/statsd/src"), .files = &.{
        "active_config_list.pb.cc",  "experiment_ids.pb.cc",  "shell/shell_config.pb.cc", "stats_policy_config.pb.cc",
        "statsd_config.pb.cc",       "statsd_metadata.pb.cc", "guardrail/stats_log_enums.pb.cc", "uid_data.pb.cc",
    }, .flags = cpp });
    for (statsd_aidl) |file| mod.addCSourceFile(.{ .file = ndk_sources.path(b, b.fmt("{s}.cpp", .{file[0 .. file.len - ".aidl".len]})), .flags = cpp });
    for (incident_aidl) |file| mod.addCSourceFile(.{ .file = incident_sources.path(b, b.fmt("android/os/{s}.cpp", .{file[0 .. file.len - ".aidl".len]})), .flags = cpp });
    mod.addCSourceFile(.{ .file = b.path("src/native/stats/data_paths.c"), .flags = &.{ "-std=gnu11", "-D_GNU_SOURCE" } });

    mod.linkLibrary(b.addLibrary(.{ .name = "statsd_bionic_properties", .root_module = b.createModule(.{
        .root_source_file = b.path("src/native/credential/bionic_properties.zig"),
        .target = o.target,
        .optimize = .ReleaseFast,
        .pic = true,
    }) }));
    mod.linkLibrary(o.protobuf);
    mod.linkLibrary(o.hidl);
    mod.linkLibrary(o.binder);
    mod.linkLibrary(o.ndk);
    mod.linkSystemLibrary("sqlite3", .{});
    for (keystore.daemon_rpaths) |rpath| mod.addRPathSpecial(rpath);
    const exe = b.addExecutable(.{ .name = "statsd", .root_module = mod });
    b.step("statsd", "Build stock statsd").dependOn(&exe.step);
    return exe.getEmittedBin();
}
