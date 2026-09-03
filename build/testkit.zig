//! Host-JDK Kotlin test-control client, CLI, and JUnit integration tests.
const std = @import("std");
const SourceFiles = @import("artzig").SourceSet;
const project_sources = @import("project_jvm_sources.zig");
const command = @import("command.zig");

pub const Result = struct {
    jars: Jars,
};

pub const Jars = struct {
    testkit: std.Build.LazyPath,
    tests: std.Build.LazyPath,
    junit_hamcrest: std.Build.LazyPath,
    gson: std.Build.LazyPath,
    kotlin_stdlib: std.Build.LazyPath,
    kotlin_stdlib_jdk7: std.Build.LazyPath,
    kotlin_stdlib_jdk8: std.Build.LazyPath,
    kotlin_test: std.Build.LazyPath,
    kotlin_test_junit: std.Build.LazyPath,

    pub fn all(jars: Jars) [9]std.Build.LazyPath {
        return .{ jars.testkit, jars.tests, jars.junit_hamcrest, jars.gson, jars.kotlin_stdlib, jars.kotlin_stdlib_jdk7, jars.kotlin_stdlib_jdk8, jars.kotlin_test, jars.kotlin_test_junit };
    }
};

fn javac(b: *std.Build) command.Command {
    const result = command.create(b);
    result.addArg("javac");
    inline for (.{
        "Note: Some input files use or override a deprecated API.",
        "Note: Recompile with -Xlint:deprecation for details.",
        "Note: Some input files use unchecked or unsafe operations.",
        "Note: Recompile with -Xlint:unchecked for details.",
    }) |pattern| result.filterStderr(pattern);
    return result;
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
    hamcrest_sources.addInputs(hamcrest_javac.run);
    hamcrest_javac.run.setName("compile testkit Hamcrest dependency");

    const junit_javac = javac(b);
    junit_javac.addArgs(&.{ "--release", "11", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-classpath" });
    junit_javac.addDirectoryArg(hamcrest_classes);
    junit_javac.addArg("-d");
    const junit_classes = junit_javac.addOutputDirectoryArg("junit-classes");
    junit_javac.addPrefixedFileArg("@", junit_sources.responseFile(b, "junit-sources.rsp"));
    junit_sources.addInputs(junit_javac.run);
    junit_javac.run.setName("compile testkit JUnit dependency");

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

    const compile_testkit = command.create(b);
    compile_testkit.addFileArg(kotlin_root.path.path(b, "bin/kotlinc"));
    compile_testkit.addArgs(&.{ "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-java-parameters", "-classpath" });
    compile_testkit.addJoinedPaths(":", &.{ kotlin_stdlib, kotlin_stdlib_jdk7, kotlin_stdlib_jdk8, gson, junit_hamcrest_jar, kotlin_test, kotlin_test_junit });
    compile_testkit.addArg("-d");
    const testkit_classes = compile_testkit.addOutputDirectoryArg("testkit-classes");
    compile_testkit.addPrefixedFileArg("@", testkit_sources.responseFile(b, "testkit-sources.rsp"));
    testkit_sources.addInputs(compile_testkit.run);
    compiler_inputs.addInputs(compile_testkit.run);
    compile_testkit.run.setEnvironmentVariable("JAVA_OPTS", "-Xmx1024M -Xms128M");
    compile_testkit.run.setName("compile Kotlin testkit");
    const testkit_jar = jarClasses(b, "testkit.jar", &.{testkit_classes});

    const compile_tests = command.create(b);
    compile_tests.addFileArg(kotlin_root.path.path(b, "bin/kotlinc"));
    compile_tests.addArgs(&.{ "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-java-parameters" });
    compile_tests.addPrefixedFileArg("-Xfriend-paths=", testkit_jar);
    compile_tests.addArg("-classpath");
    compile_tests.addJoinedPaths(":", &.{ kotlin_stdlib, kotlin_stdlib_jdk7, kotlin_stdlib_jdk8, gson, junit_hamcrest_jar, kotlin_test, kotlin_test_junit, testkit_jar });
    compile_tests.addArg("-d");
    const test_classes = compile_tests.addOutputDirectoryArg("test-classes");
    compile_tests.addPrefixedFileArg("@", test_sources.responseFile(b, "test-sources.rsp"));
    test_sources.addInputs(compile_tests.run);
    compiler_inputs.addInputs(compile_tests.run);
    compile_tests.run.setEnvironmentVariable("JAVA_OPTS", "-Xmx1024M -Xms128M");
    compile_tests.run.setName("compile Kotlin testkit tests");
    const tests_jar = jarClasses(b, "tests.jar", &.{test_classes});

    return .{
        .jars = .{
            .testkit = testkit_jar,
            .tests = tests_jar,
            .junit_hamcrest = junit_hamcrest_jar,
            .gson = gson,
            .kotlin_stdlib = kotlin_stdlib,
            .kotlin_stdlib_jdk7 = kotlin_stdlib_jdk7,
            .kotlin_stdlib_jdk8 = kotlin_stdlib_jdk8,
            .kotlin_test = kotlin_test,
            .kotlin_test_junit = kotlin_test_junit,
        },
    };
}
