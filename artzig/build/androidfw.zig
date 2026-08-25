//! Build libandroidfw (AOSP's resources.arsc / asset manager library) and its
//! dependencies with Zig's clang. This is the pilot for compiling AOSP C++
//! outside Soong; flags mirror what Soong passes for host x86_64 (see
//! out/compile_commands.json after `SOONG_GEN_COMPDB=1`).
//!
//! Sources: vendor/ (libbase, liblog, libcutils, libutils, libziparchive,
//! fmtlib) and extra/ (frameworks-base/libs/androidfw, frameworks-native
//! headers, incremental_delivery/incfs/util). System libs: z, png.
const std = @import("std");

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    vendor: std.Build.LazyPath,
    extra: std.Build.LazyPath,
};

// Common flags Soong uses for host C++ in AOSP. -fno-exceptions/-fno-rtti are
// the AOSP defaults; libandroidfw itself is built that way upstream too.
const cxx_common = [_][]const u8{
    "-std=gnu++20",
    "-DANDROID",
    "-DANDROID_STRICT",
    "-D__ANDROID_HOST__",
    "-D__compiler_offsetof=__builtin_offsetof",
    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",
    "-fno-exceptions",
    "-fno-rtti",
    "-fno-strict-aliasing",
    "-ffp-contract=off",
    "-Wno-unused",
    "-Wno-sign-compare",
    "-Wno-deprecated-declarations",
    "-Wno-inconsistent-missing-override",
    "-Wno-gnu-include-next",
    "-Wno-missing-field-initializers",
    "-Wno-vla-cxx-extension",
    "-Wno-dynamic-class-memaccess",
    "-Wno-nontrivial-memcall",
    "-fPIC",
};

fn cxxFlags(b: *std.Build, extra: []const []const u8) []const []const u8 {
    var list: std.ArrayList([]const u8) = .empty;
    list.appendSlice(b.allocator, &cxx_common) catch @panic("OOM");
    // fmtlib's bundled copy predates libc++ dropping transitive <cstdlib>
    list.appendSlice(b.allocator, &.{ "-include", (b.root.join(b.allocator, "src/androidfw/shim/prelude.h") catch @panic("OOM")).toString(b.allocator) catch @panic("OOM") }) catch @panic("OOM");
    list.appendSlice(b.allocator, extra) catch @panic("OOM");
    return list.items;
}

fn addIncludes(mod: *std.Build.Module, root: std.Build.LazyPath, b: *std.Build, rels: []const []const u8) void {
    for (rels) |r| mod.addIncludePath(root.path(b, r));
}

