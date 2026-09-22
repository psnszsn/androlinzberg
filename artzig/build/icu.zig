//! Pinned external/icu Android.bp modules: ICU4C, stable shims, JNI and ICU4J.
//! Keep all implementations and data from the same AOSP checkout.
const std = @import("std");
const Sources = @import("source_files.zig");
const command = @import("command.zig");
const art = @import("gen/art.zig");
const LazyPath = std.Build.LazyPath;

pub const Native = struct {
    common: *std.Build.Step.Compile,
    i18n: *std.Build.Step.Compile,
    stable: *std.Build.Step.Compile,
    android: *std.Build.Step.Compile,
};

fn library(b: *std.Build, vendor: Sources.Root, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode, name: []const u8) *std.Build.Step.Compile {
    const lib = b.addLibrary(.{ .name = name, .linkage = .dynamic, .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
        .sanitize_c = .off,
    }) });
    lib.each_lib_rpath = false;
    @import("host_toolchain.zig").addSharedLibraryCrt(b, lib);
    lib.root_module.addRPathSpecial("$ORIGIN");
    for ([_][]const u8{ "icu4c/source/common", "icu4c/source/i18n", "android_icu4c/include", "libandroidicuinit/include" }) |path|
        lib.root_module.addIncludePath(vendor.path.path(b, b.fmt("external/icu/{s}", .{path})));
    return lib;
}

fn addSources(b: *std.Build, lib: *std.Build.Step.Compile, vendor: Sources.Root, path: []const u8, flags: []const []const u8) void {
    var collector = Sources.Collector.init(b);
    collector.addTree(vendor.child(b, path), .{ .max_depth = 1, .extensions = &.{".cpp"} });
    const selected = collector.select(null);
    if (selected.items.len == 0) std.debug.panic("ICU module has no sources: {s}", .{path});
    for (selected.items) |source|
        lib.root_module.addCSourceFile(.{ .file = source.path, .flags = flags });
}

const common_flags = &[_][]const u8{ "-std=c++17", "-DANDROID", "-D_REENTRANT", "-DUCONFIG_USE_ML_PHRASE_BREAKING=1", "-Wno-deprecated-declarations", "-Wno-ambiguous-reversed-operator" };

pub fn native(b: *std.Build, vendor: Sources.Root, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode) Native {
    const uc = library(b, vendor, target, optimize, "icuuc-host");
    addSources(b, uc, vendor, "external/icu/icu4c/source/common", common_flags ++ &[_][]const u8{ "-DU_COMMON_IMPLEMENTATION", "-fvisibility=hidden" });
    addSources(b, uc, vendor, "external/icu/libandroidicuinit", common_flags);
    uc.root_module.addCSourceFile(.{ .file = vendor.path.path(b, "external/icu/icu4c/source/stubdata/stubdata.cpp"), .flags = common_flags });
    const i18n = library(b, vendor, target, optimize, "icui18n-host");
    addSources(b, i18n, vendor, "external/icu/icu4c/source/i18n", common_flags ++ &[_][]const u8{ "-DU_I18N_IMPLEMENTATION", "-fvisibility=hidden" });
    i18n.root_module.linkLibrary(uc);
    const stable = library(b, vendor, target, optimize, "icu");
    const android = library(b, vendor, target, optimize, "androidicu-host");
    for ([_]*std.Build.Step.Compile{ stable, android }, [_][]const u8{ "libicu/src", "libandroidicu/static_shim" }) |lib, path| {
        addSources(b, lib, vendor, b.fmt("external/icu/{s}", .{path}), common_flags ++ &[_][]const u8{ "-DU_SHOW_CPLUSPLUS_API=0", "-DU_SHOW_CPLUSPLUS_HEADER_API=0" });
        lib.root_module.linkLibrary(uc);
        lib.root_module.linkLibrary(i18n);
    }
    return .{ .common = uc, .i18n = i18n, .stable = stable, .android = android };
}

