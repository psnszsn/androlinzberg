//! System-image APKs that PackageManagerService scans from the system partition
//! (Soong android_app with platform_apis/certificate: "platform"): resources
//! compiled and linked by aapt2 against framework-res and the app's prebuilt
//! Android libraries, code compiled against the framework jar, dexed by D8,
//! merged and platform-signed.
const std = @import("std");
const LazyPath = std.Build.LazyPath;
const command = @import("command.zig");

/// A system-partition APK and its partition directory (priv-app or app).
pub const SystemApp = struct { name: []const u8, apk: LazyPath, privileged: bool };

pub const Spec = struct {
    /// Output and partition directory name (Soong module name).
    name: []const u8,
    manifest: LazyPath,
    res: ?LazyPath = null,
    java: []const LazyPath = &.{},
    kotlin: []const LazyPath = &.{},
    /// Package of the generated R class, when code references resources.
    r_package: ?[]const u8 = null,
    /// aapt2 --shared-lib (Soong aaptflags), for framework shared-library APKs.
    shared_lib: bool = false,
    /// Values of manifest `android:featureFlag` flags beyond framework-res's
    /// (Soong flags_packages), in aapt2 `--feature-flags` form.
    feature_flags: []const u8 = "",
    certificate: []const u8 = "azk-platform",
    /// Prebuilt android_library_import static_libs (AOSP-relative .aar/.jar),
    /// dependencies first: their classes join the app's dex and AAR resources
    /// are linked beneath the app's own.
    libraries: []const []const u8 = &.{},
    /// Compiled java_library static_libs, on the classpath and in the dex.
    jars: []const LazyPath = &.{},
    /// Jars whose non-class entries (Java resources such as META-INF/services)
    /// are packaged into the APK.
    java_resources: []const LazyPath = &.{},
    kotlin_plugins: []const LazyPath = &.{},
    kotlinc_flags: []const []const u8 = &.{},
};

pub const Framework = struct {
    classes: LazyPath,
    core: LazyPath,
    system_modules: LazyPath,
    resources: LazyPath,
};

