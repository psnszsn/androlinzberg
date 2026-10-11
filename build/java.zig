//! Framework JVM: the vendored AOSP subset + our Java/Kotlin replacements ->
//! a multidex framework.jar, plus framework-res.apk (aapt2) and generated Java. Replaces
//! tools/build-java.sh; the curated source list lives in build/java_sources.zig.
const std = @import("std");
const LazyPath = std.Build.LazyPath;
const SourceFiles = @import("artzig").SourceSet;
const java_sources = @import("java_sources.zig");
const command = @import("command.zig");

pub const Options = struct {
    artzig: *std.Build.Dependency,
    aosp: *std.Build.Dependency,
    generated: @import("generators.zig").Result,
    source_audit: *std.Build.Step,
};

pub const Result = struct {
    classes_jar: LazyPath,
    /// Exact static compiler inputs for the independent AOSP source comparison.
    static_sources: [4]LazyPath,
    compile_boot_jar: LazyPath,
    /// Classpath jar containing classes.dex and any secondary dex files.
    classpath_jar: LazyPath,
    compat_config: LazyPath,
    app_context_jar: LazyPath,
    platform_api_classes: LazyPath,
    framework_odex: LazyPath,
    framework_vdex: LazyPath,
    /// Test dex whose kotlin.KotlinVersion must shadow the host copy.
    kotlin_shadow_fixture: LazyPath,
    resources: @import("generators.zig").FrameworkResources,
    fixtures: @import("generators.zig").FixtureApks,
    java_step: *std.Build.Step,
    /// System-partition APKs that PackageManagerService scans at boot.
    system_apps: []const @import("system_apks.zig").SystemApp,
};

fn extractJarPackage(
    b: *std.Build,
    archive: LazyPath,
    package_pattern: []const u8,
    output_name: []const u8,
) LazyPath {
    const extract = b.addSystemCommand(&.{ "unzip", "-q" });
    extract.addFileArg(archive);
    extract.addArg(package_pattern);
    extract.addArg("-d");
    const output = extract.addOutputDirectoryArg(output_name);
    extract.setName(b.fmt("extract {s}", .{output_name}));
    return output;
}

fn extractConscryptCompileClasses(b: *std.Build, archive: LazyPath) LazyPath {
    const extract = b.addSystemCommand(&.{ "unzip", "-q" });
    extract.addFileArg(archive);
    extract.addArg("com/android/org/conscrypt/*");
    extract.addArg("-x");
    extract.addArgs(&.{
        "com/android/org/conscrypt/OpenSSLMessageDigestJDK*",
        "com/android/org/conscrypt/OpenSSLProvider.class",
        "com/android/org/conscrypt/OpenSSLRandom.class",
    });
    extract.addArg("-d");
    const output = extract.addOutputDirectoryArg("conscrypt-compile-classes");
    extract.setName("extract Conscrypt compile classes");
    return output;
}

fn extractI18nCompileClasses(b: *std.Build, archive: LazyPath) LazyPath {
    const extract = b.addSystemCommand(&.{ "unzip", "-q" });
    extract.addFileArg(archive);
    extract.addArg("android/icu/*");
    extract.addArg("com/android/i18n/*");
    extract.addArg("com/android/icu/*");
    extract.addArg("-d");
    const output = extract.addOutputDirectoryArg("i18n-compile-classes");
    extract.setName("extract modular i18n compile classes");
    return output;
}

