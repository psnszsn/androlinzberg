//! Build bionic_translation (https://gitlab.com/android_translation_layer/bionic_translation)
//! with Zig: the shim dynamic linker + bionic ABI shims that let ART load
//! Android (bionic-linked) native libraries on a glibc host.
//! Mirrors upstream meson.build. System deps: dl, egl, libelf, libunwind, libbsd, m.
const std = @import("std");

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    /// bionic_translation package root
    src: std.Build.LazyPath,
};

pub const Artifacts = struct {
    dl_bio: *std.Build.Step.Compile,
    pthread_bio: *std.Build.Step.Compile,
    c_bio: *std.Build.Step.Compile,
    stdcpp_bio: *std.Build.Step.Compile,
};

const c_common = [_][]const u8{ "-fPIC", "-D_GNU_SOURCE" };

/// zig's linkSystemLibrary quietly drops some pkg-config libs (and nix's libbsd.so
/// is an ld script); link system shared objects by absolute path instead.
fn linkByPath(b: *std.Build, m: *std.Build.Module, pc: []const u8, sonames: []const []const u8) void {
    var code: u8 = 0;
    const out = b.runAllowFail(&.{ "pkg-config", "--libs-only-L", pc }, &code, .ignore) catch {
        std.debug.panic("pkg-config {s} failed", .{pc});
    };
    var it = std.mem.tokenizeAny(u8, out, " \n");
    const dir = while (it.next()) |t| {
        if (std.mem.startsWith(u8, t, "-L")) break t[2..];
    } else std.debug.panic("pkg-config {s}: no -L", .{pc});
    for (sonames) |so| m.addObjectFile(.{ .cwd_relative = b.fmt("{s}/{s}", .{ dir, so }) });
}

fn lib(b: *std.Build, o: Options, name: []const u8) *std.Build.Step.Compile {
    const l = b.addLibrary(.{ .name = name, .linkage = .dynamic, .root_module = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .sanitize_c = .off,
    }) });
    for ([_][]const u8{ "$ORIGIN/", "$ORIGIN/../lib64" }) |r| l.root_module.addRPathSpecial(r);
    l.root_module.addIncludePath(o.src.path(b, "shared/config"));
    return l;
}

