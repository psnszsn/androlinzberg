//! Build the pinned AOSP AIDL compiler (system/tools/aidl) as a zig c++ host
//! executable. Every AIDL backend the build uses (Java, cpp, ndk, rust) runs
//! this compiler instead of the generic-linux SDK prebuilt, and the repo-owned
//! Zig backend in tools/aidl-zig is linked into the same executable so that it
//! consumes the compiler's validated AST rather than re-parsing AIDL.
//!
//! The lexer and parser come from flex and bison in the development shell; the
//! remaining closure is the packaged libbase/liblog/fmtlib/googletest, as in
//! build/sysprop.zig.
const std = @import("std");

pub const Options = struct {
    /// Root of the packaged AOSP tree.
    vendor: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode = .ReleaseFast,
};

/// libaidl-common from system/tools/aidl/Android.bp, minus the flex/bison inputs.
const compiler_sources = [_][]const u8{
    "aidl_checkapi.cpp",   "aidl_const_expressions.cpp", "aidl_dumpapi.cpp",
    "aidl_language.cpp",   "aidl_to_common.cpp",         "aidl_to_cpp_common.cpp",
    "aidl_to_cpp.cpp",     "aidl_to_java.cpp",           "aidl_to_ndk.cpp",
    "aidl_to_rust.cpp",    "aidl_typenames.cpp",         "aidl.cpp",
    "ast_java.cpp",        "check_valid.cpp",            "code_writer.cpp",
    "comments.cpp",        "diagnostics.cpp",            "generate_aidl_mappings.cpp",
    "generate_cpp.cpp",    "generate_cpp_analyzer.cpp",  "generate_java_binder.cpp",
    "generate_java.cpp",   "generate_ndk.cpp",           "generate_rust.cpp",
    "import_resolver.cpp", "io_delegate.cpp",            "location.cpp",
    "logging.cpp",         "options.cpp",                "parser.cpp",
    "permission.cpp",      "preprocess.cpp",
};

/// Repo-owned entry point and Zig backend (tools/aidl-zig).
const zig_backend_sources = [_][]const u8{ "main.cpp", "generate_zig.cpp" };

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const v = o.vendor;
    const st = v.path(b, "system/tools/aidl");
    const lb = v.path(b, "system/libbase");
    const ll = v.path(b, "system/logging/liblog");
    const fmt = v.path(b, "external/fmtlib");
    const gtest = v.path(b, "external/googletest/googletest");

    // Soong's yacc rule with gen_location_hh/gen_position_hh: bison writes
    // location.hh and position.hh beside the parser it generates.
    const bison = b.addSystemCommand(&.{ "bison", "-Wno-deprecated" });
    const parser_header = bison.addPrefixedOutputFileArg("--defines=", "aidl_language_y.h");
    const parser = bison.addPrefixedOutputFileArg("--output=", "aidl_language_y.cpp");
    bison.addFileArg(st.path(b, "aidl_language_y.yy"));
    bison.setName("generate AIDL parser");

    const flex = b.addSystemCommand(&.{"flex"});
    const lexer = flex.addPrefixedOutputFileArg("--outfile=", "aidl_language_l.cpp");
    flex.addFileArg(st.path(b, "aidl_language_l.ll"));
    flex.setName("generate AIDL lexer");

    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
    });
    const exe = b.addExecutable(.{ .name = "aidl", .root_module = mod });

    inline for (.{
        st,                     st.path(b, "include"),    parser_header.dirname(),
        lb.path(b, "include"),  ll.path(b, "include"),    v.path(b, "system/core/libcutils/include"),
        fmt.path(b, "include"), gtest.path(b, "include"), gtest,
    }) |inc| mod.addIncludePath(inc);

    // Android 16 (the pinned android-16.0.0_r1 platform); only `--help` prints it.
    const flags: []const []const u8 = &.{
        "-std=c++20", "-w", "-D_FILE_OFFSET_BITS=64", "-DPLATFORM_SDK_VERSION=36",
    };

    mod.addCSourceFiles(.{ .root = st, .files = &compiler_sources, .flags = flags });
    mod.addCSourceFile(.{ .file = parser, .flags = flags });
    mod.addCSourceFile(.{ .file = lexer, .flags = flags });
    mod.addCSourceFiles(.{ .root = b.path("tools/aidl-zig"), .files = &zig_backend_sources, .flags = flags });

    // libbase host subset (posix_strerror_r compiled separately).
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

    // fmt/format.h uses malloc/free without including <cstdlib>; force it in.
    mod.addCSourceFiles(.{ .root = fmt.path(b, "src"), .files = &.{
        "format.cc", "os.cc",
    }, .flags = &.{ "-std=c++20", "-w", "-D_FILE_OFFSET_BITS=64", "-include", "build/gen/fmt-cstdlib-prefix.h" } });

    // aidl_checkapi.cpp and the AST use gtest assertions and FRIEND_TEST.
    mod.addCSourceFiles(.{ .root = gtest.path(b, "src"), .files = &.{"gtest-all.cc"}, .flags = flags });

    return exe;
}

pub const ZigInterfaces = struct {
    /// `-I` root that holds `files`.
    root: std.Build.LazyPath,
    /// Further `-I` roots for imported declarations.
    includes: []const std.Build.LazyPath = &.{},
    /// Structured AIDL declarations below `root`, one defined type per file.
    /// Every structured type they import must be listed too.
    files: []const []const u8,
    /// src/binder/aidl.zig.
    runtime: *std.Build.Module,
    /// A frozen interface version and its API hash, as Soong passes them.
    version: ?struct { number: u32, hash: []const u8 } = null,
    /// Supplies `@"<canonical name>"` for each unstructured parcelable used.
    custom: ?*std.Build.Module = null,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
};

/// A module of Zig generated by `aidl --lang=zig`. It exposes every listed
/// type under its canonical AIDL name, e.g. `@"android.os.IServiceManager"`.
pub fn zigModule(b: *std.Build, aidl_tool: *std.Build.Step.Compile, o: ZigInterfaces) *std.Build.Module {
    const run = b.addRunArtifact(aidl_tool);
    run.addArg("--lang=zig");
    if (o.version) |version| run.addArgs(&.{ "--structured", b.fmt("--version={d}", .{version.number}), b.fmt("--hash={s}", .{version.hash}) });
    run.addPrefixedDirectoryArg("-I", o.root);
    for (o.includes) |include| run.addPrefixedDirectoryArg("-I", include);
    run.addArg("-o");
    const generated = run.addOutputDirectoryArg("aidl-zig");

    var root: std.ArrayList(u8) = .empty;
    for (o.files) |file| {
        run.addFileArg(o.root.path(b, file));
        const stem = file[0 .. file.len - ".aidl".len];
        const canonical = b.allocator.dupe(u8, stem) catch @panic("OOM");
        std.mem.replaceScalar(u8, canonical, '/', '.');
        root.appendSlice(b.allocator, b.fmt("pub const @\"{s}\" = @import(\"{s}.zig\").{s};\n", .{
            canonical, stem, std.fs.path.basename(stem),
        })) catch @panic("OOM");
    }
    run.setName("generate Zig AIDL interfaces");

    const tree = b.addWriteFiles();
    _ = tree.addCopyDirectory(generated, "", .{});
    const module = b.createModule(.{
        .root_source_file = tree.add("aidl_generated.zig", root.items),
        .target = o.target,
        .optimize = o.optimize,
    });
    module.addImport("aidl", o.runtime);
    if (o.custom) |custom| module.addImport("aidl_custom", custom);
    return module;
}
