//! Serialize the actual native island and linked Skia compiler commands, without reconstructing
//! source lists or compiler settings in a second build declaration.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

const Token = struct {
    text: ?[]const u8 = null,
    path: ?usize = null,
    prefix: []const u8 = "",
    suffix: []const u8 = "",
};

fn samePath(a: LazyPath, b: LazyPath) bool {
    if (std.meta.activeTag(a) != std.meta.activeTag(b)) return false;
    return switch (a) {
        .src_path => |v| v.owner == b.src_path.owner and std.mem.eql(u8, v.sub_path, b.src_path.sub_path),
        .dependency => |v| v.dependency == b.dependency.dependency and std.mem.eql(u8, v.sub_path, b.dependency.sub_path),
        .generated => |v| v.index == b.generated.index and v.up == b.generated.up and std.mem.eql(u8, v.sub_path, b.generated.sub_path),
        .relative => |v| v.eql(b.relative),
        else => std.debug.panic("unsupported legacy native compiler path", .{}),
    };
}

pub fn add(b: *std.Build, compiles: []const *std.Build.Step.Run, reference: []const u8, package: LazyPath, platform: LazyPath, ndk: LazyPath) *std.Build.Step {
    const run = b.addSystemCommand(&.{ "python3", "-B" });
    run.addFileArg(b.path("tools/native-client-audit.py"));
    run.addArgs(&.{ "--reference", reference, "--package" });
    run.addDirectoryArg(package);
    run.addArg("--repo");
    run.addDirectoryArg(b.path(""));
    run.addArg("--platform");
    run.addFileArg(platform);
    run.addArg("--ndk");
    run.addFileArg(ndk);
    var paths: std.ArrayList(LazyPath) = .empty;
    var tokens: std.ArrayList(Token) = .empty;
    var token_ids: std.StringHashMapUnmanaged(usize) = .empty;
    var commands: std.ArrayList([]const usize) = .empty;
    for (compiles) |compile| {
        var command: std.ArrayList(usize) = .empty;
        for (compile.argv.items) |arg| {
            var token: Token = .{};
            const path: ?LazyPath = switch (arg) {
                .bytes => |value| value: {
                    token.text = value;
                    break :value null;
                },
                .lazy_path, .decorated_directory => |value| value: {
                    token.prefix = value.prefix;
                    token.suffix = value.suffix;
                    break :value value.lazy_path;
                },
                .output_file => |value| value: {
                    token.prefix = value.prefix;
                    token.suffix = value.suffix;
                    break :value .{ .generated = .{ .index = value.generated_file } };
                },
                else => std.debug.panic("native provenance cannot serialize compiler argument {s}", .{@tagName(arg)}),
            };
            if (path) |value| {
                token.path = for (paths.items, 0..) |existing, index| {
                    if (samePath(existing, value)) break index;
                } else new: {
                    const index = paths.items.len;
                    paths.append(b.allocator, value) catch @panic("OOM");
                    run.addArg("--path");
                    switch (arg) {
                        .decorated_directory => run.addDirectoryArg(value),
                        else => run.addFileArg(value),
                    }
                    break :new index;
                };
            }
            const key = std.json.Stringify.valueAlloc(b.allocator, token, .{}) catch @panic("OOM");
            const entry = token_ids.getOrPut(b.allocator, key) catch @panic("OOM");
            if (!entry.found_existing) {
                entry.value_ptr.* = tokens.items.len;
                tokens.append(b.allocator, token) catch @panic("OOM");
            }
            command.append(b.allocator, entry.value_ptr.*) catch @panic("OOM");
        }
        commands.append(b.allocator, command.items) catch @panic("OOM");
    }
    const manifest = b.addWriteFiles().add("native-compiler-inputs.json", std.json.Stringify.valueAlloc(b.allocator, .{ .tokens = tokens.items, .commands = commands.items }, .{}) catch @panic("OOM"));
    run.addArg("--commands");
    run.addFileArg(manifest);
    run.addArg("--output");
    const report = run.addOutputFileArg("native-client-source.json");
    run.has_side_effects = true;
    return &b.addInstallFileWithDir(report, .prefix, "test-results/native-client-source.json").step;
}
