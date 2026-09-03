//! Host command adapter shared by the root and Artzig build graphs for
//! arguments that Step.Run cannot model.
const std = @import("std");
const Io = std.Io;

const Parsed = struct {
    argv: []const []const u8,
    environment: []const Environment,
    filters: []const []const u8,
    clean_paths: []const []const u8,
};

const Environment = struct {
    name: []const u8,
    value: []const u8,
    /// The value is a path: a relative value is resolved against the working
    /// directory before the child runs, so consumers that persist locations
    /// (ART class-loader contexts, the install tree) see one absolute spelling.
    path: bool = false,
    /// Prepend the (resolved) value to the variable's inherited value with a
    /// ':' separator instead of replacing it, as for LD_LIBRARY_PATH.
    prepend: bool = false,
};

fn parse(allocator: std.mem.Allocator, args: []const []const u8) !Parsed {
    var argv: std.ArrayList([]const u8) = .empty;
    var environment: std.ArrayList(Environment) = .empty;
    var filters: std.ArrayList([]const u8) = .empty;
    var clean_paths: std.ArrayList([]const u8) = .empty;
    var index: usize = 1;
    while (index < args.len) {
        const directive = args[index];
        index += 1;
        if (std.mem.eql(u8, directive, "--arg")) {
            if (index == args.len) return error.MissingArgument;
            try argv.append(allocator, args[index]);
            index += 1;
        } else if (std.mem.eql(u8, directive, "--join")) {
            if (index + 2 > args.len) return error.MissingArgument;
            const separator = args[index];
            const count = try std.fmt.parseUnsigned(usize, args[index + 1], 10);
            index += 2;
            if (index + count > args.len) return error.MissingArgument;
            try argv.append(allocator, try std.mem.join(allocator, separator, args[index .. index + count]));
            index += count;
        } else if (std.mem.eql(u8, directive, "--join-prefix")) {
            if (index + 3 > args.len) return error.MissingArgument;
            const prefix = args[index];
            const separator = args[index + 1];
            const count = try std.fmt.parseUnsigned(usize, args[index + 2], 10);
            index += 3;
            if (index + count > args.len) return error.MissingArgument;
            const joined = try std.mem.join(allocator, separator, args[index .. index + count]);
            try argv.append(allocator, try std.mem.concat(allocator, u8, &.{ prefix, joined }));
            index += count;
        } else if (std.mem.eql(u8, directive, "--prefix")) {
            if (index + 2 > args.len) return error.MissingArgument;
            try argv.append(allocator, try std.mem.concat(allocator, u8, args[index .. index + 2]));
            index += 2;
        } else if (std.mem.eql(u8, directive, "--child")) {
            if (index + 2 > args.len) return error.MissingArgument;
            const child = try std.fs.path.join(allocator, &.{ args[index + 1], args[index] });
            try argv.append(allocator, child);
            try clean_paths.append(allocator, child);
            index += 2;
        } else if (std.mem.eql(u8, directive, "--child-path")) {
            if (index + 2 > args.len) return error.MissingArgument;
            try argv.append(allocator, try std.fs.path.join(allocator, &.{ args[index + 1], args[index] }));
            index += 2;
        } else if (std.mem.eql(u8, directive, "--prefix-child-path")) {
            if (index + 3 > args.len) return error.MissingArgument;
            const child = try std.fs.path.join(allocator, &.{ args[index + 2], args[index + 1] });
            try argv.append(allocator, try std.mem.concat(allocator, u8, &.{ args[index], child }));
            index += 3;
        } else if (std.mem.eql(u8, directive, "--join-child-paths")) {
            if (index + 3 > args.len) return error.MissingArgument;
            const prefix = args[index];
            const separator = args[index + 1];
            const count = try std.fmt.parseUnsigned(usize, args[index + 2], 10);
            index += 3;
            if (index + count + 1 > args.len) return error.MissingArgument;
            const parent = args[index + count];
            var children = try allocator.alloc([]const u8, count);
            for (args[index .. index + count], 0..) |child, child_index| {
                children[child_index] = try std.fs.path.join(allocator, &.{ parent, child });
            }
            const joined = try std.mem.join(allocator, separator, children);
            try argv.append(allocator, try std.mem.concat(allocator, u8, &.{ prefix, joined }));
            index += count + 1;
        } else if (std.mem.eql(u8, directive, "--env")) {
            if (index + 2 > args.len) return error.MissingArgument;
            try environment.append(allocator, .{ .name = args[index], .value = args[index + 1] });
            index += 2;
        } else if (std.mem.eql(u8, directive, "--env-path")) {
            if (index + 2 > args.len) return error.MissingArgument;
            try environment.append(allocator, .{ .name = args[index], .value = args[index + 1], .path = true });
            index += 2;
        } else if (std.mem.eql(u8, directive, "--env-path-prepend")) {
            if (index + 2 > args.len) return error.MissingArgument;
            try environment.append(allocator, .{ .name = args[index], .value = args[index + 1], .path = true, .prepend = true });
            index += 2;
        } else if (std.mem.eql(u8, directive, "--env-child")) {
            if (index + 3 > args.len) return error.MissingArgument;
            try environment.append(allocator, .{
                .name = args[index],
                .value = try std.fs.path.join(allocator, &.{ args[index + 2], args[index + 1] }),
                .path = true,
            });
            index += 3;
        } else if (std.mem.eql(u8, directive, "--stderr-filter")) {
            if (index == args.len) return error.MissingArgument;
            try filters.append(allocator, args[index]);
            index += 1;
        } else if (std.mem.eql(u8, directive, "--passthru")) {
            try argv.appendSlice(allocator, args[index..]);
            index = args.len;
        } else {
            return error.UnknownDirective;
        }
    }
    if (argv.items.len == 0) return error.MissingCommand;
    return .{
        .argv = try argv.toOwnedSlice(allocator),
        .environment = try environment.toOwnedSlice(allocator),
        .filters = try filters.toOwnedSlice(allocator),
        .clean_paths = try clean_paths.toOwnedSlice(allocator),
    };
}

