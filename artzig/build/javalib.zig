//! Boot-classpath Java build (core-oj, core-libart, conscrypt, apache-xml,
//! bouncycastle, okhttp) with the system JDK — replaces Soong's
//! metalava/turbine/hiddenapi pipeline with the classic bootstrap:
//!
//!   1. javac core-all (every boot source at once, --system=none,
//!      --patch-module=java.base) → core-all.jar
//!   2. tools/make-system-modules.sh → a java.base system-module image
//!   3. javac each module against it (mirrors Soong's per-module compiles;
//!      the intra-core-API stub system modules Soong uses are a subset of ours)
//!   4. d8 (r8.jar from the AOSP package) with the same flags Soong passes
//!      (--debug --min-api 31, record/permitted-subclasses annotations in dex)
//!   5. jar: classes.dex + java resources → the boot jar
//!
//! Source lists and resources extracted from the pinned Soong graph are
//! committed under build/gen/ and src/gen/java/.
const std = @import("std");
const table = @import("gen/java.zig");
const SourceFiles = @import("source_files.zig");

pub const Options = struct {
    vendor: std.Build.LazyPath,
    vendor_dir: []const u8,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
};

pub const Result = struct {
    /// module name -> final jar (dex+res for .dex modules, classes jar for core-all)
    jars: std.StringArrayHashMapUnmanaged(std.Build.LazyPath),
    /// classes (pre-dex) jars, for javac -classpath of consumers
    class_jars: std.StringArrayHashMapUnmanaged(std.Build.LazyPath),
    system_modules: std.Build.LazyPath,
};

fn javacBase(b: *std.Build, o: Options, source: []const u8) *std.Build.Step.Run {
    const run = b.addSystemCommand(&.{
        "sh",          "-c",        "exec javac \"$@\" 2>&1", "sh",
        "-J-Xmx4096M", "-encoding", "UTF-8",                  "-g",
        "-proc:none",  "-nowarn",   "-Xmaxerrs",              "9999999",
        "-source",     source,      "-target",                source,
    });
    run.setCwd(o.vendor);
    return run;
}

fn jarOfClasses(b: *std.Build, name: []const u8, classes: std.Build.LazyPath) std.Build.LazyPath {
    const run = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const out = run.addOutputFileArg(b.fmt("{s}-classes.jar", .{name}));
    run.addArg("-C");
    run.addDirectoryArg(classes);
    run.addArg(".");
    return out;
}

