//! Source-built pinned AOSP Rust crates, with graph-owned generated dependencies.
const std = @import("std");
const command = @import("command.zig");
const sources = @import("rust_sources.zig");
const LazyPath = std.Build.LazyPath;

pub const Artifact = struct {
    file: LazyPath,
    dependencies: []const LazyPath,
};

pub const Builder = struct {
    b: *std.Build,
    aosp: LazyPath,
    runner: *std.Build.Step.Compile,
    generated: std.StringHashMapUnmanaged(LazyPath) = .empty,
    artifacts: std.StringHashMapUnmanaged(Artifact) = .empty,

    pub fn addGenerated(self: *Builder, name: []const u8, source: LazyPath) void {
        self.generated.put(self.b.allocator, name, source) catch @panic("oom");
    }

    pub fn build(self: *Builder, name: []const u8) Artifact {
        return self.compile(name, null);
    }

    pub const Test = struct {
        linker: *std.Build.Step.Compile,
        native: []const LazyPath,
    };

    pub fn buildTest(self: *Builder, name: []const u8, options: Test) Artifact {
        return self.compile(name, options);
    }

    fn compile(self: *Builder, name: []const u8, test_options: ?Test) Artifact {
        const b = self.b;
        const key_name = if (test_options != null) b.fmt("{s}-test", .{name}) else name;
        if (self.artifacts.get(key_name)) |artifact| return artifact;
        const crate: sources.Crate = if (std.mem.eql(u8, name, "inputconstants-rust")) .{
            .name = name,
            .crate_name = "inputconstants",
            .directory = "",
            .source = "",
            .deps = &.{ "libbinder_rs", "libasync_trait" },
        } else if (std.mem.eql(u8, name, "libinput_bindgen")) .{
            .name = name,
            .crate_name = "input_bindgen",
            .directory = "",
            .source = "",
        } else for (sources.crates) |entry| {
            if (std.mem.eql(u8, entry.name, name)) break entry;
        } else std.debug.panic("undeclared AOSP Rust crate: {s}", .{name});

        const run = command.create(b);
        run.run.setName(b.fmt("rustc {s} (AOSP 1.83.0)", .{key_name}));
        const sysroot = self.aosp.path(b, "prebuilts/rust/linux-x86/1.83.0");
        run.addFileArg(self.runner.getEmittedBin());
        run.addFileArg(sysroot.path(b, "bin/rustc"));
        run.addArg("--sysroot");
        run.addDirectoryArg(sysroot);
        const source = self.generated.get(name) orelse self.aosp.path(b, crate.directory).path(b, crate.source);
        run.addFileArg(source);
        run.addArgs(&.{ "--crate-name", crate.crate_name, "--edition", crate.edition, "--crate-type", if (test_options != null) "bin" else switch (crate.kind) {
            .rlib => "rlib",
            .proc_macro => "proc-macro",
            .staticlib => "staticlib",
            .bin => "bin",
        }, "--cfg", "soong", "-Copt-level=2", "-Crelocation-model=pic", "-Coverflow-checks=on", "-Csymbol-mangling-version=v0", "-Awarnings" });
        if (test_options) |options| {
            run.addArg("--test");
            run.addPrefixedFileArg("-Clinker=", options.linker.getEmittedBin());
            run.setFileEnvironment("AZK_RUST_LINK_ZIG", .zig_exe);
            for (options.native) |native| run.addPrefixedFileArg("-Clink-arg=", native);
        } else run.addArg("-Clinker=cc");
        if (crate.kind == .proc_macro) run.addArgs(&.{ "--extern", "proc_macro" });
        for (crate.features) |feature| run.addArgs(&.{ "--cfg", b.fmt("feature=\"{s}\"", .{feature}) });
        for (crate.cfgs) |cfg| run.addArgs(&.{ "--cfg", cfg });
        var dependencies: std.ArrayList(LazyPath) = .empty;
        for (crate.deps) |dep_name| {
            const dep = self.build(dep_name);
            const dep_crate = if (std.mem.eql(u8, dep_name, "inputconstants-rust")) "inputconstants" else if (std.mem.eql(u8, dep_name, "libinput_bindgen")) "input_bindgen" else for (sources.crates) |entry| {
                if (std.mem.eql(u8, entry.name, dep_name)) break entry.crate_name;
            } else unreachable;
            run.addArg("--extern");
            run.addPrefixedFileArg(b.fmt("{s}=", .{dep_crate}), dep.file);
            dependencies.appendSlice(b.allocator, dep.dependencies) catch @panic("oom");
            dependencies.append(b.allocator, dep.file) catch @panic("oom");
        }
        // rustc must find the dependencies embedded in its direct dependencies'
        // metadata, even when this crate doesn't use them as extern crates.
        var seen: std.StringHashMapUnmanaged(void) = .empty;
        var unique: std.ArrayList(LazyPath) = .empty;
        for (dependencies.items) |dependency| {
            const key = b.fmt("{any}", .{dependency});
            const entry = seen.getOrPut(b.allocator, key) catch @panic("oom");
            if (entry.found_existing) continue;
            unique.append(b.allocator, dependency) catch @panic("oom");
            run.addPrefixedDirectoryArg("-Ldependency=", dependency.dirname());
        }
        run.setEnvironment("RUSTC_BOOTSTRAP", "1");
        run.setEnvironment("CARGO_PKG_NAME", crate.crate_name);
        run.setEnvironment("CARGO_PKG_VERSION", crate.version);
        var version = std.mem.splitScalar(u8, crate.version, '.');
        for ([_][]const u8{ "MAJOR", "MINOR", "PATCH" }) |part|
            run.setEnvironment(b.fmt("CARGO_PKG_VERSION_{s}", .{part}), version.next() orelse "0");
        run.setEnvironment("CARGO_PKG_VERSION_PRE", "");
        run.setEnvironment("CARGO_PKG_DESCRIPTION", "");
        run.setPathEnvironment("CARGO_MANIFEST_DIR", if (crate.directory.len == 0) source.dirname() else self.aosp.path(b, crate.directory));
        if (std.mem.eql(u8, name, "libbinder_ndk_sys"))
            run.setPathEnvironment("OUT_DIR", self.generated.get("binder-bindings").?.dirname());
        run.addArg("-o");
        const filename = if (test_options != null) b.fmt("{s}-rust-test", .{crate.crate_name}) else switch (crate.kind) {
            .bin => crate.crate_name,
            .rlib => b.fmt("lib{s}.rlib", .{crate.crate_name}),
            .proc_macro => b.fmt("lib{s}.so", .{crate.crate_name}),
            .staticlib => b.fmt("lib{s}_rust.a", .{crate.crate_name}),
        };
        const artifact: Artifact = .{ .file = run.addOutputFileArg(filename), .dependencies = unique.toOwnedSlice(b.allocator) catch @panic("oom") };
        self.artifacts.put(b.allocator, key_name, artifact) catch @panic("oom");
        return artifact;
    }
};