const vendor_includes = [_][]const u8{
    "system/libbase/include",
    "system/logging/liblog/include",
    "system/core/include",
    "system/core/libcutils/include",
    "system/core/libprocessgroup/include",
    "system/core/libsystem/include",
    "system/core/libutils/include",
    "system/core/libutils/binder/include",
    "system/libziparchive/include",
    "system/libziparchive/incfs_support/include",
    "external/fmtlib/include",
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const lib = b.addLibrary(.{
        .name = "androidfw",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = o.target,
            .optimize = o.optimize,
            .link_libc = true,
            .link_libcpp = true,
        }),
    });
    const m = lib.root_module;
    m.addIncludePath(b.path("src/androidfw/shim"));
    addIncludes(m, o.vendor, b, &vendor_includes);
    m.addIncludePath(o.vendor.path(b, "system/libbase"));
    m.addIncludePath(o.vendor.path(b, "system/core/libutils"));
    m.addIncludePath(o.vendor.path(b, "system/core/libutils/binder"));
    m.addIncludePath(o.vendor.path(b, "system/logging/liblog"));
    m.addIncludePath(o.vendor.path(b, "system/core/libcutils"));
    m.addIncludePath(o.vendor.path(b, "system/libziparchive"));
    m.addIncludePath(o.extra.path(b, "frameworks-base/libs/androidfw/include"));
    m.addIncludePath(o.extra.path(b, "frameworks-base/libs/androidfw/include_pathutils"));
    m.addIncludePath(o.extra.path(b, "frameworks-native/include"));
    m.addIncludePath(o.extra.path(b, "incremental_delivery/incfs/util/include"));
    m.linkSystemLibrary("z", .{});
    m.linkSystemLibrary("png", .{});

    // fmtlib (whole_static_libs of libbase)
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "external/fmtlib"),
        .files = &.{ "src/format.cc", "src/os.cc" },
        .flags = cxxFlags(b, &.{}),
    });
    // libbase
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/libbase"),
        .files = &.{
            "abi_compatibility.cpp", "chrono_utils.cpp",  "cmsg.cpp",      "errors_unix.cpp",
            "file.cpp",              "hex.cpp",           "logging.cpp",   "mapped_file.cpp",
            "parsebool.cpp",         "parsenetaddress.cpp", "process.cpp",
            "properties.cpp",        "result.cpp",        "stringprintf.cpp", "strings.cpp",
            "threads.cpp",
        },
        .flags = cxxFlags(b, &.{}),
    });
    // posix_strerror_r.cpp must see the XSI strerror_r; zig defines _GNU_SOURCE for C++.
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/libbase"),
        .files = &.{"posix_strerror_r.cpp"},
        .flags = cxxFlags(b, &.{ "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L" }),
    });
    // liblog (host)
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/logging/liblog"),
        .files = &.{
            "log_event_list.cpp", "log_event_write.cpp", "logger_name.cpp",
            "logger_write.cpp",   "logprint.cpp",        "properties.cpp",
        },
        .flags = cxxFlags(b, &.{ "-DLIBLOG_LOG_TAG=1006", "-DSNET_EVENT_LOG_TAG=1397638484", "-DANDROID_DEBUGGABLE=0" }),
    });
    // libcutils (host subset)
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/core/libcutils"),
        .files = &.{
            "ashmem-host.cpp", "config_utils.cpp", "fs.cpp",      "hashmap.cpp",
            "iosched_policy.cpp", "load_file.cpp", "multiuser.cpp", "native_handle.cpp",
            "properties.cpp",  "record_stream.cpp", "sockets.cpp", "sockets_unix.cpp",
            "socket_inaddr_any_server_unix.cpp", "socket_local_client_unix.cpp",
            "socket_local_server_unix.cpp", "socket_network_client_unix.cpp",
            "str_parms.cpp",   "trace-host.cpp",
        },
        .flags = cxxFlags(b, &.{}),
    });
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/core/libcutils"),
        .files = &.{"strlcpy.c"},
        .flags = &.{ "-std=gnu17", "-DANDROID", "-fPIC" },
    });
    // libutils (without CallStack/ProcessCallStack → no libunwindstack)
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/core/libutils"),
        .files = &.{
            "FileMap.cpp",       "JenkinsHash.cpp",   "LightRefBase.cpp", "Looper.cpp",
            "NativeHandle.cpp",  "Printer.cpp",       "StopWatch.cpp",    "SystemClock.cpp",
            "Threads.cpp",       "Timers.cpp",        "Tokenizer.cpp",    "misc.cpp",
            "binder/Errors.cpp", "binder/RefBase.cpp", "binder/SharedBuffer.cpp",
            "binder/String16.cpp", "binder/String8.cpp", "binder/StrongPointer.cpp",
            "binder/Unicode.cpp", "binder/VectorImpl.cpp",
        },
        .flags = cxxFlags(b, &.{"-DANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION"}),
    });
    // libziparchive
    m.addCSourceFiles(.{
        .root = o.vendor.path(b, "system/libziparchive"),
        .files = &.{
            "zip_archive.cc", "zip_archive_stream_entry.cc", "zip_cd_entry_map.cc",
            "zip_error.cpp",  "zip_writer.cc", "incfs_support/signal_handling.cpp",
        },
        .flags = cxxFlags(b, &.{ "-DINCFS_SUPPORT_DISABLED=1", "-DZIPARCHIVE_DISABLE_CALLBACK_API=1", "-DZLIB_CONST", "-D_FILE_OFFSET_BITS=64" }),
    });
    // incfs util (map_ptr)
    m.addCSourceFiles(.{
        .root = o.extra.path(b, "incremental_delivery/incfs/util"),
        .files = &.{"map_ptr.cpp"},
        .flags = cxxFlags(b, &.{}),
    });
    // libandroidfw (host variant: no Backup*/CursorWindow, which need libbinder)
    m.addCSourceFiles(.{
        .root = o.extra.path(b, "frameworks-base/libs/androidfw"),
        .files = &.{
            "ApkAssets.cpp",      "ApkParsing.cpp",     "Asset.cpp",          "AssetDir.cpp",
            "AssetManager.cpp",   "AssetManager2.cpp",  "AssetsProvider.cpp", "AttributeResolution.cpp",
            "BigBuffer.cpp",      "BigBufferStream.cpp", "ChunkIterator.cpp", "ConfigDescription.cpp",
            "FileStream.cpp",     "Idmap.cpp",          "LoadedArsc.cpp",     "Locale.cpp",
            "LocaleData.cpp",     "LocaleDataLookup.cpp", "misc.cpp",         "NinePatch.cpp",
            "ObbFile.cpp",        "PosixUtils.cpp",     "Png.cpp",            "PngChunkFilter.cpp",
            "PngCrunch.cpp",      "ResourceTimer.cpp",  "ResourceTypes.cpp",  "ResourceUtils.cpp",
            "StreamingZipInflater.cpp", "StringPool.cpp", "TypeWrappers.cpp", "Util.cpp",
            "ZipFileRO.cpp",      "ZipUtils.cpp",       "PathUtils.cpp",
        },
        .flags = cxxFlags(b, &.{ "-std=gnu++2b", "-DSTATIC_ANDROIDFW_FOR_TOOLS" }),
    });
    // our stable C API (from art_standalone)
    m.addCSourceFiles(.{
        .root = b.path("src/androidfw"),
        .files = &.{"androidfw_c_api.cpp"},
        .flags = cxxFlags(b, &.{"-std=gnu++2b"}),
    });
    m.addIncludePath(b.path("src/androidfw/include"));
    m.addIncludePath(b.path("src/androidfw/shim"));
    lib.installHeader(b.path("src/androidfw/include/androidfw/androidfw_c_api.h"), "androidfw/androidfw_c_api.h");
    return lib;
}