fn writeFiltered(io: Io, file: Io.File, text: []const u8, filters: []const []const u8) !void {
    var lines = std.mem.splitScalar(u8, text, '\n');
    while (lines.next()) |line| {
        if (line.len == 0) continue;
        if (isFilteredLine(line, filters)) continue;
        try file.writeStreamingAll(io, line);
        try file.writeStreamingAll(io, "\n");
    }
}

fn isFilteredLine(line: []const u8, filters: []const []const u8) bool {
    for (filters) |pattern| if (std.mem.indexOf(u8, line, pattern) != null) return true;
    return false;
}

fn exitCode(term: std.process.Child.Term) u8 {
    return switch (term) {
        .exited => |code| code,
        else => 1,
    };
}

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    const parsed = parse(init.arena.allocator(), args) catch |err| {
        try Io.File.stderr().writeStreamingAll(
            init.io,
            "usage: build-command (--arg VALUE | --join SEP COUNT VALUES... | --prefix PREFIX VALUE | --child NAME DIRECTORY | --child-path CHILD DIRECTORY | --prefix-child-path PREFIX CHILD DIRECTORY | --join-child-paths PREFIX SEP COUNT CHILDREN... DIRECTORY | --env NAME VALUE | --env-path NAME PATH | --env-path-prepend NAME PATH | --env-child NAME CHILD DIRECTORY | --stderr-filter TEXT | --passthru ...)...\n",
        );
        return err;
    };

    var environment = try init.minimal.environ.createMap(init.gpa);
    defer environment.deinit();
    var cwd_buffer: [std.fs.max_path_bytes]u8 = undefined;
    const cwd = cwd_buffer[0..try Io.Dir.cwd().realPathFile(init.io, ".", &cwd_buffer)];
    for (parsed.environment) |entry| {
        const resolved = if (entry.path and !std.fs.path.isAbsolute(entry.value))
            try std.fs.path.resolve(init.arena.allocator(), &.{ cwd, entry.value })
        else
            entry.value;
        const inherited = if (entry.prepend) environment.get(entry.name) else null;
        const value = if (inherited != null and inherited.?.len != 0)
            try std.mem.concat(init.arena.allocator(), u8, &.{ resolved, ":", inherited.? })
        else
            resolved;
        try environment.put(entry.name, value);
    }
    for (parsed.clean_paths) |path| try Io.Dir.cwd().deleteTree(init.io, path);

    const result = try std.process.run(init.gpa, init.io, .{
        .argv = parsed.argv,
        .environ_map = &environment,
    });
    defer init.gpa.free(result.stdout);
    defer init.gpa.free(result.stderr);
    try Io.File.stdout().writeStreamingAll(init.io, result.stdout);
    try writeFiltered(init.io, Io.File.stderr(), result.stderr, parsed.filters);
    std.process.exit(exitCode(result.term));
}