pub fn build(b: *std.Build, o: Options) Result {
    var res: Result = .{ .jars = .empty, .class_jars = .empty, .system_modules = undefined };

    // conscrypt's NativeConstants.java: compile & run the upstream generator
    // against the vendored BoringSSL headers.
    const natconst_dir = blk: {
        const exe = b.addExecutable(.{ .name = "conscrypt_generate_constants", .root_module = b.createModule(.{
            .target = o.target,
            .optimize = .ReleaseFast,
            .link_libc = true,
        }) });
        exe.root_module.addCSourceFiles(.{
            .root = o.vendor.path(b, "external/conscrypt/constants/src/gen/cpp"),
            .files = &.{"generate_constants.cc"},
            .flags = &.{"-fno-exceptions"},
        });
        exe.root_module.link_libcpp = true;
        exe.root_module.addIncludePath(o.vendor.path(b, "external/boringssl/src/include"));
        const run = b.addRunArtifact(exe);
        run.addArg("com.android.org.conscrypt");
        const java = run.captureStdOut(.{});
        const wf = b.addWriteFiles();
        _ = wf.addCopyFile(java, "com/android/org/conscrypt/NativeConstants.java");
        break :blk wf.getDirectory();
    };

    var sysmod: ?std.Build.LazyPath = null;

    for (&table.modules) |*m| {
        const javac = javacBase(b, o, m.source);

        // The response files keep argv short; every source they name is also
        // registered as an explicit Zig input for content-based invalidation.
        const rsp_path = b.fmt("build/gen/java/{s}.rsp", .{m.name});
        const rsp = SourceFiles.Root.project(b, rsp_path);
        var module_sources = SourceFiles.Collector.init(b);
        module_sources.addResponseFile(
            .{ .path = o.vendor, .absolute = o.vendor_dir },
            rsp,
        );
        const vendored_sources = module_sources.select(".java");
        javac.addPrefixedFileArg("@", rsp.path);
        vendored_sources.addInputs(javac);

        if (m.has_gen_src) {
            var generated_sources = SourceFiles.Collector.init(b);
            generated_sources.addTree(
                SourceFiles.Root.project(b, b.fmt("src/gen/java/{s}", .{m.name})),
                .{ .extensions = &.{".java"} },
            );
            const files = generated_sources.select(".java");
            javac.addPrefixedFileArg("@", files.responseFile(b, b.fmt("{s}-gen.rsp", .{m.name})));
            files.addInputs(javac);
        }
        if (std.mem.eql(u8, m.name, "conscrypt")) {
            javac.addFileArg(natconst_dir.path(b, "com/android/org/conscrypt/NativeConstants.java"));
        }

        if (std.mem.eql(u8, m.name, "core-all")) {
            javac.addArgs(&.{ "--system=none", "--patch-module=java.base=." });
        } else if (std.mem.eql(u8, m.source, "1.8")) {
            // pre-modules javac: boot classpath is the core-all classes jar
            javac.addArg("-bootclasspath");
            javac.addFileArg(res.class_jars.get("core-all").?);
            if (m.classpath.len > 0) {
                javac.addArg("-classpath");
                for (m.classpath, 0..) |dep, i| {
                    if (i > 0) @panic("multi-entry classpath not supported yet");
                    javac.addFileArg(res.class_jars.get(dep).?);
                }
            }
        } else {
            javac.addArg("--system");
            javac.addDirectoryArg(sysmod.?);
            javac.addArg("--patch-module=java.base=.");
            std.debug.assert(m.classpath.len == 0);
        }

        javac.addArg("-d");
        const classes = javac.addOutputDirectoryArg(b.fmt("{s}-classes", .{m.name}));
        const class_jar = jarOfClasses(b, m.name, classes);
        res.class_jars.put(b.allocator, m.name, class_jar) catch @panic("OOM");

        if (std.mem.eql(u8, m.name, "core-all")) {
            // system-modules image for everything after core-all
            const sm = b.addSystemCommand(&.{b.pathJoin(&.{ b.root.toString(b.allocator) catch @panic("OOM"), "tools/make-system-modules.sh" })});
            sm.addFileArg(class_jar);
            const out = sm.addOutputDirectoryArg("system-modules");
            sm.addDirectoryArg(o.vendor);
            sysmod = out;
            res.jars.put(b.allocator, m.name, class_jar) catch @panic("OOM");
            continue;
        }

        if (m.dex) {
            const d8 = b.addSystemCommand(&.{
                "java",
                "-Xmx4096M",
                "-Dcom.android.tools.r8.emitRecordAnnotationsInDex",
                "-Dcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex",
                "-cp",
            });
            d8.addFileArg(o.vendor.path(b, "prebuilts/r8/r8.jar"));
            d8.addArgs(&.{ "com.android.tools.r8.D8", "--debug", "--min-api", "31", "--output" });
            const dex_dir = d8.addOutputDirectoryArg(b.fmt("{s}-dex", .{m.name}));
            d8.addFileArg(class_jar);

            const mkjar = b.addSystemCommand(&.{b.pathJoin(&.{ b.root.toString(b.allocator) catch @panic("OOM"), "tools/mkbootjar.sh" })});
            const jar_out = mkjar.addOutputFileArg(b.fmt("{s}.jar", .{m.name}));
            mkjar.addDirectoryArg(dex_dir);
            mkjar.addDirectoryArg(o.vendor);
            if (m.has_res) {
                mkjar.addFileArg(b.path(b.fmt("build/gen/java/{s}.res", .{m.name})));
            } else {
                mkjar.addArg("-");
            }
            res.jars.put(b.allocator, m.name, jar_out) catch @panic("OOM");
        }
    }
    res.system_modules = sysmod.?;
    return res;
}