pub fn build(b: *std.Build, o: Options) Artifacts {
    // wrapper + config are compiled into each consumer (upstream links the
    // static convenience libs into every shared lib anyway)
    const wrapper_srcs = [_][]const u8{ "wrapper/wrapper.c", "shared/config/config.c" };

    const dl_bio = lib(b, o, "dl_bio");
    dl_bio.root_module.addCSourceFiles(.{
        .root = o.src,
        .files = &(wrapper_srcs ++ [_][]const u8{
            "linker/dlfcn.c",      "linker/linker_environ.c",
            "linker/linker_tls.c", "linker/rt.c",
            "linker/strlcpy.c",
        }),
        .flags = &(c_common ++ [_][]const u8{ "-D_LARGEFILE64_SOURCE", "-DLINKER_DEBUG=0", "-Wno-parentheses" }),
    });
    // linker.c relies on a GNU-as-specific section-naming hack
    // (`.section NAME,"a";#`) to place a copyable stub function and its adjacent
    // data in one section with __start_/__stop_ symbols. Clang's integrated
    // assembler takes the section name literally, so this one TU is built with
    // the system gcc and linked in as an object.
    dl_bio.root_module.addObjectFile(gccObject(b, o, "linker/linker.c", &.{
        "-fPIC",            "-D_GNU_SOURCE",                 "-D_LARGEFILE64_SOURCE",        "-DLINKER_DEBUG=0",
        "-Wno-parentheses", "-DR_AARCH64_TLS_DTPREL32=1031", "-DR_AARCH64_TLS_TPREL64=1030",
    }));
    dl_bio.root_module.linkSystemLibrary("EGL", .{ .needed = true });
    linkByPath(b, dl_bio.root_module, "libelf", &.{"libelf.so"});
    linkByPath(b, dl_bio.root_module, "libunwind-generic", &.{ "libunwind.so", "libunwind-x86_64.so" });

    const pthread_bio = lib(b, o, "pthread_bio");
    pthread_bio.root_module.addCSourceFiles(.{
        .root = o.src,
        .files = &.{"pthread_wrapper/libpthread.c"},
        .flags = &(c_common ++ [_][]const u8{"-fno-exceptions"}),
    });

    const c_bio = lib(b, o, "c_bio");
    c_bio.root_module.addCSourceFiles(.{
        .root = o.src,
        .files = &(wrapper_srcs ++ [_][]const u8{
            "libc/libc.c",       "libc/libc-chk.c",  "libc/libc-math.c",
            "libc/libc-misc.c",  "libc/libc-musl.c", "libc/libc-signal.c",
            "libc/libc-stdio.c", "libc/libc-sha1.c", "libc/libc-open-overrides.c",
        }),
        .flags = &(c_common ++ [_][]const u8{"-DPAGESIZE=4096"}),
    });
    c_bio.root_module.linkSystemLibrary("m", .{ .needed = true });
    linkByPath(b, c_bio.root_module, "libunwind-generic", &.{ "libunwind.so", "libunwind-x86_64.so" });
    // nix's libbsd.so is an ld script referencing -lmd, and zig does not forward -L
    // search paths to lld — link the real shared objects by absolute path instead.
    linkByPath(b, c_bio.root_module, "libbsd", &.{"libbsd.so.0"});
    linkByPath(b, c_bio.root_module, "libmd", &.{"libmd.so.0"});
    c_bio.root_module.addSystemIncludePath(blk: {
        var code: u8 = 0;
        const out = b.runAllowFail(&.{ "pkg-config", "--cflags-only-I", "libbsd" }, &code, .ignore) catch break :blk .{ .cwd_relative = "/usr/include" };
        const t = std.mem.trim(u8, out, " \n");
        break :blk .{ .cwd_relative = if (std.mem.startsWith(u8, t, "-I")) b.dupe(t[2..]) else "/usr/include" };
    });
    c_bio.root_module.linkLibrary(pthread_bio);
    // bionic defines FE_DFL_ENV as &__fe_dfl_env; glibc/musl use -1
    c_bio.root_module.addObjectFile(defsymObject(b, o));
    c_bio.linker_allow_shlib_undefined = true;

    const stdcpp_bio = lib(b, o, "stdc++_bio");
    stdcpp_bio.root_module.addIncludePath(o.src.path(b, "libstdc++_standalone/include"));
    stdcpp_bio.root_module.link_libcpp = true;
    stdcpp_bio.root_module.addCSourceFiles(.{
        .root = o.src,
        .files = &.{ "libstdc++_standalone/__cxa_guard.cpp", "libstdc++_standalone/__cxa_pure_virtual.cpp", "libstdc++_standalone/new.cpp" },
        .flags = &c_common,
    });
    return .{ .dl_bio = dl_bio, .pthread_bio = pthread_bio, .c_bio = c_bio, .stdcpp_bio = stdcpp_bio };
}

/// Compile one C file with the system gcc (for TUs using GNU-as-only features).
fn gccObject(b: *std.Build, o: Options, rel: []const u8, flags: []const []const u8) std.Build.LazyPath {
    const cc = b.addSystemCommand(&.{ "gcc", "-c" });
    cc.addArgs(flags);
    cc.addPrefixedDirectoryArg("-I", o.src.path(b, "shared/config"));
    // NOTE: do NOT add -I<linker dir>; it would make <dlfcn.h> resolve to bionic's
    // own linker/dlfcn.h. Quoted includes already resolve relative to the source.
    cc.addFileArg(o.src.path(b, rel));
    cc.addArg("-o");
    const out = cc.addOutputFileArg(b.fmt("{s}.o", .{std.fs.path.basename(rel[0 .. rel.len - 2])}));
    return out;
}

/// `--defsym,__fe_dfl_env=-1` as an assembly object (zig has no defsym passthrough per-lib)
fn defsymObject(b: *std.Build, o: Options) std.Build.LazyPath {
    const wf = b.addWriteFiles();
    const s = wf.add("fe_dfl_env.s",
        \\.globl __fe_dfl_env
        \\.set __fe_dfl_env, -1
        \\
    );
    const obj = b.addObject(.{ .name = "fe_dfl_env", .root_module = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
    }) });
    obj.root_module.addAssemblyFile(s);
    return obj.getEmittedBin();
}