pub fn jni(b: *std.Build, vendor: Sources.Root, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode, n: Native, arts: art.Artifacts) *std.Build.Step.Compile {
    const lib = library(b, vendor, target, optimize, "icu_jni");
    // ScopedIcuLocale passes an uninitialized UErrorCode to ICU. The sources
    // name their sibling headers by quotes, so build the module from a copy
    // of the pinned directory that carries the header patched at build time.
    const bridge = "external/icu/android_icu4j/libcore_bridge/src/native";
    const locale_status = b.addSystemCommand(&.{ "patch", "--batch", "--forward", "--fuzz=0" });
    locale_status.addFileArg(vendor.path.path(b, bridge ++ "/ScopedIcuLocale.h"));
    locale_status.addArg("-i");
    locale_status.addFileArg(b.path("patches/host/0002-icu-jni-initialize-locale-status.patch"));
    locale_status.addArg("-o");
    const tree = b.addWriteFiles();
    _ = tree.addCopyFile(locale_status.addOutputFileArg("ScopedIcuLocale.h"), "ScopedIcuLocale.h");
    for ([_][]const u8{ "IcuUtilities.h", "JniConstants.h", "MatcherState.h", "ScopedJavaUnicodeString.h" }) |header|
        _ = tree.addCopyFile(vendor.path.path(b, b.fmt("{s}/{s}", .{ bridge, header })), header);
    // libicu_jni's srcs in the pinned external/icu Android.bp.
    const jni_sources = [_][]const u8{
        "com_android_i18n_timezone_internal_Memory.cpp", "com_android_i18n_util_ATrace.cpp",
        "com_android_i18n_util_Log.cpp",                 "com_android_icu_charset_NativeConverter.cpp",
        "com_android_icu_text_TimeZoneNamesNative.cpp",  "com_android_icu_util_CaseMapperNative.cpp",
        "com_android_icu_util_ICU4CMetadata.cpp",        "com_android_icu_util_LocaleNative.cpp",
        "com_android_icu_util_regex_MatcherNative.cpp",  "com_android_icu_util_regex_PatternNative.cpp",
        "com_android_icu_util_UResourceBundleNative.cpp", "IcuUtilities.cpp",
        "JniConstants.cpp",                              "MatcherState.cpp",
        "Register.cpp",
    };
    for (jni_sources) |source| _ = tree.addCopyFile(vendor.path.path(b, b.fmt("{s}/{s}", .{ bridge, source })), source);
    lib.root_module.addCSourceFiles(.{ .root = tree.getDirectory(), .files = &jni_sources, .flags = common_flags ++ &[_][]const u8{ "-std=c++23", "-DU_USING_ICU_NAMESPACE=0" } });
    // libcutils' original host tracing implementation, as selected by its Android.bp.
    // JNI uses C++23 so the host libc++ accepts cutils' C atomics with JNI's C++ atomics.
    lib.root_module.addCSourceFile(.{ .file = vendor.path.path(b, "system/core/libcutils/trace-host.cpp"), .flags = common_flags });
    for ([_][]const u8{ "libnativehelper/include_jni", "libnativehelper/include", "libnativehelper/header_only_include", "libnativehelper/include_platform", "libnativehelper/include_platform_header_only", "system/libbase/include", "system/logging/liblog/include", "system/core/libcutils/include" }) |path|
        lib.root_module.addIncludePath(vendor.path.path(b, path));
    for ([_]*std.Build.Step.Compile{ n.common, n.i18n, arts.libbase, arts.liblog, arts.libnativehelper }) |dep| lib.root_module.linkLibrary(dep);
    return lib;
}

pub const Java = struct { classes: LazyPath, dex: LazyPath };

