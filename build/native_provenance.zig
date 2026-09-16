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

pub fn add(b: *std.Build, native_compiles: []const *std.Build.Step.Run, rust: *@import("rust.zig").Builder, libraries: []const Library, reference: []const u8, package: LazyPath, platform: LazyPath, ndk: LazyPath, pdfclient: LazyPath) *std.Build.Step {
    const run = b.addSystemCommand(&.{ "python3", "-B" });
    run.addFileArg(b.path("tools/native-client-audit.py"));
    run.addFileInput(b.path("tools/native_rust_audit.py"));
    run.addArgs(&.{ "--reference", reference, "--package" });
    run.addDirectoryArg(package);
    run.addArg("--repo");
    run.addDirectoryArg(b.path(""));
    run.addArg("--platform");
    run.addFileArg(platform);
    run.addArg("--ndk");
    run.addFileArg(ndk);
    run.addArg("--pdfclient");
    run.addFileArg(pdfclient);
    run.addArgs(&.{ "--compiler", b.graph.zig_exe });
    var paths: std.ArrayList(LazyPath) = .empty;
    var tokens: std.ArrayList(Token) = .empty;
    var token_ids: std.StringHashMapUnmanaged(usize) = .empty;
    var commands: std.ArrayList([]const usize) = .empty;
    var compiles: std.ArrayList(*std.Build.Step.Run) = .empty;
    compiles.appendSlice(b.allocator, native_compiles) catch @panic("OOM");
    var rust_names: std.ArrayList([]const u8) = .empty;
    var keys = rust.artifacts.keyIterator();
    while (keys.next()) |name| {
        if (!std.mem.endsWith(u8, name.*, "-test")) rust_names.append(b.allocator, name.*) catch @panic("OOM");
    }
    std.mem.sort([]const u8, rust_names.items, {}, struct {
        fn less(_: void, a: []const u8, c: []const u8) bool {
            return std.mem.lessThan(u8, a, c);
        }
    }.less);
    for (rust_names.items) |name| compiles.append(b.allocator, rust.artifacts.get(name).?.compile) catch @panic("OOM");
    for (compiles.items) |compile| {
        var command: std.ArrayList(usize) = .empty;
        for (compile.argv.items) |arg| {
            var token: Token = .{};
            const path: ?LazyPath = switch (arg) {
                .bytes => |value| value: {
                    token.text = value;
                    break :value null;
                },
                .artifact => |value| value: {
                    break :value value.artifact.getEmittedBin();
                },
                .lazy_path, .decorated_directory => |value| value: {
                    token.prefix = value.prefix;
                    token.suffix = value.suffix;
                    break :value value.lazy_path;
                },
                .output_file, .output_file_dep => |value| value: {
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
    const RustInput = struct { name: []const u8, kind: []const u8, command: usize, artifact: usize, depfile: usize, production: bool };
    var rust_inputs: std.ArrayList(RustInput) = .empty;
    const runtime = rust.artifacts.get("libinput_rust_ffi").?;
    for (rust_names.items, native_compiles.len..) |name, index| {
        const artifact = rust.artifacts.get(name).?;
        const production = samePath(runtime.file, artifact.file) or for (runtime.dependencies) |dependency| {
            if (samePath(dependency, artifact.file)) break true;
        } else false;
        const file_index = for (paths.items, 0..) |path, i| {
            if (samePath(path, artifact.file)) break i;
        } else unreachable;
        const dep_index = for (paths.items, 0..) |path, i| {
            if (samePath(path, artifact.depfile)) break i;
        } else unreachable;
        rust_inputs.append(b.allocator, .{ .name = name, .kind = artifact.kind, .command = index, .artifact = file_index, .depfile = dep_index, .production = production }) catch @panic("OOM");
    }
    const LibraryInput = struct { artifact: usize, sources: []const usize };
    var library_inputs: std.ArrayList(LibraryInput) = .empty;
    for (libraries) |library| {
        const artifact = paths.items.len;
        paths.append(b.allocator, library.artifact) catch @panic("OOM");
        run.addArg("--path");
        run.addFileArg(library.artifact);
        var sources: std.ArrayList(usize) = .empty;
        for (library.sources) |source| {
            sources.append(b.allocator, paths.items.len) catch @panic("OOM");
            paths.append(b.allocator, source) catch @panic("OOM");
            run.addArg("--path");
            run.addFileArg(source);
        }
        library_inputs.append(b.allocator, .{ .artifact = artifact, .sources = sources.items }) catch @panic("OOM");
    }
    const manifest = b.addWriteFiles().add("native-compiler-inputs.json", std.json.Stringify.valueAlloc(b.allocator, .{ .tokens = tokens.items, .commands = commands.items, .native_count = native_compiles.len, .rust = rust_inputs.items, .libraries = library_inputs.items }, .{}) catch @panic("OOM"));
    run.addArg("--commands");
    run.addFileArg(manifest);
    run.addArg("--output");
    const report = run.addOutputFileArg("native-client-source.json");
    run.has_side_effects = true;
    return &b.addInstallFileWithDir(report, .prefix, "test-results/native-client-source.json").step;
}

pub const Library = struct {
    artifact: LazyPath,
    sources: []const LazyPath,
};
const Walker = struct {
    b: *std.Build,
    seen: std.AutoHashMapUnmanaged(*std.Build.Step.Compile, void) = .empty,
    modules: std.AutoHashMapUnmanaged(*std.Build.Module, void) = .empty,
    libraries: std.ArrayList(Library) = .empty,

    fn flags(self: *Walker, value: *[]const []const u8) void {
        for (value.*) |flag| if (std.mem.eql(u8, flag, "-frecord-command-line")) return;
        value.* = std.mem.concat(self.b.allocator, []const u8, &.{ value.*, &.{"-frecord-command-line"} }) catch @panic("OOM");
    }
    fn path(self: *Walker, value: LazyPath) void {
        if (value == .generated) {
            const producer = self.b.graph.generated_files.items[@backingInt(value.generated.index)];
            if (producer.cast(std.Build.Step.Compile)) |dependency| self.compile(dependency);
        }
    }
    fn compile(self: *Walker, value: *std.Build.Step.Compile) void {
        const entry = self.seen.getOrPut(self.b.allocator, value) catch @panic("OOM");
        if (entry.found_existing) return;
        var sources: std.ArrayList(LazyPath) = .empty;
        for (value.root_module.getGraph().modules) |module| {
            const visited = self.modules.getOrPut(self.b.allocator, module) catch @panic("OOM");
            for (module.link_objects.items) |object| switch (object) {
                .other_step => |dependency| self.compile(dependency),
                .static_path => |file| self.path(file),
                .c_source_file => |file| {
                    if (!visited.found_existing) self.flags(&file.flags);
                    sources.append(self.b.allocator, file.file) catch @panic("OOM");
                },
                .c_source_files => |files| {
                    if (!visited.found_existing) self.flags(&files.flags);
                    for (files.files) |file| sources.append(self.b.allocator, files.root.path(self.b, file)) catch @panic("OOM");
                },
                .assembly_file => |file| sources.append(self.b.allocator, file) catch @panic("OOM"),
                else => {},
            };
        }
        self.libraries.append(self.b.allocator, .{ .artifact = value.getEmittedBin(), .sources = sources.items }) catch @panic("OOM");
    }
};
pub fn record(b: *std.Build, link: *std.Build.Step.Run) []const Library {
    var walker: Walker = .{ .b = b };
    for (link.argv.items) |arg| switch (arg) {
        .artifact => |value| walker.compile(value.artifact),
        .lazy_path => |value| walker.path(value.lazy_path),
        else => {},
    };
    return walker.libraries.items;
}
