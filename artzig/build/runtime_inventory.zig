//! Inventory the declared runtime exception directly from its build graph.
//! This does not claim compiler-record provenance for ART or Bionic translation.
const std = @import("std");
const Path = std.Build.LazyPath;
const Input = struct { path: usize, flags: []const []const u8, macros: []const []const u8 };
const Unit = struct { name: []const u8, inputs: []const Input, links: []const usize };
const Argument = struct { text: ?[]const u8 = null, input: ?usize = null, directory: ?usize = null, prefix: []const u8 = "", suffix: []const u8 = "" };
const Walker = struct {
    b: *std.Build,
    run: *std.Build.Step.Run,
    paths: usize = 0,
    directories: usize = 0,
    seen: std.AutoHashMapUnmanaged(*std.Build.Step.Compile, void) = .empty,
    units: std.ArrayList(Unit) = .empty,
    external_commands: std.ArrayList([]const Argument) = .empty,

    fn path(self: *Walker, p: Path) usize {
        const index = self.paths;
        self.paths += 1;
        self.run.addArg("--input");
        self.run.addFileArg(p);
        return index;
    }
    fn compile(self: *Walker, c: *std.Build.Step.Compile) void {
        const seen = self.seen.getOrPut(self.b.allocator, c) catch @panic("OOM");
        if (seen.found_existing) return;
        var inputs: std.ArrayList(Input) = .empty;
        var links: std.ArrayList(usize) = .empty;
        for (c.root_module.getGraph().modules) |m| {
            if (m.root_source_file) |p| inputs.append(self.b.allocator, .{ .path = self.path(p), .flags = &.{}, .macros = &.{} }) catch @panic("OOM");
            for (m.link_objects.items) |obj| switch (obj) {
                .other_step => |dep| self.compile(dep),
                .static_path => |p| {
                    if (p == .generated) {
                        const producer = self.b.graph.generated_files.items[@backingInt(p.generated.index)];
                        if (producer.cast(std.Build.Step.Compile)) |dep| self.compile(dep);
                        if (producer.cast(std.Build.Step.Run)) |command| self.externalCommand(command);
                    }
                    links.append(self.b.allocator, self.path(p)) catch @panic("OOM");
                },
                .c_source_file => |f| inputs.append(self.b.allocator, .{ .path = self.path(f.file), .flags = f.flags, .macros = m.c_macros.items }) catch @panic("OOM"),
                .c_source_files => |fs| for (fs.files) |f| {
                    inputs.append(self.b.allocator, .{ .path = self.path(fs.root.path(self.b, f)), .flags = fs.flags, .macros = m.c_macros.items }) catch @panic("OOM");
                },
                .assembly_file => |p| inputs.append(self.b.allocator, .{ .path = self.path(p), .flags = &.{}, .macros = m.c_macros.items }) catch @panic("OOM"),
                else => {},
            };
        }
        self.units.append(self.b.allocator, .{ .name = c.out_filename, .inputs = inputs.items, .links = links.items }) catch @panic("OOM");
    }
    fn externalCommand(self: *Walker, command: *std.Build.Step.Run) void {
        var args: std.ArrayList(Argument) = .empty;
        for (command.argv.items) |arg| {
            const token: Argument = switch (arg) {
                .bytes => |s| .{ .text = s },
                .lazy_path => |p| .{ .input = self.path(p.lazy_path), .prefix = p.prefix, .suffix = p.suffix },
                .decorated_directory => |p| token: {
                    const index = self.directories;
                    self.directories += 1;
                    self.run.addArg("--directory");
                    self.run.addDirectoryArg(p.lazy_path);
                    break :token .{ .directory = index, .prefix = p.prefix, .suffix = p.suffix };
                },
                .output_file, .output_file_dep => |p| .{ .input = self.path(.{ .generated = .{ .index = p.generated_file } }), .prefix = p.prefix, .suffix = p.suffix },
                else => std.debug.panic("unhandled runtime external compiler argument: {s}", .{@tagName(arg)}),
            };
            args.append(self.b.allocator, token) catch @panic("OOM");
        }
        self.external_commands.append(self.b.allocator, args.items) catch @panic("OOM");
    }
};

pub fn add(b: *std.Build, runtime: Path, roots: []const *std.Build.Step.Compile, vendor: Path) Path {
    const tests = b.addSystemCommand(&.{ "python3", "-B" });
    tests.addFileArg(b.path("tools/test_runtime_inventory.py"));
    tests.addFileInput(b.path("tools/runtime-inventory.py"));
    const run = b.addSystemCommand(&.{ "python3", "-B" });
    run.step.dependOn(&tests.step);
    run.addFileArg(b.path("tools/runtime-inventory.py"));
    run.addArg("--runtime");
    run.addDirectoryArg(runtime);
    run.addArg("--package");
    run.addDirectoryArg(vendor);
    run.addArg("--project");
    run.addDirectoryArg(b.path(""));
    var walker: Walker = .{ .b = b, .run = run };
    for (roots) |root| walker.compile(root);
    // These patches are applied by declared Run steps after package extraction.
    for ([_][]const u8{ "build.zig.zon", "patches/host/0001-libcore-network-portability.patch", "patches/bionic_translation/0004-linker-resolve-host-process-global-symbols.patch" }) |p| _ = walker.path(b.path(p));
    const manifest = b.addWriteFiles().add("runtime-inputs.json", std.json.Stringify.valueAlloc(b.allocator, .{ .units = walker.units.items, .external_commands = walker.external_commands.items }, .{}) catch @panic("OOM"));
    run.addArg("--manifest");
    run.addFileArg(manifest);
    run.addArg("--output");
    return run.addOutputFileArg("runtime-inventory.json");
}