pub fn build(b: *std.Build, o: Options) Result {
    const dep = o.artzig;
    const aosp = o.aosp;
    const core_all_classes = dep.namedLazyPath("core-all-classes");
    const conscrypt_compile_classes = extractConscryptCompileClasses(
        b,
        dep.namedLazyPath("conscrypt-classes"),
    );
    // Time and TimeUtils use the modular i18n implementation. ART supplies
    // this JAR at runtime; fold its classes into the compile-only system image
    // without repackaging them into framework.jar.
    const i18n_compile_classes = extractI18nCompileClasses(
        b,
        dep.namedLazyPath("core-icu4j-classes"),
    );
    const sources = java_sources.configure(b, aosp);
    var kotlinc_input_collector = SourceFiles.Collector.init(b);
    const kotlinc_root = SourceFiles.Root.dependency(b, aosp, "external/kotlinc");
    kotlinc_input_collector.addTree(kotlinc_root.child(b, "bin"), .{ .extensions = &.{""} });
    kotlinc_input_collector.addTree(kotlinc_root.child(b, "lib"), .{ .extensions = &.{""} });
    kotlinc_input_collector.addFile(kotlinc_root, "build.txt");
    const kotlinc_inputs = kotlinc_input_collector.select(null);

    // 1. core-all + the two annotations missing from libcore -> compile-time
    // "JDK" (system modules). The broader host annotation set is compiled
    // separately below and belongs only in framework.jar.
    const boot_annotation_javac = b.addSystemCommand(&.{
        "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-d",
    });
    const boot_annotation_classes = boot_annotation_javac.addOutputDirectoryArg("boot-annotation-classes");
    boot_annotation_javac.addFileArg(aosp.path("frameworks/base/core/java/android/annotation/SuppressLint.java"));
    boot_annotation_javac.addFileArg(aosp.path("frameworks/base/core/java/android/annotation/TargetApi.java"));
    boot_annotation_javac.setName("compile boot annotations");

    const annotation_javac = b.addSystemCommand(&.{
        "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-d",
    });
    const annotation_classes = annotation_javac.addOutputDirectoryArg("annotation-classes");
    annotation_javac.addPrefixedFileArg("@", sources.annotations_rsp);
    sources.addAnnotationInputs(annotation_javac);
    annotation_javac.setName("compile runtime annotations");

    const plus_base = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const core_plus_base = plus_base.addOutputFileArg("core-all-plus-base.jar");
    plus_base.addArg("-C");
    plus_base.addDirectoryArg(core_all_classes);
    plus_base.addArg(".");
    plus_base.addArg("-C");
    plus_base.addDirectoryArg(boot_annotation_classes);
    plus_base.addArg("android/annotation/SuppressLint.class");
    plus_base.addArg("-C");
    plus_base.addDirectoryArg(boot_annotation_classes);
    plus_base.addArg("android/annotation/TargetApi.class");
    plus_base.addArg("-C");
    plus_base.addDirectoryArg(conscrypt_compile_classes);
    plus_base.addArg(".");

    // The modular i18n JAR is authoritative for android.icu and timezone.
    // Materialize the base JAR as a directory and overlay those classes so
    // duplicate core-all entries are replaced rather than duplicated.
    const merge_i18n = b.addSystemCommand(&.{
        "bash",                                               "-c",
        "unzip -q \"$1\" -d \"$2\" && cp -R \"$3/.\" \"$2\"", "merge-core-i18n",
    });
    merge_i18n.addFileArg(core_plus_base);
    const core_plus_classes = merge_i18n.addOutputDirectoryArg("core-all-plus-classes");
    merge_i18n.addDirectoryArg(i18n_compile_classes);
    merge_i18n.setName("overlay modular i18n compile classes");

    const plus = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const core_plus = plus.addOutputFileArg("core-all-plus.jar");
    plus.addArg("-C");
    plus.addDirectoryArg(core_plus_classes);
    plus.addArg(".");

    const sysmod_dir = @import("artzig").systemModules(
        b,
        aosp.path(""),
        core_plus,
        &.{core_plus_classes},
        b.path("artzig/tools/build-command.zig"),
    );

    // external/protobuf/Android.bp compiles its host fallback against the
    // original libcore_private.stubs Unsafe declaration. The stub must never
    // replace ART's Unsafe or enter the shipped framework jar.
    const protobuf_header = command.create(b);
    protobuf_header.addArgs(&.{ "javac", "-source", "8", "-target", "8", "-nowarn", "-bootclasspath" });
    protobuf_header.addFileArg(core_plus);
    protobuf_header.addArg("-d");
    const protobuf_header_classes = protobuf_header.addOutputDirectoryArg("protobuf-private-headers");
    protobuf_header.addFileArg(aosp.path("external/protobuf/java/core/src/stubs/sun/misc/Unsafe.java"));
    protobuf_header.run.setName("compile pinned protobuf private header");
    const protobuf_javac = command.create(b);
    protobuf_javac.addArgs(&.{ "javac", "-source", "8", "-target", "8", "-nowarn", "-bootclasspath" });
    protobuf_javac.addJoinedPathInputs(":", &.{ .{ .directory = protobuf_header_classes }, .{ .file = core_plus } });
    protobuf_javac.addArg("-d");
    const protobuf_classes = protobuf_javac.addOutputDirectoryArg("protobuf-lite-classes");
    protobuf_javac.addPrefixedFileArg("@", sources.protobuf_lite.responseFile(b, "protobuf-lite-sources.rsp"));
    sources.protobuf_lite.addInputs(protobuf_javac.run);
    protobuf_javac.run.setName("compile stock protobuf lite runtime");

    // 2. Generated Java, framework resources, and fixture APKs are cacheable
    // Run/WriteFiles nodes owned by build/generators.zig.
    const res_apk = o.generated.resources.apk;

    // 3. Kotlin first, then Java, matching AOSP Soong's mixed-source ordering.
    // The host command adapter joins the exact classpath LazyPaths without
    // hiding either source or compiler dependencies in shell syntax.
    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    // Match the pinned AOSP java_import modules instead of maintaining local copies of their
    // annotations. Extract only class packages so they can be compiled against and folded into
    // the single hosted framework jar without carrying unrelated JAR metadata.
    const androidx_annotation_classes = extractJarPackage(
        b,
        aosp.path("prebuilts/sdk/current/androidx/m2repository/androidx/annotation/annotation-jvm/1.9.0-rc01/annotation-jvm-1.9.0-rc01.jar"),
        "androidx/annotation/*",
        "androidx-annotation-classes",
    );
    const error_prone_annotation_classes = extractJarPackage(
        b,
        aosp.path("external/error_prone/error_prone/error_prone_annotations-2.36.0.jar"),
        "com/google/errorprone/annotations/*",
        "error-prone-annotation-classes",
    );
    const cronet = @import("cronet.zig").build(b, .{
        .aosp = aosp,
        .system_modules = sysmod_dir,
        .implementation_sources = sources.cronet_preload,
        .generated_sources = o.generated.jvm.cronet_sources,
        .protobuf_classes = protobuf_classes,
        .androidx_annotations = androidx_annotation_classes,
        .error_prone_annotations = error_prone_annotation_classes,
        .kotlin_stdlib = kotlin_stdlib,
    });
    const guava_host_jar = aosp.path("prebuilts/tools/common/m2/repository/com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar");
    // Reuse AOSP's keepanno-annotations java_import, including its generated API.
    const r8_keepanno_jar = aosp.path("prebuilts/r8/keepanno-annotations.jar");
    const r8_keepanno_classes = extractJarPackage(b, r8_keepanno_jar, "com/android/tools/r8/keepanno/annotations/*", "r8-keepanno-classes");

    const compat_declaration = @import("generator_sources.zig").compat_config;
    var compat_sources = SourceFiles.Collector.init(b);
    const aosp_root = SourceFiles.Root.dependency(b, aosp, "");
    for (compat_declaration.processor_sources) |path| compat_sources.addFile(aosp_root, path);
    compat_sources.addTree(SourceFiles.Root.dependency(b, aosp, compat_declaration.annotations_tree), .{});
    const compat_java = compat_sources.select(".java");
    const compat_javac = command.create(b);
    compat_javac.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-classpath" });
    compat_javac.addFileArg(guava_host_jar);
    compat_javac.addArg("-d");
    const compat_processor = compat_javac.addOutputDirectoryArg("compat-processor-classes");
    compat_javac.addPrefixedFileArg("@", compat_java.responseFile(b, "compat-processor-sources.rsp"));
    compat_java.addInputs(compat_javac.run);
    compat_javac.run.setName("compile AOSP compatibility change processor");

    var property_cache_sources = SourceFiles.Collector.init(b);
    const property_cache_root = SourceFiles.Root.dependency(
        b,
        aosp,
        "frameworks/base/tools/processors/property_cache/src/java",
    );
    property_cache_sources.addTree(property_cache_root, .{});
    const modules_utils_root = SourceFiles.Root.dependency(b, aosp, "frameworks/libs/modules-utils");
    inline for (.{
        "java/com/android/internal/annotations/CacheModifier.java",
        "java/com/android/internal/annotations/CachedProperty.java",
        "java/com/android/internal/annotations/CachedPropertyDefaults.java",
    }) |path| property_cache_sources.addFile(modules_utils_root, path);
    const property_cache_java = property_cache_sources.select(".java");
    const property_cache_rsp = property_cache_java.responseFile(b, "property-cache-processor-sources.rsp");

    const property_cache_javac = command.create(b);
    property_cache_javac.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-classpath" });
    property_cache_javac.addJoinedPathInputs(":", &.{
        .{ .file = guava_host_jar },
        .{ .directory = annotation_classes },
    });
    property_cache_javac.addArg("-d");
    const property_cache_classes = property_cache_javac.addOutputDirectoryArg("property-cache-processor-classes");
    property_cache_javac.addPrefixedFileArg("@", property_cache_rsp);
    property_cache_java.addInputs(property_cache_javac.run);
    property_cache_javac.run.setName("compile property cache annotation processor");

    const property_cache_jar_cmd = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const property_cache_jar = property_cache_jar_cmd.addOutputFileArg("property-cache-processor.jar");
    property_cache_jar_cmd.addArg("-C");
    property_cache_jar_cmd.addDirectoryArg(property_cache_classes);
    property_cache_jar_cmd.addArg(".");
    property_cache_jar_cmd.setName("package property cache annotation processor");
    b.step("property-cache-processor", "Build the AOSP property cache annotation processor")
        .dependOn(&property_cache_jar_cmd.step);

    const package_manager_internal_processor = command.create(b);
    package_manager_internal_processor.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    package_manager_internal_processor.addArgs(&.{ "-no-reflect", "-jvm-target", "21", "-classpath" });
    package_manager_internal_processor.addFileArg(kotlin_stdlib);
    package_manager_internal_processor.addArg("-d");
    const package_manager_internal_processor_jar =
        package_manager_internal_processor.addOutputFileArg("package-manager-internal-processor.jar");
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/LocalServiceAdapterProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/ActivityManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/WindowManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/DisplayManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/PowerManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/InputManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/BatteryManagerInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/UsageStatsInternalProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/DesktopWindowPolicyProcessor.kt"));
    package_manager_internal_processor.addFileArg(b.path("src/java/dev/artzig/tools/PackageManagerInternalTestDoubleProcessor.kt"));
    package_manager_internal_processor.run.setName("compile local-service adapter processors");

    var safeparcel_sources = SourceFiles.Collector.init(b);
    const jsilver_root = SourceFiles.Root.dependency(b, aosp, "external/jsilver/src");
    const javapoet_root = SourceFiles.Root.dependency(b, aosp, "external/javapoet/src/main/java");
    const safeparcel_root = SourceFiles.Root.dependency(b, aosp, "packages/modules/AppSearch/safeparcel-processor");
    safeparcel_sources.addTree(jsilver_root, .{});
    safeparcel_sources.addTree(javapoet_root, .{});
    safeparcel_sources.addTree(safeparcel_root.child(b, "src"), .{});
    const safeparcel_java = safeparcel_sources.select(".java");
    const safeparcel_rsp = safeparcel_java.responseFile(b, "safeparcel-processor-sources.rsp");

    const safeparcel_javac = command.create(b);
    safeparcel_javac.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-classpath" });
    safeparcel_javac.addJoinedPathInputs(":", &.{
        .{ .file = guava_host_jar },
        .{ .directory = androidx_annotation_classes },
    });
    safeparcel_javac.addArg("-d");
    const safeparcel_classes = safeparcel_javac.addOutputDirectoryArg("safeparcel-processor-classes");
    safeparcel_javac.addPrefixedFileArg("@", safeparcel_rsp);
    inline for (.{
        "Note: Some input files use or override a deprecated API.",
        "Note: Recompile with -Xlint:deprecation for details.",
        "Note: Some input files use or override a deprecated API that is marked for removal.",
        "Note: Recompile with -Xlint:removal for details.",
        "uses unchecked or unsafe operations.",
        "Note: Recompile with -Xlint:unchecked for details.",
    }) |pattern| safeparcel_javac.filterStderr(pattern);
    safeparcel_java.addInputs(safeparcel_javac.run);
    safeparcel_javac.run.setName("compile AppSearch SafeParcel processor");

    const safeparcel_jar_cmd = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const safeparcel_jar = safeparcel_jar_cmd.addOutputFileArg("safeparcel-processor.jar");
    safeparcel_jar_cmd.addArg("-C");
    safeparcel_jar_cmd.addDirectoryArg(safeparcel_classes);
    safeparcel_jar_cmd.addArg(".");
    safeparcel_jar_cmd.addArg("-C");
    safeparcel_jar_cmd.addDirectoryArg(safeparcel_root.child(b, "resources").path);
    safeparcel_jar_cmd.addArg(".");
    safeparcel_jar_cmd.addArg("-C");
    safeparcel_jar_cmd.addDirectoryArg(jsilver_root.path);
    safeparcel_jar_cmd.addArg(".");
    safeparcel_jar_cmd.setName("package AppSearch SafeParcel processor");

    // Pinned external/auto/value auto_value_plugin (libauto_value_plugin), the
    // processor ART Service's AutoValue classes are generated with. Its static
    // libraries are compiled from their pinned sources; asm-9.6's API is the
    // org.objectweb.asm core of the pinned external/ow2-asm tree.
    var auto_value_sources = SourceFiles.Collector.init(b);
    const auto_root = SourceFiles.Root.dependency(b, aosp, "external/auto");
    auto_value_sources.addTree(auto_root.child(b, "value/src/main/java/com/google/auto/value"), .{});
    auto_value_sources.addTree(auto_root.child(b, "common/src/main/java"), .{});
    auto_value_sources.addTree(auto_root.child(b, "service/annotations/src/main/java"), .{});
    auto_value_sources.addTree(auto_root.child(b, "android-annotation-stubs/src"), .{});
    auto_value_sources.addTree(SourceFiles.Root.dependency(b, aosp, "external/escapevelocity/src/main/java"), .{});
    auto_value_sources.addTree(SourceFiles.Root.dependency(b, aosp, "external/ow2-asm/asm/src/main/java"), .{});
    auto_value_sources.addTree(javapoet_root, .{});
    const auto_value_java = auto_value_sources.select(".java");
    const kotlinx_metadata_jar = aosp.path("external/kotlinx.metadata/kotlinx-metadata-jvm-0.6.0.jar");
    const auto_value_javac = command.create(b);
    auto_value_javac.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-proc:none", "-classpath" });
    auto_value_javac.addJoinedPathInputs(":", &.{
        .{ .file = guava_host_jar },
        .{ .file = kotlin_stdlib },
        .{ .file = kotlinx_metadata_jar },
        .{ .file = aosp.path("external/error_prone/error_prone/error_prone_annotations-2.36.0.jar") },
    });
    auto_value_javac.addArg("-d");
    const auto_value_classes = auto_value_javac.addOutputDirectoryArg("auto-value-processor-classes");
    auto_value_javac.addPrefixedFileArg("@", auto_value_java.responseFile(b, "auto-value-processor-sources.rsp"));
    inline for (.{
        "Note: Some input files use or override a deprecated API.",
        "Note: Recompile with -Xlint:deprecation for details.",
        "Note: Some input files use or override a deprecated API that is marked for removal.",
        "Note: Recompile with -Xlint:removal for details.",
        "uses unchecked or unsafe operations.",
        "Note: Recompile with -Xlint:unchecked for details.",
    }) |pattern| auto_value_javac.filterStderr(pattern);
    auto_value_java.addInputs(auto_value_javac.run);
    auto_value_javac.run.setName("compile AutoValue processor");
    // auto_value_plugin_resources: the processor's Velocity templates.
    const auto_value_templates = b.addWriteFiles();
    inline for (.{ "autoannotation", "autobuilderannotation", "autobuilder", "autooneof", "autovalue", "builder", "equalshashcode", "gwtserializer" }) |name| {
        _ = auto_value_templates.addCopyFile(
            aosp.path("external/auto/value/src/main/java/com/google/auto/value/processor/" ++ name ++ ".vm"),
            "com/google/auto/value/processor/" ++ name ++ ".vm",
        );
    }
    b.step("safeparcel-processor", "Build the AppSearch SafeParcel annotation processor")
        .dependOn(&safeparcel_jar_cmd.step);

    const kotlin_classpath = [_]command.Command.PathInput{
        .{ .file = core_plus },
        .{ .file = kotlin_stdlib },
        .{ .directory = androidx_annotation_classes },
        .{ .directory = cronet.experimental_annotations },
        .{ .directory = error_prone_annotation_classes },
        .{ .directory = r8_keepanno_classes },
        .{ .directory = protobuf_classes },
    };
    const KotlinModule = struct {
        /// One kotlinc run over a Kotlin module, with the framework's Java
        /// sources and generated Java visible as sources.
        fn compile(builder: *std.Build, aosp_dep: *std.Build.Dependency, opts: Options, set: java_sources.Set,
            inputs: SourceFiles.Files, preload: LazyPath, classpath: []const command.Command.PathInput,
            flags: []const []const u8, kotlin_rsp: LazyPath, output: []const u8) struct { run: command.Command, classes: LazyPath } {
            const run = command.create(builder);
            run.addFileArg(aosp_dep.path("external/kotlinc/bin/kotlinc"));
            inline for (.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-nowarn", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-java-parameters", "-Xsam-conversions=class", "-Xstring-concat=inline" }) |arg| run.addArg(arg);
            run.addArgs(flags);
            run.addArg("-classpath");
            run.addJoinedPathInputs(":", classpath);
            run.addArg("-d");
            const classes = run.addOutputDirectoryArg(output);
            run.addPrefixedFileArg("@", set.java_rsp);
            run.addPrefixedFileArg("@", kotlin_rsp);
            for (opts.generated.jvm.sources) |file| run.addFileArg(file);
            run.addFileArg(preload);
            // K2 also needs package roots to resolve fully qualified Java annotation constants
            // inherited from Activity. These roots contain only the declared aconfig outputs.
            for (opts.generated.jvm.aconfig_source_roots) |root| {
                run.addPrefixedDirectoryArg("-Xjava-source-roots=", root);
            }
            // Relocated nano runtime and messages also need a package root for K2.
            run.addPrefixedDirectoryArg("-Xjava-source-roots=", opts.generated.jvm.nano_source_root);
            set.addJavacInputs(run.run);
            inputs.addInputs(run.run);
            run.run.step.dependOn(opts.source_audit);
            // C1-only code fills the code cache faster; size it for the whole tree.
            run.run.setEnvironmentVariable("JAVA_OPTS", "-Xmx2048M -Xms256M -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=512m");
            return .{ .run = run, .classes = classes };
        }
    };
    // Pinned frameworks/base/services/permission/Android.bp kotlincflags: its
    // stock Java callers pass null for platform types (such as the internal
    // volume UUID), so this module alone omits Kotlin's null assertions.
    const permission_kotlin = KotlinModule.compile(b, aosp, o, sources, sources.permission_kotlin, cronet.preload_source,
        &kotlin_classpath, &.{ "-module-name", "services.permission", "-Xjvm-default=all", "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions" },
        sources.permission_kotlin_rsp, "permission-kotlin-classes");
    kotlinc_inputs.addInputs(permission_kotlin.run.run);
    permission_kotlin.run.run.setName("compile framework permission Kotlin");
    const permission_classes = permission_kotlin.classes;

    const framework_kotlin = KotlinModule.compile(b, aosp, o, sources, sources.kotlin, cronet.preload_source,
        &(kotlin_classpath ++ [_]command.Command.PathInput{.{ .directory = permission_classes }}),
        &.{}, sources.kotlin_rsp, "kotlin-classes");
    kotlinc_inputs.addInputs(framework_kotlin.run.run);
    framework_kotlin.run.run.setName("compile framework Kotlin");
    const kotlin_classes = framework_kotlin.classes;

    const javac = command.create(b);
    javac.addArg("javac");
    javac.addArg("--system");
    javac.addDirectoryArg(sysmod_dir);
    inline for (.{ "-source", "21", "-target", "21", "-encoding", "UTF-8", "-g", "-parameters", "-XDstringConcat=inline", "-Xmaxerrs", "2000", "-nowarn", "-Xlint:none", "-classpath" }) |arg| javac.addArg(arg);
    javac.addJoinedPathInputs(":", &.{
        .{ .directory = kotlin_classes },
        .{ .directory = permission_classes },
        .{ .directory = annotation_classes },
        .{ .directory = androidx_annotation_classes },
        .{ .directory = cronet.experimental_annotations },
        .{ .directory = error_prone_annotation_classes },
        .{ .directory = r8_keepanno_classes },
        .{ .directory = protobuf_classes },
        .{ .file = kotlin_stdlib },
    });
    javac.addArg("-processorpath");
    javac.addJoinedPathInputs(":", &.{
        .{ .directory = compat_processor },
        .{ .file = safeparcel_jar },
        .{ .file = property_cache_jar },
        .{ .file = package_manager_internal_processor_jar },
        .{ .directory = auto_value_classes },
        .{ .directory = auto_value_templates.getDirectory() },
        .{ .file = kotlinx_metadata_jar },
        .{ .file = o.generated.jvm.system_features_processor },
        .{ .directory = o.generated.jvm.system_features_processor_dependencies },
        .{ .file = kotlin_stdlib },
        .{ .file = guava_host_jar },
        .{ .directory = androidx_annotation_classes },
    });
    javac.addArgs(&.{
        "-processor",
        "com.google.auto.value.processor.AutoValueProcessor,android.app.appsearch.safeparcel.SafeParcelProcessor,android.processor.property_cache.CachedPropertyProcessor,dev.artzig.tools.ActivityManagerInternalProcessor,dev.artzig.tools.WindowManagerInternalProcessor,dev.artzig.tools.DisplayManagerInternalProcessor,dev.artzig.tools.PowerManagerInternalProcessor,dev.artzig.tools.InputManagerInternalProcessor,dev.artzig.tools.BatteryManagerInternalProcessor,dev.artzig.tools.UsageStatsInternalProcessor,dev.artzig.tools.DesktopWindowPolicyProcessor,dev.artzig.tools.PackageManagerInternalTestDoubleProcessor," ++ @import("generator_sources.zig").system_features.processor_class ++ "," ++ compat_declaration.processor_class,
    });
    javac.addArg("-d");
    const java_classes = javac.addOutputDirectoryArg("java-classes");
    javac.addPrefixedFileArg("@", sources.java_rsp);
    for (o.generated.jvm.sources) |file| javac.addFileArg(file);
    javac.addFileArg(cronet.preload_source);
    inline for (.{
        "Note: Some input files use or override a deprecated API.",
        "Note: Recompile with -Xlint:deprecation for details.",
        "Note: Some input files use unchecked or unsafe operations.",
        "Note: Recompile with -Xlint:unchecked for details.",
    }) |pattern| javac.filterStderr(pattern);
    sources.addJavacInputs(javac.run);
    javac.run.step.dependOn(o.source_audit);
    javac.run.setName("compile framework Java");

    const input_resources = b.addWriteFiles();
    _ = input_resources.addCopyFile(aosp.path("frameworks/base/data/keyboards/Generic.kl"), "dev/artzig/input/Generic.kl");
    const input_resources_dir = input_resources.getDirectory();

    // 5. Package each compiler's directory directly, with no intermediate
    // class-tree copies. The r8 CLI takes jars rather than directories.
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const classes_jar = jar.addOutputFileArg("framework-classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(kotlin_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(permission_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(annotation_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(java_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(androidx_annotation_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(cronet.experimental_annotations);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(error_prone_annotation_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(r8_keepanno_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(protobuf_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(o.generated.jvm.resources);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(input_resources_dir);
    jar.addArg(".");

    const merge_compat = command.create(b);
    merge_compat.addArg("python3");
    merge_compat.addFileArg(aosp.path(compat_declaration.merge_tool));
    merge_compat.addArg("--jar");
    merge_compat.addFileArg(classes_jar);
    merge_compat.addArg("--device-config");
    const compat_config = merge_compat.addOutputFileArg("framework-compat-config.xml");
    merge_compat.run.setName("merge AOSP framework compatibility changes");
    b.step("gen-framework-compat-config", "Generate framework compatibility change configuration")
        .dependOn(&merge_compat.run.step);

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(core_plus);
    d8.addArg("--output");
    const framework_dex = d8.addOutputDirectoryArg("framework-dex");
    d8.addFileArg(classes_jar);

    // D8 discards Java resources. Package its dex output together with the
    // stock MIME/key-layout data so Class.getResourceAsStream works on the runtime jar.
    const runtime_jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const classpath_jar = runtime_jar.addOutputFileArg("framework.jar");
    runtime_jar.addArgs(&.{ "--no-compress", "-C" });
    runtime_jar.addDirectoryArg(framework_dex);
    runtime_jar.addArgs(&.{ ".", "-C" });
    runtime_jar.addDirectoryArg(o.generated.jvm.resources);
    runtime_jar.addArg(".");
    runtime_jar.addArg("-C");
    runtime_jar.addDirectoryArg(input_resources_dir);
    runtime_jar.addArg(".");

    // APK compilation sees the same public namespace as the runtime platform
    // parent. Stored contexts still validate the complete implementation jar.
    const platform_api = b.addSystemCommand(&.{"python3"});
    platform_api.addFileArg(b.path("tools/platform-api-jar.py"));
    platform_api.addFileArg(classes_jar);
    const platform_api_classes = platform_api.addOutputFileArg("platform-api-classes.jar");
    const app_context_d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    app_context_d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    app_context_d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    app_context_d8.addFileArg(core_plus);
    app_context_d8.addArg("--lib");
    app_context_d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));
    app_context_d8.addArg("--output");
    const app_context_jar = app_context_d8.addOutputFileArg("framework-app-context.jar");
    app_context_d8.addFileArg(platform_api_classes);
    app_context_d8.addArg("--lib");
    app_context_d8.addFileArg(classes_jar);

    // Android's framework jars are dexpreopted into the system image. Build an adjacent hosted
    // odex/vdex pair against the exact ART boot image and final framework.jar location.
    const art_runtime = dep.namedLazyPath("art-runtime");
    const dexopt = command.create(b);
    dexopt.setPathEnvironment("ANDROID_ROOT", art_runtime);
    dexopt.setChildPathEnvironment("ANDROID_ART_ROOT", "apex/com.android.art", art_runtime);
    dexopt.setChildPathEnvironment("ANDROID_I18N_ROOT", "com.android.i18n", art_runtime);
    dexopt.setChildPathEnvironment("ANDROID_TZDATA_ROOT", "com.android.tzdata", art_runtime);
    _ = dexopt.setOutputDirectoryEnvironment("ANDROID_DATA", "framework-oat-data");
    dexopt.run.setEnvironmentVariable("ANDROID_LOG_TAGS", "*:w");
    dexopt.addChildPathArg("bin/dex2oat64", art_runtime);
    dexopt.addPrefixedFileArg("--dex-file=", classpath_jar);
    dexopt.addPrefixedFileArg("--dex-location=", classpath_jar);
    const framework_odex = dexopt.addPrefixedOutputFileArg("--oat-file=", "framework.odex");
    const framework_vdex = dexopt.addPrefixedOutputFileArg("--output-vdex=", "framework.vdex");
    dexopt.addArgs(&.{
        "--instruction-set=x86_64",
        "--instruction-set-features=runtime",
        "--compiler-filter=speed",
        "--class-loader-context=PCL[]",
    });
    dexopt.addPrefixedChildPathArg("--boot-image=", "apex/com.android.art/javalib/boot.art", art_runtime);
    dexopt.addArg("--runtime-arg");
    dexopt.addPrefixedJoinedChildPaths("-Xbootclasspath:", ":", &.{
        "apex/com.android.art/javalib/core-oj.jar",
        "apex/com.android.art/javalib/core-libart.jar",
        "apex/com.android.art/javalib/okhttp.jar",
        "apex/com.android.art/javalib/bouncycastle.jar",
        "apex/com.android.art/javalib/apache-xml.jar",
        "apex/com.android.i18n/javalib/core-icu4j.jar",
        "apex/com.android.conscrypt/javalib/conscrypt.jar",
    }, art_runtime);
    dexopt.addArg("--runtime-arg");
    dexopt.addArg("-Xbootclasspath-locations:/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar");
    dexopt.addArgs(&.{ "--no-generate-mini-debug-info", "-j4" });
    dexopt.filterStderr(" Compilation of ");
    dexopt.run.setName("dex2oat framework.jar (speed)");
    d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));

    // An APK library fixture exercises stock parent, delegate-last, and
    // explicitly declared shared-library resolution across private runtimes.
    const fixture_javac = b.addSystemCommand(&.{"javac"});
    fixture_javac.addPrefixedDirectoryArg("--system=", sysmod_dir);
    fixture_javac.addArgs(&.{ "-source", "21", "-target", "21", "-d" });
    const fixture_classes = fixture_javac.addOutputDirectoryArg("kotlin-shadow-classes");
    fixture_javac.addFileArg(b.path("testdata/kotlin-shadow/kotlin/KotlinVersion.java"));
    fixture_javac.addFileArg(b.path("testdata/kotlin-shadow/fixture/NewerKotlinCaller.java"));

    const fixture_kotlinc = command.create(b);
    fixture_kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    fixture_kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath" });
    fixture_kotlinc.addJoinedPaths(":", &.{ core_plus, kotlin_stdlib, classes_jar });
    fixture_kotlinc.addArg("-d");
    const fixture_platform_classes = fixture_kotlinc.addOutputDirectoryArg("platform-shadow-classes");
    fixture_kotlinc.addFileArg(b.path("testdata/kotlin-shadow/fixture/PlatformReference.kt"));
    kotlinc_inputs.addInputs(fixture_kotlinc.run);

    // Bundle the actual pinned platform class, rather than inventing an Android
    // replacement for the fixture. A stock loader must preserve platform identity
    // even when an APK accidentally includes these byte-identical definitions.
    const fixture_platform_copy = extractJarPackage(
        b,
        classes_jar,
        "android/net/Uri*.class",
        "platform-shadow-original-classes",
    );
    const fixture_jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const fixture_classes_jar = fixture_jar.addOutputFileArg("kotlin-shadow-classes.jar");
    fixture_jar.addArg("-C");
    fixture_jar.addDirectoryArg(fixture_classes);
    fixture_jar.addArg(".");
    fixture_jar.addArg("-C");
    fixture_jar.addDirectoryArg(fixture_platform_classes);
    fixture_jar.addArg(".");
    fixture_jar.addArg("-C");
    fixture_jar.addDirectoryArg(fixture_platform_copy);
    fixture_jar.addArg(".");

    const fixture_d8 = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    fixture_d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    fixture_d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    fixture_d8.addFileArg(core_plus);
    fixture_d8.addArg("--lib");
    fixture_d8.addFileArg(classes_jar);
    fixture_d8.addArg("--output");
    const kotlin_shadow_fixture = fixture_d8.addOutputFileArg("kotlin-shadow.jar");
    fixture_d8.addFileArg(fixture_classes_jar);

    const download_provider = @import("download_provider.zig").build(b, aosp, classes_jar, sysmod_dir, core_plus, res_apk);

    const java_step = b.step("java", "Build the framework jar (kotlinc + javac + d8), framework-res.apk and generated Java");
    inline for (.{ classpath_jar, app_context_jar, framework_odex, framework_vdex, res_apk, compat_config }) |path| {
        path.addStepDependencies(java_step);
    }
    return .{
        .classes_jar = classes_jar,
        .static_sources = .{ sources.java_rsp, sources.kotlin_rsp, sources.annotations_rsp, sources.permission_kotlin_rsp },
        .compile_boot_jar = core_plus,
        .classpath_jar = classpath_jar,
        .compat_config = compat_config,
        .app_context_jar = app_context_jar,
        .platform_api_classes = platform_api_classes,
        .framework_odex = framework_odex,
        .framework_vdex = framework_vdex,
        .kotlin_shadow_fixture = kotlin_shadow_fixture,
        .resources = o.generated.resources,
        .fixtures = o.generated.fixtures,
        .java_step = java_step,
        .system_apps = systemApps(b, aosp, .{ .classes = classes_jar, .core = core_plus, .system_modules = sysmod_dir, .resources = res_apk },
            download_provider, @import("desktop_transfer_provider.zig").build(b, aosp, classes_jar, core_plus, res_apk)),
    };
}