test "joins path arguments without changing their order" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--arg", "java", "--join", ":", "3", "one", "two", "three",
    });
    const joined = parsed.argv[1];
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(joined);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("java", parsed.argv[0]);
    try std.testing.expectEqualStrings("one:two:three", parsed.argv[1]);
}

test "parses environment and stderr filters" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--env", "ROOT", "/tmp/root", "--stderr-filter", "known note", "--arg", "true",
    });
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("ROOT", parsed.environment[0].name);
    try std.testing.expectEqualStrings("/tmp/root", parsed.environment[0].value);
    try std.testing.expectEqualStrings("known note", parsed.filters[0]);
}

test "marks path environment values for resolution against the working directory" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--env-path", "ARTZIG_INSTALL_ROOT", "./.zig-cache/o/tree", "--env", "JAVA_OPTS", "-Xmx1g", "--arg", "true",
    });
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("./.zig-cache/o/tree", parsed.environment[0].value);
    try std.testing.expect(parsed.environment[0].path);
    try std.testing.expect(!parsed.environment[1].path);
}

test "marks prepended path environment values" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--env-path-prepend", "LD_LIBRARY_PATH", "./lib64", "--arg", "true",
    });
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("./lib64", parsed.environment[0].value);
    try std.testing.expect(parsed.environment[0].path);
    try std.testing.expect(parsed.environment[0].prepend);
}

test "derives an environment path below a declared directory" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--env-child", "LD_LIBRARY_PATH", "lib64", "/cache/runtime", "--arg", "true",
    });
    const value = parsed.environment[0].value;
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(value);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("LD_LIBRARY_PATH", parsed.environment[0].name);
    try std.testing.expectEqualStrings("/cache/runtime/lib64", value);
    try std.testing.expect(parsed.environment[0].path);
}

test "derives and joins child arguments from one declared directory" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const parsed = try parse(arena.allocator(), &.{
        "build-command",
        "--child-path",
        "bin/tool",
        "/cache/runtime",
        "--prefix-child-path",
        "--image=",
        "boot/boot.art",
        "/cache/runtime",
        "--join-child-paths",
        "-Xbootclasspath:",
        ":",
        "2",
        "a.jar",
        "b.jar",
        "/cache/runtime",
    });
    try std.testing.expectEqualStrings("/cache/runtime/bin/tool", parsed.argv[0]);
    try std.testing.expectEqualStrings("--image=/cache/runtime/boot/boot.art", parsed.argv[1]);
    try std.testing.expectEqualStrings("-Xbootclasspath:/cache/runtime/a.jar:/cache/runtime/b.jar", parsed.argv[2]);
}

test "places a command output below a declared parent directory" {
    const parsed = try parse(std.testing.allocator, &.{
        "build-command", "--arg", "jlink", "--child", "image", "/cache/output",
    });
    const child = parsed.argv[1];
    defer std.testing.allocator.free(parsed.argv);
    defer std.testing.allocator.free(child);
    defer std.testing.allocator.free(parsed.environment);
    defer std.testing.allocator.free(parsed.filters);
    defer std.testing.allocator.free(parsed.clean_paths);
    try std.testing.expectEqualStrings("/cache/output/image", parsed.argv[1]);
}

test "stderr filtering is narrow and unmatched lines survive" {
    const filters = [_][]const u8{"known compiler note"};
    try std.testing.expect(isFilteredLine("prefix: known compiler note", &filters));
    try std.testing.expect(!isFilteredLine("a different warning", &filters));
}

test "child exit status propagates" {
    try std.testing.expectEqual(@as(u8, 23), exitCode(.{ .exited = 23 }));
    try std.testing.expectEqual(@as(u8, 1), exitCode(.{ .signal = .TERM }));
}
