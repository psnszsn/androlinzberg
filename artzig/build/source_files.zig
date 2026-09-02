//! Configure-time source discovery with explicit Zig build dependencies.
//!
//! Configure-time selection registers every declared file as a Run input.
//! Response files are only an argv-size mechanism.
const std = @import("std");
const Io = std.Io;

const Build = std.Build;
const LazyPath = Build.LazyPath;

pub const Root = struct {
    path: LazyPath,
    absolute: []const u8,

    pub fn project(b: *Build, sub_path: []const u8) Root {
        const project_root = b.root.toString(b.allocator) catch @panic("OOM");
        return .{
            .path = b.path(sub_path),
            .absolute = absolutePath(b, project_root, sub_path),
        };
    }

    pub fn dependency(b: *Build, dep: *Build.Dependency, sub_path: []const u8) Root {
        const dependency_root = dep.builder.root.toString(b.allocator) catch @panic("OOM");
        return .{
            .path = dep.path(sub_path),
            .absolute = absolutePath(b, dependency_root, sub_path),
        };
    }

    pub fn child(root: Root, b: *Build, sub_path: []const u8) Root {
        return .{
            .path = root.path.path(b, sub_path),
            .absolute = b.pathJoin(&.{ root.absolute, sub_path }),
        };
    }

    fn absolutePath(b: *Build, root: []const u8, sub_path: []const u8) []const u8 {
        const relative = if (sub_path.len == 0) root else b.pathJoin(&.{ root, sub_path });
        return Io.Dir.cwd().realPathFileAlloc(b.graph.io, relative, b.allocator) catch |err| {
            std.debug.panic("unable to resolve source root {s}: {t}", .{ relative, err });
        };
    }
};

pub const AddOptions = struct {
    max_depth: ?usize = null,
    extensions: []const []const u8 = &.{ ".java", ".kt" },
    not_paths: []const []const u8 = &.{},
    not_names: []const []const u8 = &.{},
};

pub const File = struct {
    path: LazyPath,
    absolute: []const u8,
};

pub const Files = struct {
    items: []const File,

    pub fn addInputs(files: Files, run: *Build.Step.Run) void {
        for (files.items) |file| run.addFileInput(file.path);
    }

    pub fn responseFile(files: Files, b: *Build, name: []const u8) LazyPath {
        var size: usize = 0;
        for (files.items) |file| size += file.absolute.len + 1;
        const contents = b.allocator.alloc(u8, size) catch @panic("OOM");
        var offset: usize = 0;
        for (files.items) |file| {
            @memcpy(contents[offset..][0..file.absolute.len], file.absolute);
            offset += file.absolute.len;
            contents[offset] = '\n';
            offset += 1;
        }
        const write = b.addWriteFiles();
        return write.add(name, contents);
    }
};

