//! Validation shared by jar-producing graph nodes.
const std = @import("std");

pub const Error = error{DuplicateJarEntry};

pub fn validate(allocator: std.mem.Allocator, entries: []const []const u8) Error!void {
    var seen: std.StringHashMapUnmanaged(void) = .empty;
    defer seen.deinit(allocator);
    for (entries) |entry| {
        const result = seen.getOrPut(allocator, entry) catch @panic("OOM");
        if (result.found_existing) return error.DuplicateJarEntry;
    }
}

test "exact jar entries preserve their declaration and reject duplicates" {
    const entries = [_][]const u8{
        "classes.dex",
        "java/security/security.properties",
        "sun/nio/cs/standard-charsets",
    };
    try validate(std.testing.allocator, &entries);
    try std.testing.expectEqualStrings("classes.dex", entries[0]);
    try std.testing.expectEqualStrings("java/security/security.properties", entries[1]);
    try std.testing.expectError(
        error.DuplicateJarEntry,
        validate(std.testing.allocator, &.{ "classes.dex", "resource.txt", "resource.txt" }),
    );
}
