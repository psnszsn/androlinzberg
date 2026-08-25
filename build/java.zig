//! Framework Java: the vendored AOSP subset + our shims -> classes.dex, plus
//! framework-res.apk (aapt2) and the AIDL-generated Java. Replaces
//! tools/build-java.sh; the curated source list lives in tools/java-srcs.py.
//!
//! zig's Run step does not hash directory contents, so steps that depend on
//! whole source trees take a configure-time "tree stamp" (tools/tree-stamp.py)
//! as a file input: the stamp changes iff any file in the trees changed.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    artzig: *std.Build.Dependency,
};

pub const Result = struct {
    /// directory containing classes.dex
    dex_dir: LazyPath,
    res_apk: LazyPath,
    java_step: *std.Build.Step,
};

/// Always-run step writing a content stamp of the given trees; consumers take
/// it with addFileInput so they re-run iff something in the trees changed.
/// (A configure-time stamp would not work: build.zig configuration is cached.)
fn stamp(b: *std.Build, name: []const u8, paths: []const LazyPath) LazyPath {
    const r = b.addSystemCommand(&.{ "python3", "tools/tree-stamp.py" });
    const out = r.addOutputFileArg(name);
    for (paths) |p| r.addDirectoryArg(p);
    r.has_side_effects = true;
    r.setName(b.fmt("tree-stamp {s}", .{name}));
    return out;
}

pub fn build(b: *std.Build, o: Options) Result {
    const dep = o.artzig;
    const fb = dep.path("extra/frameworks-base");
    const core_all = dep.namedLazyPath("core-all-jar");
    const stamp_java = stamp(b, "java.stamp", &.{
        fb.path(b, "core/java"),          fb.path(b, "graphics/java"),
        fb.path(b, "libs/hwui/apex/java"), dep.path("extra/modules-utils/java"),
        b.path("src/framework"),          b.path("src/framework-stubs"),
        b.path("src/java"),               b.path("tools/java-srcs.py"),
    });
    const stamp_res = stamp(b, "res.stamp", &.{ fb.path(b, "core/res"), b.path("tools/build-res.sh") });
    const stamp_aidl = stamp(b, "aidl.stamp", &.{ b.path("tools/aidl-list.txt"), b.path("tools/build-aidl.sh"), b.path("aidl-compat") });

    // 1. core-all + annotation classes -> compile-time "JDK" (system modules)
    const plus = b.addSystemCommand(&.{ "bash", "tools/make-core-plus.sh" });
    plus.addFileArg(core_all);
    plus.addDirectoryArg(fb);
    const core_plus = plus.addOutputFileArg("core-all-plus.jar");

    const sysmod = b.addSystemCommand(&.{"bash"});
    sysmod.addFileArg(dep.path("tools/make-system-modules.sh"));
    sysmod.addFileArg(core_plus);
    const sysmod_dir = sysmod.addOutputDirectoryArg("sysmodules");
    sysmod.addDirectoryArg(dep.path("vendor"));

    // 2. generated Java: AIDL interfaces and android.R / framework-res.apk
    const aidl = b.addSystemCommand(&.{ "bash", "tools/build-aidl.sh" });
    const aidl_dir = aidl.addOutputDirectoryArg("aidl-java");
    aidl.addFileInput(stamp_aidl);

    const res = b.addSystemCommand(&.{ "bash", "tools/build-res.sh" });
    const res_dir = res.addOutputDirectoryArg("res");
    res.addFileInput(stamp_res);
    const res_apk = res_dir.path(b, "framework-res.apk");

    // 3. curated source list
    const srcs = b.addSystemCommand(&.{ "python3", "tools/java-srcs.py" });
    const srcs_txt = srcs.addOutputFileArg("framework-srcs.txt");
    srcs.addPrefixedDirectoryArg("FB=", fb);
    srcs.addPrefixedDirectoryArg("MU=", dep.path("extra/modules-utils"));
    srcs.addPrefixedDirectoryArg("APP=", b.path("."));
    srcs.addPrefixedDirectoryArg("AIDL=", aidl_dir);
    srcs.addPrefixedDirectoryArg("RES=", res_dir);
    srcs.addFileInput(stamp_java);

    // 4. javac against the system-modules image
    const javac = b.addSystemCommand(&.{"javac"});
    javac.addPrefixedDirectoryArg("--system=", sysmod_dir);
    javac.addArgs(&.{ "-encoding", "UTF-8", "-g", "-parameters", "-XDstringConcat=inline", "-Xmaxerrs", "2000", "-nowarn", "-d" });
    const classes_dir = javac.addOutputDirectoryArg("classes");
    javac.addPrefixedFileArg("@", srcs_txt);
    javac.addFileInput(stamp_java);

    // 5. d8 (via a classes jar; the r8 CLI takes jars, not directories)
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const classes_jar = jar.addOutputFileArg("framework-classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(classes_dir);
    jar.addArg(".");

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    d8.addFileArg(dep.path("vendor/prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "34", "--lib" });
    d8.addFileArg(core_plus);
    d8.addArg("--output");
    const dex_dir = d8.addOutputDirectoryArg("framework-dex");
    d8.addFileArg(classes_jar);

    const java_step = b.step("java", "Build the framework dex (javac + d8), framework-res.apk and AIDL Java");
    java_step.dependOn(&b.addInstallFileWithDir(dex_dir.path(b, "classes.dex"), .{ .custom = "framework" }, "classes.dex").step);
    java_step.dependOn(&b.addInstallFileWithDir(res_apk, .{ .custom = "res" }, "framework-res.apk").step);
    return .{ .dex_dir = dex_dir, .res_apk = res_apk, .java_step = java_step };
}