pub fn build(b: *std.Build, aosp: *std.Build.Dependency, framework: Framework, spec: Spec) LazyPath {
    const flags = @import("gen/resource_flags.zig").csv;
    const feature_flags = if (spec.feature_flags.len == 0) flags else b.fmt("{s},{s}", .{ flags, spec.feature_flags });
    const res_zip = if (spec.res) |res| compile: {
        const compile = b.addSystemCommand(&.{ "aapt2", "compile", "--feature-flags", flags, "--dir" });
        compile.addDirectoryArg(res);
        compile.addArg("-o");
        const out = compile.addOutputFileArg(b.fmt("{s}-resources.zip", .{spec.name}));
        compile.setName(b.fmt("compile {s} resources", .{spec.name}));
        break :compile out;
    } else null;

    var aars: std.ArrayList([]const u8) = .empty;
    var library_classes: std.ArrayList(LazyPath) = .empty;
    for (spec.libraries) |library| {
        if (std.mem.endsWith(u8, library, ".aar")) {
            aars.append(b.allocator, library) catch @panic("OOM");
            library_classes.append(b.allocator, aarClasses(b, aosp, library)) catch @panic("OOM");
        } else library_classes.append(b.allocator, aosp.path(library)) catch @panic("OOM");
    }

    // Resource APK and, when code needs it, the compiled R classes.
    var r_classes: ?LazyPath = null;
    var r_dir: ?LazyPath = null;
    const resource_apk = if (aars.items.len != 0) linked: {
        const link = b.addSystemCommand(&.{"python3"});
        link.addFileArg(b.path("tools/android-app-link.py"));
        link.addArgs(&.{ "--aapt2", "aapt2", "--feature-flags", feature_flags, "--framework" });
        link.addFileArg(framework.resources);
        link.addArg("--manifest");
        link.addFileArg(spec.manifest);
        if (res_zip) |zip| {
            link.addArg("--app-res");
            link.addFileArg(zip);
        }
        link.addArg("--out-java");
        const java_dir = link.addOutputDirectoryArg(b.fmt("{s}-r", .{spec.name}));
        link.addArg("--out-apk");
        const out = link.addOutputFileArg(b.fmt("{s}-resources.apk", .{spec.name}));
        link.addArg("--");
        for (aars.items) |aar| link.addFileArg(aosp.path(aar));
        link.setName(b.fmt("link {s} resources with its libraries", .{spec.name}));
        r_classes = compileR(b, java_dir, spec.name);
        break :linked out;
    } else plain: {
        const link = b.addSystemCommand(&.{ "aapt2", "link", "--feature-flags", feature_flags, "--manifest" });
        link.addFileArg(spec.manifest);
        link.addArg("-I");
        link.addFileArg(framework.resources);
        link.addArgs(&.{ "--min-sdk-version", "35", "--target-sdk-version", "35" });
        if (spec.shared_lib) link.addArg("--shared-lib");
        r_dir = if (spec.r_package != null) link.addPrefixedOutputDirectoryArg("--java=", b.fmt("{s}-r", .{spec.name})) else null;
        link.addArg("-o");
        const out = link.addOutputFileArg(b.fmt("{s}-resources.apk", .{spec.name}));
        if (res_zip) |zip| link.addFileArg(zip);
        link.setName(b.fmt("link {s} resources", .{spec.name}));
        break :plain out;
    };

    const has_code = spec.java.len != 0 or spec.kotlin.len != 0;
    if (!has_code) {
        const unsigned = copyUnsigned(b, resource_apk, spec.name);
        return @import("generators.zig").signApk(b, aosp, unsigned, b.fmt("{s}.apk", .{spec.name}), false, spec.certificate);
    }

    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    var classpath: std.ArrayList(LazyPath) = .empty;
    classpath.append(b.allocator, framework.classes) catch @panic("OOM");
    classpath.append(b.allocator, kotlin_stdlib) catch @panic("OOM");
    if (r_classes) |jar| classpath.append(b.allocator, jar) catch @panic("OOM");
    classpath.appendSlice(b.allocator, library_classes.items) catch @panic("OOM");
    classpath.appendSlice(b.allocator, spec.jars) catch @panic("OOM");

    var compiled: std.ArrayList(LazyPath) = .empty;
    const kotlin_classes = if (spec.kotlin.len != 0) kotlin: {
        const kotlinc = command.create(b);
        kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
        kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-nowarn", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-Xsam-conversions=class", "-Xstring-concat=inline" });
        kotlinc.addArgs(spec.kotlinc_flags);
        for (spec.kotlin_plugins) |plugin| kotlinc.addPrefixedFileArg("-Xplugin=", plugin);
        kotlinc.addArg("-classpath");
        var kotlin_classpath: std.ArrayList(LazyPath) = .empty;
        kotlin_classpath.append(b.allocator, framework.core) catch @panic("OOM");
        kotlin_classpath.appendSlice(b.allocator, classpath.items) catch @panic("OOM");
        kotlinc.addJoinedPaths(":", kotlin_classpath.items);
        kotlinc.addArg("-d");
        const out = kotlinc.addOutputFileArg(b.fmt("{s}-kotlin-classes.jar", .{spec.name}));
        for (spec.kotlin) |source| kotlinc.addFileArg(source);
        // Java sources are visible to Kotlin and compiled by javac below.
        for (spec.java) |source| kotlinc.addFileArg(source);
        kotlinc.run.setName(b.fmt("compile {s} Kotlin", .{spec.name}));
        compiled.append(b.allocator, out) catch @panic("OOM");
        break :kotlin out;
    } else null;

    if (spec.java.len != 0) {
        const javac = command.create(b);
        javac.addArgs(&.{ "javac", "--system" });
        javac.addDirectoryArg(framework.system_modules);
        javac.addArgs(&.{ "-source", "21", "-target", "21", "-encoding", "UTF-8", "-XDstringConcat=inline", "-proc:none", "-nowarn", "-classpath" });
        var java_classpath: std.ArrayList(command.Command.PathInput) = .empty;
        for (classpath.items) |entry| java_classpath.append(b.allocator, .{ .file = entry }) catch @panic("OOM");
        if (kotlin_classes) |jar| java_classpath.append(b.allocator, .{ .file = jar }) catch @panic("OOM");
        javac.addJoinedPathInputs(":", java_classpath.items);
        javac.addArg("-d");
        const out = javac.addOutputDirectoryArg(b.fmt("{s}-classes", .{spec.name}));
        for (spec.java) |source| javac.addFileArg(source);
        if (r_dir) |dir| javac.addFileArg(dir.path(b, b.fmt("{s}/R.java", .{rPath(b, spec.r_package.?)})));
        javac.run.setName(b.fmt("compile {s} Java", .{spec.name}));
        const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
        const class_jar = jar.addOutputFileArg(b.fmt("{s}-classes.jar", .{spec.name}));
        jar.addArg("-C");
        jar.addDirectoryArg(out);
        jar.addArg(".");
        compiled.append(b.allocator, class_jar) catch @panic("OOM");
    }

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx2G", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(framework.core);
    d8.addArg("--classpath");
    d8.addFileArg(framework.classes);
    d8.addArg("--output");
    const dex = d8.addOutputFileArg(b.fmt("{s}-dex.jar", .{spec.name}));
    for (compiled.items) |jar| d8.addFileArg(jar);
    if (r_classes) |jar| d8.addFileArg(jar);
    for (library_classes.items) |jar| d8.addFileArg(jar);
    for (spec.jars) |jar| d8.addFileArg(jar);
    if (spec.kotlin.len != 0 or spec.libraries.len != 0) d8.addFileArg(kotlin_stdlib);
    d8.setName(b.fmt("dex {s}", .{spec.name}));

    const package = b.addSystemCommand(&.{"python3"});
    package.addFileArg(b.path("tools/window-fixture-apk.py"));
    package.addArg("package");
    package.addFileArg(resource_apk);
    package.addFileArg(dex);
    const unsigned = package.addOutputFileArg(b.fmt("{s}-unsigned.apk", .{spec.name}));
    for (spec.java_resources) |jar| package.addFileArg(jar);
    package.setName(b.fmt("package {s}", .{spec.name}));
    return @import("generators.zig").signApk(b, aosp, unsigned, b.fmt("{s}.apk", .{spec.name}), false, spec.certificate);
}