pub fn java(b: *std.Build, vendor: Sources.Root, boot: @import("javalib.zig").Result) Java {
    const declaration = @import("generator_sources.zig").icu;
    const runner = b.addExecutable(.{ .name = "aosp-tool-runner", .root_module = b.createModule(.{
        .root_source_file = b.path("tools/aosp-tool-runner.zig"),
        .target = b.graph.host,
        .optimize = .ReleaseSafe,
    }) });
    const cache = b.addRunArtifact(runner);
    cache.addFileArg(vendor.path.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    cache.addArgs(&.{ "create-cache", "--package", declaration.package, "--container", declaration.container, "--declarations" });
    cache.addFileArg(vendor.path.path(b, declaration.input));
    for (declaration.values) |path| {
        cache.addArg("--values");
        cache.addFileArg(vendor.path.path(b, path));
    }
    cache.addArg("--cache");
    const pb = cache.addOutputFileArg("icu-aconfig.pb");
    const flags = b.addRunArtifact(runner);
    flags.addFileArg(vendor.path.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    flags.addArgs(&.{ "create-java-lib", "--mode", "production", "--cache" });
    flags.addFileArg(pb);
    flags.addArg("--out");
    const generated = flags.addOutputDirectoryArg("icu-flags");

    var sources = Sources.Collector.init(b);
    for ([_][]const u8{ "external/icu/android_icu4j/src/main/java/android/icu", "external/icu/android_icu4j/libcore_bridge/src/java" }) |path|
        sources.addTree(vendor.child(b, path), .{});
    const selected = sources.select(".java");
    const compile = command.create(b);
    compile.addArgs(&.{ "javac", "-J-Xmx4096M", "-encoding", "UTF-8", "-proc:none", "-source", "21", "-target", "21", "--system" });
    compile.addDirectoryArg(boot.system_modules);
    compile.addArg("--patch-module=java.base=.");
    compile.addPrefixedFileArg("@", selected.responseFile(b, "icu-java-sources.rsp"));
    selected.addInputs(compile.run);
    for (declaration.outputs) |name| compile.addFileArg(generated.path(b, name));
    // Compile-only annotations: the Soong jarjar rule zaps app-compat annotations,
    // and framework-annotations-lib is a non-static dependency.
    for ([_][]const u8{ "FlaggedApi", "NonNull", "Nullable" }) |name|
        compile.addFileArg(vendor.path.path(b, b.fmt("frameworks/libs/modules-utils/java/android/annotation/{s}.java", .{name})));
    compile.addArg("-d");
    const classes = compile.addOutputDirectoryArg("icu-classes");
    compile.run.setCwd(vendor.path);
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const output = jar.addOutputFileArg("core-icu4j-classes.jar");
    for ([_][]const u8{ "android/icu", "com/android" }) |path| {
        jar.addArg("-C");
        jar.addDirectoryArg(classes);
        jar.addArg(path);
    }
    const resources = vendor.child(b, "external/icu/android_icu4j/resources");
    jar.addArg("-C");
    jar.addDirectoryArg(resources.path);
    jar.addArg(".");
    var resource_inputs = Sources.Collector.init(b);
    resource_inputs.addTree(resources, .{ .extensions = &.{""} });
    resource_inputs.select(null).addInputs(jar);
    const d8 = b.addSystemCommand(&.{ "java", "-Xmx4096M", "-Dcom.android.tools.r8.emitRecordAnnotationsInDex", "-Dcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex", "-cp" });
    d8.addFileArg(vendor.path.path(b, "prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--debug", "--min-api", "31", "--classpath" });
    d8.addFileArg(boot.class_jars.get("core-all").?);
    d8.addArg("--output");
    const dexdir = d8.addOutputDirectoryArg("icu-dex");
    d8.addFileArg(output);
    const dexjar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const dex = dexjar.addOutputFileArg("core-icu4j.jar");
    dexjar.addArg("-C");
    dexjar.addDirectoryArg(dexdir);
    dexjar.addArg(".");
    dexjar.addArg("-C");
    dexjar.addDirectoryArg(resources.path);
    dexjar.addArg(".");
    resource_inputs.select(null).addInputs(dexjar);
    return .{ .classes = output, .dex = dex };
}

pub fn stageData(b: *std.Build, stage: *std.Build.Step.WriteFile, vendor: LazyPath) void {
    _ = stage.addCopyFile(vendor.path(b, "external/icu/icu4c/source/stubdata/icudt76l.dat"), "com.android.i18n/etc/icu/icudt76l.dat");
    // system/timezone/output_data/Android.bp, format 9 used by this RuntimeI18n.
    for ([_][]const u8{ "version/tz_version", "iana/tzdata", "android/tzlookup.xml", "android/telephonylookup.xml", "icu_overlay/zoneinfo64.res", "icu_overlay/metaZones.res", "icu_overlay/timezoneTypes.res", "icu_overlay/windowsZones.res" }) |path| {
        const directory = if (std.mem.startsWith(u8, path, "icu_overlay/")) "icu/" else "";
        _ = stage.addCopyFile(vendor.path(b, b.fmt("system/timezone/output_data/{s}", .{path})), b.fmt("com.android.tzdata/etc/tz/versioned/9/{s}{s}", .{ directory, std.fs.path.basename(path) }));
    }
    for ([_][]const u8{ "version/tz_version", "iana/tzdata" }) |path|
        _ = stage.addCopyFile(vendor.path(b, b.fmt("system/timezone/output_data/{s}", .{path})), b.fmt("com.android.tzdata/etc/tz/{s}", .{std.fs.path.basename(path)}));
}
