//! Cacheable construction of the minimal java.base system image used by javac.
const std = @import("std");

pub fn build(
    b: *std.Build,
    vendor: std.Build.LazyPath,
    core_jar: std.Build.LazyPath,
    class_directories: []const std.Build.LazyPath,
    command_runner_source: std.Build.LazyPath,
) std.Build.LazyPath {
    const module_info = b.addSystemCommand(&.{"bash"});
    module_info.addFileArg(vendor.path(b, "build/soong/scripts/jars-to-module-info-java.sh"));
    module_info.addArg("java.base");
    module_info.addFileArg(core_jar);
    module_info.setCwd(vendor);
    const module_info_java = module_info.captureStdOut(.{ .basename = "module-info.java" });

    const compile = b.addSystemCommand(&.{ "javac", "--system=none" });
    compile.addPrefixedFileArg("--patch-module=java.base=", core_jar);
    compile.addFileArg(module_info_java);
    compile.addArg("-d");
    const module_info_classes = compile.addOutputDirectoryArg("module-info-classes");

    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const module_jar = jar.addOutputFileArg("java.base.jar");
    for (class_directories) |classes| {
        jar.addArg("-C");
        jar.addDirectoryArg(classes);
        jar.addArg(".");
    }
    jar.addArg("-C");
    jar.addDirectoryArg(module_info_classes);
    jar.addArg("module-info.class");

    const jmod = b.addSystemCommand(&.{ "jmod", "create", "--module-version", "21", "--target-platform", "LINUX-OTHER", "--class-path" });
    jmod.addFileArg(module_jar);
    const java_base_jmod = jmod.addOutputFileArg("java.base.jmod");

    const jmod_tree = b.addWriteFiles();
    _ = jmod_tree.addCopyFile(java_base_jmod, "java.base.jmod");

    const runner = b.addExecutable(.{
        .name = "build-command",
        .root_module = b.createModule(.{
            .root_source_file = command_runner_source,
            .target = b.graph.host,
            .optimize = .ReleaseSafe,
        }),
    });
    const jlink = b.addRunArtifact(runner);
    jlink.addArgs(&.{ "--arg", "jlink", "--arg", "--module-path", "--arg" });
    jlink.addDirectoryArg(jmod_tree.getDirectory());
    jlink.addArgs(&.{ "--arg", "--add-modules", "--arg", "java.base", "--arg", "--output", "--child", "image" });
    const image_parent = jlink.addOutputDirectoryArg("system-modules-output");
    jlink.addArgs(&.{ "--arg", "--disable-plugin", "--arg", "system-modules" });
    jlink.setName("create java.base system image");
    const image = image_parent.path(b, "image");

    const java_home = b.graph.environ_map.get("JAVA_HOME") orelse
        std.debug.panic("JAVA_HOME is required to construct javac system modules", .{});
    const result = b.addWriteFiles();
    _ = result.addCopyDirectory(image, "", .{});
    _ = result.addCopyFile(b.graph.cwdRelativePath(b.pathJoin(&.{ java_home, "lib/jrt-fs.jar" })), "lib/jrt-fs.jar");
    return result.getDirectory();
}