/// The system image PackageManagerService requires (pinned getRequired*LPr
/// lookups and config_servicesExtensionPackage) plus the host's own system apps.
/// frameworks/base/packages/PackageInstaller (android_app PackageInstaller):
/// the stock installer and uninstaller UI over its androidx static_libs.
fn packageInstaller(b: *std.Build, aosp: *std.Build.Dependency, framework: @import("system_apks.zig").Framework) LazyPath {
    const libraries = @import("android_libraries.zig").build(b, aosp, framework.core, framework.classes);
    var sources = SourceFiles.Collector.init(b);
    sources.addTree(SourceFiles.Root.dependency(b, aosp, "frameworks/base/packages/PackageInstaller/src"), .{});
    const androidx = "prebuilts/sdk/current/androidx/m2repository/androidx/";
    const stdlib_dir = "external/kotlinc/lib/";
    var jars: std.ArrayList(LazyPath) = .empty;
    jars.appendSlice(b.allocator, libraries.jars) catch @panic("OOM");
    jars.append(b.allocator, aosp.path(stdlib_dir ++ "parcelize-runtime.jar")) catch @panic("OOM");
    return @import("system_apks.zig").build(b, aosp, framework, .{
        .name = "PackageInstaller",
        .manifest = aosp.path("frameworks/base/packages/PackageInstaller/AndroidManifest.xml"),
        .res = aosp.path("frameworks/base/packages/PackageInstaller/res"),
        .java = paths(b, sources.select(".java")),
        .kotlin = paths(b, sources.select(".kt")),
        .kotlin_plugins = &.{aosp.path(stdlib_dir ++ "parcelize-compiler.jar")},
        .jars = jars.items,
        .java_resources = libraries.resources,
        // Pinned prebuilts/sdk Android.bp static_libs closure, dependencies first.
        .libraries = &.{
            androidx ++ "annotation/annotation-jvm/1.9.0-rc01/annotation-jvm-1.9.0-rc01.jar",
            androidx ++ "annotation/annotation-experimental/1.5.0-alpha01/annotation-experimental-1.5.0-alpha01.aar",
            androidx ++ "collection/collection-jvm/1.5.0-beta03/collection-jvm-1.5.0-beta03.jar",
            androidx ++ "concurrent/concurrent-futures/1.3.0-alpha01/concurrent-futures-1.3.0-alpha01.jar",
            androidx ++ "core/core-viewtree/1.1.0-alpha01/core-viewtree-1.1.0-alpha01.aar",
            androidx ++ "interpolator/interpolator/1.1.0-alpha01/interpolator-1.1.0-alpha01.aar",
            androidx ++ "arch/core/core-common/2.3.0-alpha01/core-common-2.3.0-alpha01.jar",
            androidx ++ "arch/core/core-runtime/2.3.0-alpha01/core-runtime-2.3.0-alpha01.aar",
            androidx ++ "lifecycle/lifecycle-common-jvm/2.9.0-alpha12/lifecycle-common-jvm-2.9.0-alpha12.jar",
            androidx ++ "tracing/tracing-android/1.3.0-beta01/tracing-android-1.3.0-beta01.aar",
            androidx ++ "startup/startup-runtime/1.2.0-alpha02/startup-runtime-1.2.0-alpha02.aar",
            androidx ++ "profileinstaller/profileinstaller/1.5.0-alpha01/profileinstaller-1.5.0-alpha01.aar",
            androidx ++ "lifecycle/lifecycle-runtime-android/2.9.0-alpha12/lifecycle-runtime-android-2.9.0-alpha12.aar",
            androidx ++ "versionedparcelable/versionedparcelable/1.2.0-rc01/versionedparcelable-1.2.0-rc01.aar",
            androidx ++ "core/core/1.16.0-beta01/core-1.16.0-beta01.aar",
            androidx ++ "core/core-ktx/1.16.0-beta01/core-ktx-1.16.0-beta01.aar",
            androidx ++ "lifecycle/lifecycle-viewmodel-android/2.9.0-alpha12/lifecycle-viewmodel-android-2.9.0-alpha12.aar",
            androidx ++ "lifecycle/lifecycle-livedata-core/2.9.0-alpha12/lifecycle-livedata-core-2.9.0-alpha12.aar",
            androidx ++ "savedstate/savedstate-android/1.3.0-alpha11/savedstate-android-1.3.0-alpha11.aar",
            androidx ++ "lifecycle/lifecycle-viewmodel-savedstate-android/2.9.0-alpha12/lifecycle-viewmodel-savedstate-android-2.9.0-alpha12.aar",
            androidx ++ "activity/activity/1.11.0-alpha01/activity-1.11.0-alpha01.aar",
            androidx ++ "loader/loader/1.2.0-alpha01/loader-1.2.0-alpha01.aar",
            androidx ++ "customview/customview/1.2.0-alpha03/customview-1.2.0-alpha03.aar",
            androidx ++ "viewpager/viewpager/1.1.0-alpha02/viewpager-1.1.0-alpha02.aar",
            androidx ++ "fragment/fragment/1.9.0-alpha01/fragment-1.9.0-alpha01.aar",
            androidx ++ "vectordrawable/vectordrawable/1.2.0/vectordrawable-1.2.0.aar",
            androidx ++ "vectordrawable/vectordrawable-animated/1.2.0/vectordrawable-animated-1.2.0.aar",
            androidx ++ "cursoradapter/cursoradapter/1.1.0-alpha01/cursoradapter-1.1.0-alpha01.aar",
            androidx ++ "drawerlayout/drawerlayout/1.3.0-alpha01/drawerlayout-1.3.0-alpha01.aar",
            androidx ++ "lifecycle/lifecycle-process/2.9.0-alpha12/lifecycle-process-2.9.0-alpha12.aar",
            androidx ++ "emoji2/emoji2/1.5.0-rc01/emoji2-1.5.0-rc01.aar",
            androidx ++ "emoji2/emoji2-views-helper/1.5.0-rc01/emoji2-views-helper-1.5.0-rc01.aar",
            androidx ++ "resourceinspection/resourceinspection-annotation/1.1.0-alpha01/resourceinspection-annotation-1.1.0-alpha01.jar",
            androidx ++ "appcompat/appcompat/1.8.0-alpha01/appcompat-1.8.0-alpha01.aar",
            androidx ++ "customview/customview-poolingcontainer/1.1.0-alpha01/customview-poolingcontainer-1.1.0-alpha01.aar",
            androidx ++ "recyclerview/recyclerview/1.5.0-alpha01/recyclerview-1.5.0-alpha01.aar",
            androidx ++ "leanback/leanback-grid/1.0.0-beta01/leanback-grid-1.0.0-beta01.aar",
            androidx ++ "media/media/1.7.0-alpha02/media-1.7.0-alpha02.aar",
            androidx ++ "leanback/leanback/1.2.0-beta01/leanback-1.2.0-beta01.aar",
            androidx ++ "lifecycle/lifecycle-livedata-core-ktx/2.9.0-alpha12/lifecycle-livedata-core-ktx-2.9.0-alpha12.aar",
            androidx ++ "lifecycle/lifecycle-livedata/2.9.0-alpha12/lifecycle-livedata-2.9.0-alpha12.aar",
            androidx ++ "lifecycle/lifecycle-service/2.9.0-alpha12/lifecycle-service-2.9.0-alpha12.aar",
            "prebuilts/sdk/current/androidx-legacy/m2repository/androidx/lifecycle/lifecycle-extensions/2.3.0-alpha01/lifecycle-extensions-2.3.0-alpha01.aar",
        },
    });
}