pub const Collector = struct {
    b: *Build,
    files: std.StringHashMapUnmanaged(File) = .empty,

    pub fn init(b: *Build) Collector {
        return .{ .b = b };
    }

    pub fn addFile(c: *Collector, root: Root, relative_path: []const u8) void {
        const absolute = c.b.pathJoin(&.{ root.absolute, relative_path });
        const result = c.files.getOrPut(c.b.allocator, absolute) catch @panic("OOM");
        if (result.found_existing) return;
        result.key_ptr.* = absolute;
        result.value_ptr.* = .{
            .path = root.path.path(c.b, relative_path),
            .absolute = absolute,
        };
    }

    pub fn addResponseFile(c: *Collector, source_root: Root, response_file: Root) void {
        c.b.dependOnFileContents(response_file.path);
        const contents = Io.Dir.cwd().readFileAlloc(
            c.b.graph.io,
            response_file.absolute,
            c.b.allocator,
            .limited(std.math.maxInt(u32)),
        ) catch |err| {
            std.debug.panic("unable to read source response file {s}: {t}", .{ response_file.absolute, err });
        };
        var lines = std.mem.tokenizeScalar(u8, contents, '\n');
        while (lines.next()) |raw_line| {
            const line = std.mem.trim(u8, raw_line, " \t\r");
            if (line.len == 0 or line[0] == '#') continue;
            c.addFile(source_root, line);
        }
    }

    pub fn addTree(c: *Collector, root: Root, options: AddOptions) void {
        var dir = Io.Dir.cwd().openDir(c.b.graph.io, root.absolute, .{ .iterate = true }) catch |err| {
            std.debug.panic("unable to open source directory {s}: {t}", .{ root.absolute, err });
        };
        defer dir.close(c.b.graph.io);
        var walker = dir.walk(c.b.allocator) catch @panic("OOM");
        defer walker.deinit();

        while (walker.next(c.b.graph.io) catch |err| {
            std.debug.panic("unable to walk source directory {s}: {t}", .{ root.absolute, err });
        }) |entry| {
            if (entry.kind == .directory) {
                if (options.max_depth) |max_depth| {
                    if (entry.depth() >= max_depth) {
                        walker.leave(c.b.graph.io);
                        continue;
                    }
                }
                continue;
            }
            if (entry.kind != .file and entry.kind != .sym_link) continue;
            if (options.max_depth) |max_depth| {
                if (entry.depth() > max_depth) continue;
            }
            if (!matchesAnySuffix(options.extensions, entry.basename)) continue;
            if (matchesAny(options.not_names, entry.basename)) continue;

            const absolute = c.b.pathJoin(&.{ root.absolute, entry.path });
            if (matchesAny(options.not_paths, absolute)) continue;
            const result = c.files.getOrPut(c.b.allocator, absolute) catch @panic("OOM");
            if (result.found_existing) continue;
            result.key_ptr.* = absolute;
            result.value_ptr.* = .{
                .path = root.path.path(c.b, entry.path),
                .absolute = absolute,
            };
        }
    }

    pub fn select(c: *const Collector, extension: ?[]const u8) Files {
        const selected = c.b.allocator.alloc(File, c.files.count()) catch @panic("OOM");
        var count: usize = 0;
        var values = c.files.valueIterator();
        while (values.next()) |file| {
            if (extension) |ext| {
                if (!std.mem.endsWith(u8, file.absolute, ext)) continue;
            }
            selected[count] = file.*;
            count += 1;
        }
        const result = selected[0..count];
        std.mem.sortUnstable(File, result, {}, lessThan);
        return .{ .items = result };
    }
};

fn lessThan(_: void, lhs: File, rhs: File) bool {
    return std.mem.order(u8, lhs.absolute, rhs.absolute) == .lt;
}

fn matchesAnySuffix(suffixes: []const []const u8, text: []const u8) bool {
    for (suffixes) |suffix| {
        if (std.mem.endsWith(u8, text, suffix)) return true;
    }
    return false;
}

fn matchesAny(patterns: []const []const u8, text: []const u8) bool {
    for (patterns) |pattern| {
        if (globMatch(pattern, text)) return true;
    }
    return false;
}

fn globMatch(pattern: []const u8, text: []const u8) bool {
    var pattern_index: usize = 0;
    var text_index: usize = 0;
    var star_index: ?usize = null;
    var retry_text_index: usize = 0;

    while (text_index < text.len) {
        if (pattern_index < pattern.len and
            (pattern[pattern_index] == text[text_index] or pattern[pattern_index] == '?'))
        {
            pattern_index += 1;
            text_index += 1;
        } else if (pattern_index < pattern.len and pattern[pattern_index] == '*') {
            star_index = pattern_index;
            pattern_index += 1;
            retry_text_index = text_index;
        } else if (star_index) |star| {
            pattern_index = star + 1;
            retry_text_index += 1;
            text_index = retry_text_index;
        } else {
            return false;
        }
    }
    while (pattern_index < pattern.len and pattern[pattern_index] == '*') pattern_index += 1;
    return pattern_index == pattern.len;
}

test "glob matching" {
    try std.testing.expect(globMatch("SurfaceControl*.java", "SurfaceControlViewHost.java"));
    try std.testing.expect(globMatch("*/pdf/*", "/tmp/android/graphics/pdf/PdfRenderer.java"));
    try std.testing.expect(!globMatch("DayPicker*.java", "DatePicker.java"));
}
