//! Build the AOSP `sysprop_java` host generator (system/tools/sysprop)
//! as a zig c++ executable, then run it from build/generators.zig to
//! generate the android.sysprop.* accessor classes — replacing the hand-written
//! sysprop stubs. No AOSP prebuilt exists for this tool, so we compile it from
//! source against packaged libbase/liblog/fmtlib and our zig-built libprotobuf
//! (one C++ stdlib, libc++, across the tool and the library — see build/protobuf.zig).
const std = @import("std");

pub const Options = struct {
    language: enum { java, cpp } = .java,
    /// Root of the packaged AOSP tree.
    vendor: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode = .ReleaseFast,
    /// the zig-built static libprotobuf (build/protobuf.zig).
    protobuf: *std.Build.Step.Compile,
    /// Zig-built protoc used to generate the tool's own message schema.
    protoc: *std.Build.Step.Compile,
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const v = o.vendor;
    const st = v.path(b, "system/tools/sysprop");
    const lb = v.path(b, "system/libbase");
    const ll = v.path(b, "system/logging/liblog");
    const fmt = v.path(b, "external/fmtlib");
    const pb = v.path(b, "external/protobuf");

    const protoc = b.addRunArtifact(o.protoc);
    protoc.addPrefixedDirectoryArg("-I", st);
    const pb_dir = protoc.addPrefixedOutputDirectoryArg("--cpp_out=", "sysprop-pb");
    protoc.addFileArg(st.path(b, "sysprop.proto"));
    protoc.setName("generate sysprop protobuf C++");

    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    const exe = b.addExecutable(.{ .name = if (o.language == .java) "sysprop_java" else "sysprop_cpp", .root_module = mod });

    inline for (.{
        st.path(b, "include"),  st,                    pb_dir,
        lb.path(b, "include"),  ll.path(b, "include"), v.path(b, "system/core/libcutils/include"),
        fmt.path(b, "include"), pb.path(b, "src"),     pb.path(b, "config"),
    }) |inc| mod.addIncludePath(inc);

    const flags: []const []const u8 = &.{
        "-std=c++20", "-w", "-D_FILE_OFFSET_BITS=64",
    };

    // sysprop tool + generated protobuf message code.
    mod.addCSourceFiles(.{ .root = st, .files = if (o.language == .java) &.{
        "JavaGen.cpp", "JavaMain.cpp", "CodeWriter.cpp", "Common.cpp",
    } else &.{
        "CppGen.cpp", "CppMain.cpp", "CodeWriter.cpp", "Common.cpp",
    }, .flags = flags });
    mod.addCSourceFiles(.{ .root = pb_dir, .files = &.{"sysprop.pb.cc"}, .flags = flags });

    // libbase host subset (no Windows/utf8; posix_strerror_r compiled separately).
    mod.addCSourceFiles(.{ .root = lb, .files = &.{
        "abi_compatibility.cpp", "chrono_utils.cpp", "cmsg.cpp",        "file.cpp",
        "hex.cpp",               "logging.cpp",      "mapped_file.cpp", "parsebool.cpp",
        "parsenetaddress.cpp",   "process.cpp",      "result.cpp",      "stringprintf.cpp",
        "strings.cpp",           "threads.cpp",      "errors_unix.cpp",
    }, .flags = flags });
    // posix_strerror_r.cpp wants the XSI (POSIX) strerror_r, not the _GNU_SOURCE one.
    mod.addCSourceFiles(.{ .root = lb, .files = &.{"posix_strerror_r.cpp"}, .flags = &.{
        "-std=c++20",    "-w",                        "-D_FILE_OFFSET_BITS=64",
        "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE",
    } });

    // liblog host subset (the __android_log_* API libbase logging links against).
    mod.addCSourceFiles(.{ .root = ll, .files = &.{
        "logger_write.cpp",    "logger_name.cpp", "log_event_list.cpp",
        "log_event_write.cpp", "log_time.cpp",    "logprint.cpp",
        "properties.cpp",
    }, .flags = flags });

    // fmtlib (classic build: format.cc + os.cc; fmt.cc is the C++20 module unit).
    // fmt/format.h uses malloc/free without including <cstdlib>; force it in.
    mod.addCSourceFiles(.{ .root = fmt.path(b, "src"), .files = &.{
        "format.cc", "os.cc",
    }, .flags = &.{ "-std=c++20", "-w", "-D_FILE_OFFSET_BITS=64", "-include", "build/gen/fmt-cstdlib-prefix.h" } });

    mod.linkLibrary(o.protobuf);
    return exe;
}