/// android_library_import: an AAR's compiled classes.
pub fn aarClasses(b: *std.Build, aosp: *std.Build.Dependency, aar: []const u8) LazyPath {
    const unzip = b.addSystemCommand(&.{ "unzip", "-p" });
    unzip.addFileArg(aosp.path(aar));
    unzip.addArg("classes.jar");
    return unzip.captureStdOut(.{ .basename = b.fmt("{s}-classes.jar", .{std.fs.path.stem(aar)}) });
}

/// The app's and its libraries' R classes, compiled from the generated sources.
fn compileR(b: *std.Build, sources: LazyPath, name: []const u8) LazyPath {
    const compile = b.addSystemCommand(&.{ "sh", "-c",
        \\set -e
        \\out=$(mktemp -d)
        \\find "$0" -name '*.java' > "$out/sources"
        \\javac -source 21 -target 21 -nowarn -encoding UTF-8 -proc:none -d "$out/classes" @"$out/sources"
        \\jar --create --file "$1" -C "$out/classes" .
        \\rm -rf "$out"
    });
    compile.addDirectoryArg(sources);
    const jar = compile.addOutputFileArg(b.fmt("{s}-R.jar", .{name}));
    compile.setName(b.fmt("compile {s} R classes", .{name}));
    return jar;
}

fn rPath(b: *std.Build, package: []const u8) []const u8 {
    const path = b.allocator.dupe(u8, package) catch @panic("OOM");
    std.mem.replaceScalar(u8, path, '.', '/');
    return path;
}

fn copyUnsigned(b: *std.Build, apk: LazyPath, name: []const u8) LazyPath {
    const files = b.addWriteFiles();
    return files.addCopyFile(apk, b.fmt("{s}-unsigned.apk", .{name}));
}
