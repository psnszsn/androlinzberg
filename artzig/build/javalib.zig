//! Boot-classpath Java build (core-oj, core-libart, conscrypt, apache-xml,
//! bouncycastle, okhttp) with the system JDK — replaces Soong's
//! metalava/turbine/hiddenapi pipeline with the classic bootstrap:
//!
//!   1. javac core-all (every boot source at once, --system=none,
//!      --patch-module=java.base) → core-all.jar
//!   2. cached javac/jar/jmod/jlink nodes → a java.base system-module image
//!   3. javac each module against it (mirrors Soong's per-module compiles;
//!      the intra-core-API stub system modules Soong uses are a subset of ours)
//!   4. d8 (r8.jar from the AOSP package) with the same flags Soong passes
//!      (--debug --min-api 31, record/permitted-subclasses annotations in dex)
//!   5. one declared jar invocation: classes.dex + Java resources → boot jar
//!
//! Source and resource declarations extracted from the pinned Soong graph live
//! in build/java_sources.zig and build/gen/java.zig.
const std = @import("std");
const table = @import("gen/java.zig");
const SourceFiles = @import("source_files.zig");
const command = @import("command.zig");
const jar_entries = @import("jar_entries.zig");

pub const Options = struct {
    vendor: SourceFiles.Root,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
};

pub const Result = struct {
    /// module name -> final jar (dex+res for .dex modules, classes jar for core-all)
    jars: std.StringArrayHashMapUnmanaged(std.Build.LazyPath),
    /// classes (pre-dex) jars, for javac -classpath of consumers
    class_jars: std.StringArrayHashMapUnmanaged(std.Build.LazyPath),
    core_all_classes: std.Build.LazyPath,
    system_modules: std.Build.LazyPath,
};

fn javacBase(b: *std.Build, o: Options, source: []const u8) command.Command {
    const run = command.create(b);
    run.addArgs(&.{
        "javac",      "-J-Xmx4096M", "-encoding", "UTF-8",   "-g",
        "-proc:none", "-nowarn",     "-Xmaxerrs", "9999999", "-source",
        source,       "-target",     source,
    });
    run.run.setCwd(o.vendor.path);
    run.filterStderr("Note: Some input files use or override a deprecated API.");
    run.filterStderr("Note: Recompile with -Xlint:deprecation for details.");
    run.filterStderr("Note: Some input files use or override a deprecated API that is marked for removal.");
    run.filterStderr("Note: Recompile with -Xlint:removal for details.");
    run.filterStderr("Note: Some input files use unchecked or unsafe operations.");
    run.filterStderr("Note: Recompile with -Xlint:unchecked for details.");
    run.filterStderr(" uses unchecked or unsafe operations.");
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
    var res: Result = .{
        .jars = .empty,
        .class_jars = .empty,
        .core_all_classes = undefined,
        .system_modules = undefined,
    };

    // conscrypt's NativeConstants.java: compile & run the upstream generator
    // against the vendored BoringSSL headers.
    const natconst_dir = blk: {
        const exe = b.addExecutable(.{ .name = "conscrypt_generate_constants", .root_module = b.createModule(.{
            .target = o.target,
            .optimize = .ReleaseFast,
            .link_libc = true,
        }) });
        exe.root_module.addCSourceFiles(.{
            .root = o.vendor.path.path(b, "external/conscrypt/constants/src/gen/cpp"),
            .files = &.{"generate_constants.cc"},
            .flags = &.{"-fno-exceptions"},
        });
        exe.root_module.link_libcpp = true;
        exe.root_module.addIncludePath(o.vendor.path.path(b, "external/boringssl/src/include"));
        const run = b.addRunArtifact(exe);
        run.addArg("com.android.org.conscrypt");
        break :blk run.captureStdOut(.{ .basename = "NativeConstants.java" });
    };

    var sysmod: ?std.Build.LazyPath = null;

    for (&table.modules) |*m| {
        const javac = javacBase(b, o, m.source);

        // The response files keep argv short; every source they name is also
        // registered as an explicit Zig input for content-based invalidation.
        var module_sources = SourceFiles.Collector.init(b);
        for (m.sources) |source| module_sources.addFile(o.vendor, source);
        const vendored_sources = module_sources.select(".java");
        javac.addPrefixedFileArg("@", vendored_sources.responseFile(b, b.fmt("{s}-sources.rsp", .{m.name})));
        vendored_sources.addInputs(javac.run);

        if (m.has_gen_src) {
            var generated_sources = SourceFiles.Collector.init(b);
            generated_sources.addTree(
                SourceFiles.Root.project(b, b.fmt("src/gen/java/{s}", .{m.name})),
                .{ .extensions = &.{".java"} },
            );
            const files = generated_sources.select(".java");
            javac.addPrefixedFileArg("@", files.responseFile(b, b.fmt("{s}-gen.rsp", .{m.name})));
            files.addInputs(javac.run);
        }
        if (std.mem.eql(u8, m.name, "conscrypt")) {
            javac.addFileArg(natconst_dir);
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
        if (std.mem.eql(u8, m.name, "core-all")) res.core_all_classes = classes;
        const class_jar = jarOfClasses(b, m.name, classes);
        res.class_jars.put(b.allocator, m.name, class_jar) catch @panic("OOM");

        if (std.mem.eql(u8, m.name, "core-all")) {
            // system-modules image for everything after core-all
            sysmod = @import("system_modules.zig").build(
                b,
                o.vendor.path,
                class_jar,
                &.{classes},
                b.path("tools/build-command.zig"),
            );
            res.jars.put(b.allocator, m.name, class_jar) catch @panic("OOM");
            continue;
        }

        if (m.dex) {
            var resource_names: std.ArrayList([]const u8) = .empty;
            for (m.resources) |resource| resource_names.append(b.allocator, resource.file) catch @panic("OOM");
            jar_entries.validate(b.allocator, resource_names.items) catch |err|
                std.debug.panic("{s}: invalid boot-jar resource declarations: {t}", .{ m.name, err });
            const d8 = b.addSystemCommand(&.{
                "java",
                "-Xmx4096M",
                "-Dcom.android.tools.r8.emitRecordAnnotationsInDex",
                "-Dcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex",
                "-cp",
            });
            d8.addFileArg(o.vendor.path.path(b, "prebuilts/r8/r8.jar"));
            d8.addArgs(&.{ "com.android.tools.r8.D8", "--debug", "--min-api", "31", "--output" });
            const dex_dir = d8.addOutputDirectoryArg(b.fmt("{s}-dex", .{m.name}));
            d8.addFileArg(class_jar);

            const mkjar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
            const jar_out = mkjar.addOutputFileArg(b.fmt("{s}.jar", .{m.name}));
            mkjar.addArg("-C");
            mkjar.addDirectoryArg(dex_dir);
            mkjar.addArg(".");
            for (m.resources) |resource| {
                const resource_root = o.vendor.child(b, resource.root);
                const file = resource_root.path.path(b, resource.file);
                mkjar.addArg("-C");
                mkjar.addDirectoryArg(resource_root.path);
                mkjar.addArg(resource.file);
                mkjar.addFileInput(file);
            }
            res.jars.put(b.allocator, m.name, jar_out) catch @panic("OOM");
        }
    }
    res.system_modules = sysmod.?;
    return res;
}
