//! Host-JDK Kotlin test-control client, CLI, and JUnit integration tests.
const std = @import("std");
const SourceFiles = @import("artzig").SourceFiles;
const project_sources = @import("project_jvm_sources.zig");

pub const Result = struct {
    install_step: *std.Build.Step,
};

fn javac(b: *std.Build) *std.Build.Step.Run {
    return b.addSystemCommand(&.{
        "bash",
        "-c",
        \\exec javac "$@" 2> >(grep -F -v -e 'Note: Some input files use or override a deprecated API.' -e 'Note: Recompile with -Xlint:deprecation for details.' -e 'Note: Some input files use unchecked or unsafe operations.' -e 'Note: Recompile with -Xlint:unchecked for details.' >&2)
        ,
        "bash",
    });
}

fn jarClasses(
    b: *std.Build,
    name: []const u8,
    directories: []const std.Build.LazyPath,
) std.Build.LazyPath {
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const output = jar.addOutputFileArg(name);
    for (directories) |directory| {
        jar.addArg("-C");
        jar.addDirectoryArg(directory);
        jar.addArg(".");
    }
    return output;
}

pub fn build(b: *std.Build, aosp: *std.Build.Dependency) Result {
    var hamcrest_collector = SourceFiles.Collector.init(b);
    hamcrest_collector.addTree(
        SourceFiles.Root.dependency(b, aosp, "external/hamcrest/hamcrest-core/src/main/java"),
        .{ .extensions = &.{".java"} },
    );
    const hamcrest_sources = hamcrest_collector.select(".java");

    var junit_collector = SourceFiles.Collector.init(b);
    junit_collector.addTree(
        SourceFiles.Root.dependency(b, aosp, "external/junit/src/main/java"),
        .{ .extensions = &.{".java"} },
    );
    const junit_sources = junit_collector.select(".java");

    const project_root = SourceFiles.Root.project(b, "");
    var testkit_collector = SourceFiles.Collector.init(b);
    for (project_sources.testkit) |path| testkit_collector.addFile(project_root, path);
    const testkit_sources = testkit_collector.select(".kt");
    var test_collector = SourceFiles.Collector.init(b);
    for (project_sources.tests) |path| test_collector.addFile(project_root, path);
    const test_sources = test_collector.select(".kt");

    const hamcrest_javac = javac(b);
    hamcrest_javac.addArgs(&.{ "--release", "11", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-d" });
    const hamcrest_classes = hamcrest_javac.addOutputDirectoryArg("hamcrest-classes");
    hamcrest_javac.addPrefixedFileArg("@", hamcrest_sources.responseFile(b, "hamcrest-sources.rsp"));
    hamcrest_sources.addInputs(hamcrest_javac);
    hamcrest_javac.setName("compile testkit Hamcrest dependency");

    const junit_javac = javac(b);
    junit_javac.addArgs(&.{ "--release", "11", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-classpath" });
    junit_javac.addDirectoryArg(hamcrest_classes);
    junit_javac.addArg("-d");
    const junit_classes = junit_javac.addOutputDirectoryArg("junit-classes");
    junit_javac.addPrefixedFileArg("@", junit_sources.responseFile(b, "junit-sources.rsp"));
    junit_sources.addInputs(junit_javac);
    junit_javac.setName("compile testkit JUnit dependency");

    const junit_hamcrest_jar = jarClasses(
        b,
        "junit-hamcrest.jar",
        &.{ hamcrest_classes, junit_classes },
    );

    const kotlin_root = SourceFiles.Root.dependency(b, aosp, "external/kotlinc");
    const kotlin_stdlib = kotlin_root.path.path(b, "lib/kotlin-stdlib.jar");
    const kotlin_stdlib_jdk7 = kotlin_root.path.path(b, "lib/kotlin-stdlib-jdk7.jar");
    const kotlin_stdlib_jdk8 = kotlin_root.path.path(b, "lib/kotlin-stdlib-jdk8.jar");
    const kotlin_test = kotlin_root.path.path(b, "lib/kotlin-test.jar");
    const kotlin_test_junit = kotlin_root.path.path(b, "lib/kotlin-test-junit.jar");
    const gson = aosp.path("prebuilts/tools/common/m2/repository/com/google/code/gson/gson/2.9.1/gson-2.9.1.jar");

    var compiler_collector = SourceFiles.Collector.init(b);
    compiler_collector.addTree(kotlin_root.child(b, "bin"), .{ .extensions = &.{""} });
    compiler_collector.addTree(kotlin_root.child(b, "lib"), .{ .extensions = &.{""} });
    compiler_collector.addFile(kotlin_root, "build.txt");
    const compiler_inputs = compiler_collector.select(null);

    const compile_testkit = b.addSystemCommand(&.{
        "sh",
        "-c",
        \\exec "$1" -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 -java-parameters -classpath "$2:$3:$4:$5:$6:$7:$8" -d "$9" "@${10}"
        ,
        "sh",
    });
    compile_testkit.addFileArg(kotlin_root.path.path(b, "bin/kotlinc"));
    inline for (.{ kotlin_stdlib, kotlin_stdlib_jdk7, kotlin_stdlib_jdk8, gson, junit_hamcrest_jar, kotlin_test, kotlin_test_junit }) |dependency| {
        compile_testkit.addFileArg(dependency);
    }
    const testkit_classes = compile_testkit.addOutputDirectoryArg("testkit-classes");
    compile_testkit.addFileArg(testkit_sources.responseFile(b, "testkit-sources.rsp"));
    testkit_sources.addInputs(compile_testkit);
    compiler_inputs.addInputs(compile_testkit);
    compile_testkit.setEnvironmentVariable("JAVA_OPTS", "-Xmx1024M -Xms128M");
    compile_testkit.setName("compile Kotlin testkit");
    const testkit_jar = jarClasses(b, "testkit.jar", &.{testkit_classes});

    const compile_tests = b.addSystemCommand(&.{
        "sh",
        "-c",
        \\exec "$1" -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 -java-parameters -Xfriend-paths="$9" -classpath "$2:$3:$4:$5:$6:$7:$8:$9" -d "${10}" "@${11}"
        ,
        "sh",
    });
    compile_tests.addFileArg(kotlin_root.path.path(b, "bin/kotlinc"));
    inline for (.{ kotlin_stdlib, kotlin_stdlib_jdk7, kotlin_stdlib_jdk8, gson, junit_hamcrest_jar, kotlin_test, kotlin_test_junit }) |dependency| {
        compile_tests.addFileArg(dependency);
    }
    compile_tests.addFileArg(testkit_jar);
    const test_classes = compile_tests.addOutputDirectoryArg("test-classes");
    compile_tests.addFileArg(test_sources.responseFile(b, "test-sources.rsp"));
    test_sources.addInputs(compile_tests);
    compiler_inputs.addInputs(compile_tests);
    compile_tests.setEnvironmentVariable("JAVA_OPTS", "-Xmx1024M -Xms128M");
    compile_tests.setName("compile Kotlin testkit tests");
    const tests_jar = jarClasses(b, "tests.jar", &.{test_classes});

    const install_testkit = b.addInstallFileWithDir(testkit_jar, .lib, "azk-test/testkit.jar");
    inline for (.{
        .{ tests_jar, "azk-test/tests.jar" },
        .{ junit_hamcrest_jar, "azk-test/junit-hamcrest.jar" },
        .{ gson, "azk-test/gson.jar" },
        .{ kotlin_stdlib, "azk-test/kotlin-stdlib.jar" },
        .{ kotlin_stdlib_jdk7, "azk-test/kotlin-stdlib-jdk7.jar" },
        .{ kotlin_stdlib_jdk8, "azk-test/kotlin-stdlib-jdk8.jar" },
        .{ kotlin_test, "azk-test/kotlin-test.jar" },
        .{ kotlin_test_junit, "azk-test/kotlin-test-junit.jar" },
    }) |entry| {
        install_testkit.step.dependOn(&b.addInstallFileWithDir(entry[0], .lib, entry[1]).step);
    }
    return .{ .install_step = &install_testkit.step };
}
