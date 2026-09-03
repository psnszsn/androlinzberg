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

    pub fn project(b: *Build, sub_path: []const u8) Root {
        return .{ .path = b.path(sub_path) };
    }

    pub fn dependency(_: *Build, dep: *Build.Dependency, sub_path: []const u8) Root {
        return .{ .path = dep.path(sub_path) };
    }

    pub fn child(root: Root, b: *Build, sub_path: []const u8) Root {
        return .{ .path = root.path.path(b, sub_path) };
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
};

pub const Files = struct {
    items: []const File,

    pub fn addInputs(files: Files, run: *Build.Step.Run) void {
        for (files.items) |file| run.addFileInput(file.path);
    }

    pub fn responseFile(files: Files, b: *Build, name: []const u8) LazyPath {
        const absolute = b.allocator.alloc([]const u8, files.items.len) catch @panic("OOM");
        for (files.items, absolute) |file, *resolved| resolved.* = configurePath(b, file.path);
        const contents = responseContents(b.allocator, absolute) catch @panic("OOM");
        const write = b.addWriteFiles();
        return write.add(name, contents);
    }
};

fn responseContents(allocator: std.mem.Allocator, files: []const []const u8) ![]u8 {
    var size: usize = 0;
    for (files) |file| size += file.len + 1;
    const contents = try allocator.alloc(u8, size);
    var offset: usize = 0;
    for (files) |file| {
        @memcpy(contents[offset..][0..file.len], file);
        offset += file.len;
        contents[offset] = '\n';
        offset += 1;
    }
    return contents;
}

pub const Collector = struct {
    b: *Build,
    files: std.StringHashMapUnmanaged(Entry) = .empty,

    pub fn init(b: *Build) Collector {
        return .{ .b = b };
    }

    pub fn addFile(c: *Collector, root: Root, relative_path: []const u8) void {
        const absolute = c.b.pathJoin(&.{ configurePath(c.b, root.path), relative_path });
        _ = remember(&c.files, c.b.allocator, absolute, root.path.path(c.b, relative_path));
    }

    pub fn addTree(c: *Collector, root: Root, options: AddOptions) void {
        const root_absolute = configurePath(c.b, root.path);
        var dir = Io.Dir.cwd().openDir(c.b.graph.io, root_absolute, .{ .iterate = true }) catch |err| {
            std.debug.panic("unable to open source directory {s}: {t}", .{ root_absolute, err });
        };
        defer dir.close(c.b.graph.io);
        var walker = dir.walk(c.b.allocator) catch @panic("OOM");
        defer walker.deinit();

        while (walker.next(c.b.graph.io) catch |err| {
            std.debug.panic("unable to walk source directory {s}: {t}", .{ root_absolute, err });
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

            const absolute = c.b.pathJoin(&.{ root_absolute, entry.path });
            if (matchesAny(options.not_paths, absolute)) continue;
            _ = remember(&c.files, c.b.allocator, absolute, root.path.path(c.b, entry.path));
        }
    }

    pub fn select(c: *const Collector, extension: ?[]const u8) Files {
        const result = selectEntries(c.b.allocator, &c.files, extension);
        const paths = c.b.allocator.alloc(File, result.len) catch @panic("OOM");
        for (result, paths) |entry, *path| {
            path.* = .{ .path = entry.path };
        }
        return .{ .items = paths };
    }
};

const Entry = struct { path: LazyPath, absolute: []const u8 };

fn remember(
    files: *std.StringHashMapUnmanaged(Entry),
    allocator: std.mem.Allocator,
    absolute: []const u8,
    path: LazyPath,
) bool {
    const result = files.getOrPut(allocator, absolute) catch @panic("OOM");
    if (result.found_existing) return false;
    result.key_ptr.* = absolute;
    result.value_ptr.* = .{ .path = path, .absolute = absolute };
    return true;
}

fn selectEntries(
    allocator: std.mem.Allocator,
    files: *const std.StringHashMapUnmanaged(Entry),
    extension: ?[]const u8,
) []Entry {
    var count: usize = 0;
    var count_values = files.valueIterator();
    while (count_values.next()) |file| {
        if (extension == null or std.mem.endsWith(u8, file.absolute, extension.?)) count += 1;
    }
    const selected = allocator.alloc(Entry, count) catch @panic("OOM");
    var index: usize = 0;
    var values = files.valueIterator();
    while (values.next()) |file| {
        if (extension) |ext| {
            if (!std.mem.endsWith(u8, file.absolute, ext)) continue;
        }
        selected[index] = file.*;
        index += 1;
    }
    std.mem.sortUnstable(Entry, selected, {}, lessThan);
    return selected;
}

/// Resolve source-tree locations only while walking or transporting an exact
/// response-file input set. The absolute string never escapes SourceSet.
fn configurePath(b: *Build, lazy_path: LazyPath) []const u8 {
    const unresolved = switch (lazy_path) {
        .src_path => |source| b.pathJoin(&.{
            source.owner.root.toString(b.allocator) catch @panic("OOM"),
            source.sub_path,
        }),
        .dependency => |dependency| b.pathJoin(&.{
            dependency.dependency.builder.root.toString(b.allocator) catch @panic("OOM"),
            dependency.sub_path,
        }),
        else => std.debug.panic("only project and dependency paths can be source-set roots", .{}),
    };
    return Io.Dir.cwd().realPathFileAlloc(b.graph.io, unresolved, b.allocator) catch |err| {
        std.debug.panic("unable to resolve source path {s}: {t}", .{ unresolved, err });
    };
}

fn lessThan(_: void, lhs: Entry, rhs: Entry) bool {
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

test "response files preserve exact selected order" {
    const files = [_][]const u8{ "/root/A.java", "/root/B.java" };
    const contents = try responseContents(std.testing.allocator, &files);
    defer std.testing.allocator.free(contents);
    try std.testing.expectEqualStrings("/root/A.java\n/root/B.java\n", contents);
}

test "source selection deduplicates, filters, and sorts exact files" {
    var files: std.StringHashMapUnmanaged(Entry) = .empty;
    defer files.deinit(std.testing.allocator);
    try std.testing.expect(remember(&files, std.testing.allocator, "/root/B.java", undefined));
    try std.testing.expect(remember(&files, std.testing.allocator, "/root/ignored.kt", undefined));
    try std.testing.expect(remember(&files, std.testing.allocator, "/root/A.java", undefined));
    try std.testing.expect(!remember(&files, std.testing.allocator, "/root/B.java", undefined));

    const selected = selectEntries(std.testing.allocator, &files, ".java");
    defer std.testing.allocator.free(selected);
    try std.testing.expectEqual(@as(usize, 2), selected.len);
    try std.testing.expectEqualStrings("/root/A.java", selected[0].absolute);
    try std.testing.expectEqualStrings("/root/B.java", selected[1].absolute);
}