fn paths(b: *std.Build, files: SourceFiles.Files) []const LazyPath {
    const result = b.allocator.alloc(LazyPath, files.items.len) catch @panic("OOM");
    for (files.items, result) |file, *path| path.* = file.path;
    return result;
}

fn systemApps(b: *std.Build, aosp: *std.Build.Dependency, framework: @import("system_apks.zig").Framework,
    download_provider: LazyPath, desktop_transfer_provider: LazyPath) []const @import("system_apks.zig").SystemApp {
    const apps = @import("system_apks.zig");
    const list = [_]apps.SystemApp{
        .{ .name = "ExtShared", .privileged = false, .apk = apps.build(b, aosp, framework, .{
            .name = "ExtShared",
            .manifest = aosp.path("frameworks/base/packages/ExtShared/AndroidManifest.xml"),
            .res = aosp.path("frameworks/base/packages/ExtShared/res"),
            .java = &.{aosp.path("frameworks/base/packages/ExtShared/src/android/ext/shared/Version.java")},
            .shared_lib = true,
        }) },
        .{ .name = "ExtServices", .privileged = true, .apk = apps.build(b, aosp, framework, .{
            .name = "ExtServices",
            .manifest = b.path("testdata/ext-services/AndroidManifest.xml"),
        }) },
        .{ .name = "MediaProvider", .privileged = true, .apk = apps.build(b, aosp, framework, .{
            .name = "MediaProvider",
            .manifest = b.path("testdata/media-provider/AndroidManifest.xml"),
            .kotlin = &.{b.path("src/java/dev/artzig/mediaprovider/MediaProviderStandIns.kt")},
        }) },
        .{ .name = "PermissionController", .privileged = true, .apk = apps.build(b, aosp, framework, .{
            .name = "PermissionController",
            .manifest = b.path("testdata/permission-controller/AndroidManifest.xml"),
            .kotlin = &.{b.path("src/java/dev/artzig/permissioncontroller/PermissionControllerActivities.kt")},
        }) },
        .{ .name = "Shell", .privileged = true, .apk = apps.build(b, aosp, framework, .{
            .name = "Shell",
            .manifest = aosp.path("frameworks/base/packages/Shell/AndroidManifest.xml"),
            .res = aosp.path("frameworks/base/packages/Shell/res"),
            // Shell flags_packages values matching the framework's generated aconfig flags.
            .feature_flags = "android.permission.flags.replace_body_sensor_permission_enabled:ro=false,android.security.protect_device_config_flags:ro=false",
        }) },
        .{ .name = "PackageInstaller", .privileged = true, .apk = packageInstaller(b, aosp, framework) },
        .{ .name = "Settings", .privileged = false, .apk = apps.build(b, aosp, framework, .{
            .name = "Settings",
            .manifest = b.path("testdata/settings-standin/AndroidManifest.xml"),
            .kotlin = &.{b.path("src/java/dev/artzig/settings/ManageUnknownAppSourcesActivity.kt")},
        }) },
        .{ .name = "SdkSandbox", .privileged = false, .apk = apps.build(b, aosp, framework, .{
            .name = "SdkSandbox",
            .manifest = b.path("testdata/sdk-sandbox-standin/AndroidManifest.xml"),
        }) },
        .{ .name = "DownloadProvider", .privileged = true, .apk = download_provider },
        .{ .name = "DesktopTransferProvider", .privileged = false, .apk = desktop_transfer_provider },
    };
    return b.allocator.dupe(apps.SystemApp, &list) catch @panic("OOM");
}
