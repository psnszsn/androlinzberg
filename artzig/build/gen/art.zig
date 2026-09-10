// GENERATED from the pinned AOSP Soong graph, then narrowed to the single
// Linux x86_64 configuration supported by Artzig.
// Target set: art (linux x86_64 only)
//
// Inputs taken from Soong's out/ tree (generated sources/headers, prebuilts) — port these genrules by hand:
//   (prebuilt) prebuilts/clang/host/linux-x86/clang-r547379/lib/clang/20/lib/linux/libclang_rt.builtins-x86_64.a
//   (prebuilt) prebuilts/clang/host/linux-x86/clang-r547379/lib/clang/20/lib/linux/libclang_rt.ubsan_minimal-x86_64.a
//   (prebuilt) prebuilts/clang/host/linux-x86/clang-r547379/lib/x86_64-unknown-linux-gnu/libc++.a
//   (prebuilt) prebuilts/runtime/mainline/platform/sdk/linux_glibc/x86_64/lib/libcap.a
//   soong/.intermediates/art/build/flags/art-aconfig-flags-lib/linux_glibc_x86_64_static/gen/com_android_art_flags.cc
//   soong/.intermediates/art/build/flags/art-aconfig-flags-lib/linux_glibc_x86_64_static/gen/include/
//   soong/.intermediates/art/compiler/art_compiler_operator_srcs/gen/gensrcs/
//   soong/.intermediates/art/compiler/art_compiler_operator_srcs/gen/gensrcs/art/compiler/linker/linker_patch.operator_out.cc
//   soong/.intermediates/art/compiler/art_compiler_operator_srcs/gen/gensrcs/art/compiler/optimizing/locations.operator_out.cc
//   soong/.intermediates/art/compiler/art_compiler_operator_srcs/gen/gensrcs/art/compiler/optimizing/nodes.operator_out.cc
//   soong/.intermediates/art/compiler/art_compiler_operator_srcs/gen/gensrcs/art/compiler/optimizing/optimizing_compiler_stats.operator_out.cc
//   soong/.intermediates/art/dex2oat/art_dex2oat_operator_srcs/gen/gensrcs/
//   soong/.intermediates/art/dex2oat/art_dex2oat_operator_srcs/gen/gensrcs/art/dex2oat/linker/image_writer.operator_out.cc
//   soong/.intermediates/art/libartbase/art_libartbase_operator_srcs/gen/gensrcs/
//   soong/.intermediates/art/libartbase/art_libartbase_operator_srcs/gen/gensrcs/art/libartbase/arch/instruction_set.operator_out.cc
//   soong/.intermediates/art/libartbase/art_libartbase_operator_srcs/gen/gensrcs/art/libartbase/base/allocator.operator_out.cc
//   soong/.intermediates/art/libartbase/art_libartbase_operator_srcs/gen/gensrcs/art/libartbase/base/unix_file/fd_file.operator_out.cc
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/art/libdexfile/dex/dex_file.operator_out.cc
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/art/libdexfile/dex/dex_file_layout.operator_out.cc
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/art/libdexfile/dex/dex_instruction.operator_out.cc
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/art/libdexfile/dex/dex_instruction_utils.operator_out.cc
//   soong/.intermediates/art/libdexfile/dexfile_operator_srcs/gen/gensrcs/art/libdexfile/dex/invoke_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/base/callee_save_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/base/locks.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/class_status.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/compilation_kind.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/allocator/rosalloc.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/allocator_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/collector/gc_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/collector/mark_compact.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/collector_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/space/region_space.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/space/space.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc/weak_root_state.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/gc_root.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/indirect_reference_table.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/instrumentation.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/jdwp_provider.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/jni_id_type.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/linear_alloc.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/lock_word.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/oat/image.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/oat/oat.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/oat/oat_file.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/process_state.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/reflective_value_visitor.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/stack.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/suspend_reason.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/thread.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/thread_state.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/trace.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/trace_profile.operator_out.cc
//   soong/.intermediates/art/runtime/art_operator_srcs/gen/gensrcs/art/runtime/verifier/verifier_enums.operator_out.cc
//   soong/.intermediates/art/runtime/libart_mterp.x86_64ng/gen/mterp_x86_64ng.S
//   soong/.intermediates/art/tools/cpp-define-generator/cpp-define-generator-asm-support/linux_glibc_x86_64/gen/
const std = @import("std");
const host_toolchain = @import("../host_toolchain.zig");

const target_cflags = [_][]const u8{
    // instruction_set.h defines reserve sizes for every InstructionSet enum,
    // independently of which compiler backends are enabled.
    "-DART_STACK_OVERFLOW_GAP_arm=8192",
    "-DART_STACK_OVERFLOW_GAP_arm64=8192",
    "-DART_STACK_OVERFLOW_GAP_riscv64=8192",
    "-DART_STACK_OVERFLOW_GAP_x86=8192",
    "-DART_STACK_OVERFLOW_GAP_x86_64=8192",
    "-DART_ENABLE_CODEGEN_x86_64",
};

const linux_x86_64_crypto_asm = [_][]const u8{
    "external/boringssl/src/crypto/hrss/asm/poly_rq_mul.S",
    "external/boringssl/src/gen/bcm/aes-gcm-avx10-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/aes-gcm-avx2-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/aesni-gcm-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/aesni-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/ghash-ssse3-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/ghash-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/p256-x86_64-asm-linux.S",
    "external/boringssl/src/gen/bcm/p256_beeu-x86_64-asm-linux.S",
    "external/boringssl/src/gen/bcm/rdrand-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/rsaz-avx2-linux.S",
    "external/boringssl/src/gen/bcm/sha1-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/sha256-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/sha512-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/vpaes-x86_64-linux.S",
    "external/boringssl/src/gen/bcm/x86_64-mont-linux.S",
    "external/boringssl/src/gen/bcm/x86_64-mont5-linux.S",
    "external/boringssl/src/gen/crypto/aes128gcmsiv-x86_64-linux.S",
    "external/boringssl/src/gen/crypto/chacha-x86_64-linux.S",
    "external/boringssl/src/gen/crypto/chacha20_poly1305_x86_64-linux.S",
    "external/boringssl/src/gen/crypto/md5-x86_64-linux.S",
    "external/boringssl/src/third_party/fiat/asm/fiat_curve25519_adx_mul.S",
    "external/boringssl/src/third_party/fiat/asm/fiat_curve25519_adx_square.S",
    "external/boringssl/src/third_party/fiat/asm/fiat_p256_adx_mul.S",
    "external/boringssl/src/third_party/fiat/asm/fiat_p256_adx_sqr.S",
};

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    vendor: std.Build.LazyPath,
    project: std.Build.LazyPath,
    host: host_toolchain.Result,
    /// Original external/icu stable shim, built with matching runtime data.
    icu: *std.Build.Step.Compile,
    /// All required generated sources and headers. There is deliberately no
    /// fallback to a Soong out/ directory.
    generated: GeneratedArtifacts = undefined,
};
pub const GeneratedArtifacts = struct {
    aconfig: SourceArtifacts,
    compiler_operators: SourceArtifacts,
    dex2oat_operators: SourceArtifacts,
    libartbase_operators: SourceArtifacts,
    dexfile_operators: SourceArtifacts,
    runtime_operators: SourceArtifacts,
    mterp: std.Build.LazyPath,
    asm_defines: SourceArtifacts,
};

pub const SourceArtifacts = struct {
    sources: []const std.Build.LazyPath,
    include_roots: []const std.Build.LazyPath = &.{},
};
const Root = enum { vendor, project };
const HostRoot = enum { libcap };
const GeneratedRoot = enum {
    aconfig,
    compiler_operators,
    dex2oat_operators,
    libartbase_operators,
    dexfile_operators,
    runtime_operators,
    asm_defines,
};
const Inc = struct {
    root: Root = .vendor,
    rel: []const u8,
    kind: []const u8 = "-I",
    generated: ?GeneratedRoot = null,
    host: ?HostRoot = null,
};

fn generatedArtifacts(o: Options, root: GeneratedRoot) SourceArtifacts {
    return switch (root) {
        inline else => |tag| @field(o.generated, @tagName(tag)),
    };
}

/// Flags every compile unit in this target set shares (intersection over all units).
pub const base_cflags = [_][]const u8{
    "-fPIC",                     "-fno-omit-frame-pointer",            "-U_FORTIFY_SOURCE",            "-D_FORTIFY_SOURCE=3",                      "-O2",                                        "-Wall",
    "-Wextra",                   "-Winit-self",                        "-Wpointer-arith",              "-Wno-error=deprecated-declarations",       "-Wno-c23-extensions",                        "-Wno-c99-designator",
    "-Wno-gnu-folding-constant", "-Wno-inconsistent-missing-override", "-Wno-error=reorder-init-list", "-Wno-reorder-init-list",                   "-Wno-sign-compare",                          "-Wno-unused",
    "-DANDROID",                 "-DNDEBUG",                           "-UDEBUG",                      "-D__compiler_offsetof=__builtin_offsetof", "-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__", "-ffp-contract=off",
    "-fno-exceptions",           "-fno-strict-aliasing",               "-fmessage-length=0",           "-ftrivial-auto-var-init=zero",             "-g",
};

fn rootPath(o: Options, r: Root) std.Build.LazyPath {
    return switch (r) {
        .vendor => o.vendor,
        .project => o.project,
    };
}
const IncludeSet = struct {
    args: []const []const u8,
    declarations: []const Inc,
};

const GroupFlags = struct {
    args: []const []const u8,
    includes: []const Inc,
};

fn incSet(b: *std.Build, o: Options, list: []const Inc) IncludeSet {
    _ = o;
    var out: std.ArrayList([]const u8) = .empty;
    for (list) |i| {
        // Include directories are graph paths added by addIncludes. The sole
        // forced include is found through its declared include directory, so
        // only the header basename remains a compiler flag string.
        if (!std.mem.eql(u8, i.kind, "-include")) continue;
        out.append(b.allocator, "-include") catch @panic("OOM");
        out.append(b.allocator, std.fs.path.basename(i.rel)) catch @panic("OOM");
    }
    return .{ .args = out.items, .declarations = list };
}
fn sameInclude(a: Inc, b: Inc) bool {
    return a.root == b.root and
        a.generated == b.generated and
        a.host == b.host and
        std.mem.eql(u8, a.rel, b.rel) and
        std.mem.eql(u8, a.kind, b.kind);
}

fn addIncludes(b: *std.Build, o: Options, m: *std.Build.Module, list: []const Inc) void {
    for (list, 0..) |i, index| {
        for (list[0..index]) |previous| {
            if (sameInclude(previous, i)) break;
        } else {
            addInclude(b, o, m, i);
        }
    }
}

fn addInclude(b: *std.Build, o: Options, m: *std.Build.Module, i: Inc) void {
    if (i.host) |host| {
        const roots = switch (host) {
            .libcap => o.host.libcap_include_roots,
        };
        for (roots) |base| m.addSystemIncludePath(if (i.rel.len == 0) base else base.path(b, i.rel));
        return;
    }
    if (i.generated) |generated| {
        for (generatedArtifacts(o, generated).include_roots) |base| {
            const path = if (i.rel.len == 0) base else base.path(b, i.rel);
            m.addIncludePath(path);
        }
        return;
    }
    const base = rootPath(o, i.root);
    if (std.mem.eql(u8, i.kind, "-include")) {
        m.addIncludePath(base.path(b, std.fs.path.dirname(i.rel) orelse ""));
    } else if (std.mem.eql(u8, i.kind, "-isystem")) {
        m.addSystemIncludePath(base.path(b, i.rel));
    } else {
        m.addIncludePath(base.path(b, i.rel));
    }
}

const SourceGroups = struct {
    b: *std.Build,
    options: Options,
    owner: *std.Build.Step.Compile,
    owner_name: []const u8,
    next_index: usize = 0,

    const AddCSourceFilesOptions = struct {
        root: ?std.Build.LazyPath = null,
        files: []const []const u8,
        flags: GroupFlags,
        language: ?std.Build.Module.CSourceLanguage = null,
    };

    const CSourceFile = struct {
        file: std.Build.LazyPath,
        flags: GroupFlags,
        language: ?std.Build.Module.CSourceLanguage = null,
    };

    const GroupModule = struct {
        name: []const u8,
        module: *std.Build.Module,
    };

    fn newModule(self: *SourceGroups, group_flags: GroupFlags) GroupModule {
        const name = self.b.fmt("{s}-sources-{d}", .{ self.owner_name, self.next_index });
        const module = self.b.createModule(.{
            .target = self.options.target,
            .optimize = self.options.optimize,
            .link_libc = true,
            .link_libcpp = true,
            .sanitize_c = .off,
        });
        self.next_index += 1;
        addIncludes(self.b, self.options, module, group_flags.includes);
        return .{ .name = name, .module = module };
    }

    fn attachObject(self: *SourceGroups, group: GroupModule) void {
        self.owner.root_module.addObject(self.b.addObject(.{
            .name = group.name,
            .root_module = group.module,
        }));
    }

    fn addCSourceFiles(self: *SourceGroups, source: AddCSourceFilesOptions) void {
        if (self.owner.kind == .lib and self.owner.linkage != .dynamic) {
            // Preserve the native archive contract: every selected source is
            // its own member, so the final linker extracts only the object
            // that satisfies an unresolved symbol. Grouping several sources
            // into one relocatable object changes ART's overlapping static
            // library semantics.
            const root = source.root orelse @panic("static source group requires a root");
            for (source.files) |file| {
                const group = self.newModule(source.flags);
                group.module.addCSourceFile(.{
                    .file = root.path(self.b, file),
                    .flags = source.flags.args,
                    .language = source.language,
                });
                self.attachObject(group);
            }
        } else {
            // Shared libraries and executables keep the group as one object;
            // this preserves their source-before-library link ordering.
            const group = self.newModule(source.flags);
            group.module.addCSourceFiles(.{
                .root = source.root,
                .files = source.files,
                .flags = source.flags.args,
                .language = source.language,
            });
            self.attachObject(group);
        }
    }

    fn addCSourceFile(self: *SourceGroups, source: CSourceFile) void {
        const group = self.newModule(source.flags);
        group.module.addCSourceFile(.{
            .file = source.file,
            .flags = source.flags.args,
            .language = source.language,
        });
        self.attachObject(group);
    }
};

fn applySourceGroups(
    b: *std.Build,
    o: Options,
    I: Incs,
    owner: *std.Build.Step.Compile,
    comptime add: fn (*std.Build, Options, Incs, *SourceGroups) void,
) void {
    var groups: SourceGroups = .{
        .b = b,
        .options = o,
        .owner = owner,
        // Static and shared variants often have the same logical name, while
        // their output names are distinct (libfoo.a versus libfoo.so).
        .owner_name = owner.out_filename,
        // Several source declarations may contribute to one owner. Continue
        // after its existing link objects rather than reusing `sources-0`.
        .next_index = owner.root_module.link_objects.items.len,
    };
    add(b, o, I, &groups);
}

fn addGeneratedSources(m: *SourceGroups, artifacts: SourceArtifacts, source_flags: GroupFlags) void {
    for (artifacts.sources) |source| m.addCSourceFile(.{ .file = source, .flags = source_flags });
}
fn flags(b: *std.Build, _: Options, delta: []const []const u8, incs: IncludeSet) GroupFlags {
    return .{
        .args = std.mem.concat(b.allocator, []const u8, &.{ &base_cflags, &target_cflags, delta, incs.args }) catch @panic("OOM"),
        .includes = incs.declarations,
    };
}

fn compilerOptimizationFlags(b: *std.Build, o: Options, I: Incs) GroupFlags {
    var result = flags(b, o, &cf_0, I.inc_18);
    result.includes = std.mem.concat(b.allocator, Inc, &.{
        result.includes,
        &.{.{ .root = .vendor, .rel = "art/compiler/optimizing" }},
    }) catch @panic("OOM");
    return result;
}
// Same relocatable rpath set Soong gives host binaries (bin/ next to lib64/).
fn setRPaths(c: *std.Build.Step.Compile) void {
    for ([_][]const u8{ "$ORIGIN/", "$ORIGIN/lib64", "$ORIGIN/../lib64", "$ORIGIN/../../lib64" }) |r| c.root_module.addRPathSpecial(r);
}
// sanitize_c off: zig's ubsan runtime adds a 256K static-TLS block per .so, which then cannot be dlopen()ed
fn newLib(b: *std.Build, o: Options, name: []const u8, linkage: std.builtin.LinkMode) *std.Build.Step.Compile {
    const c = b.addLibrary(.{ .name = name, .linkage = linkage, .root_module = b.createModule(.{ .target = o.target, .optimize = o.optimize, .link_libc = true, .link_libcpp = true, .sanitize_c = .off }) });
    if (linkage == .dynamic) {
        setRPaths(c);
    } else {
        // Group-local source objects are archived into this target. Zig still
        // requires the aggregate archive itself to own one direct source.
        const empty = b.addWriteFiles().add(b.fmt("{s}-aggregate.c", .{name}), "");
        c.root_module.addCSourceFile(.{ .file = empty });
    }
    return c;
}
fn newExe(b: *std.Build, o: Options, name: []const u8) *std.Build.Step.Compile {
    const c = b.addExecutable(.{ .name = name, .root_module = b.createModule(.{ .target = o.target, .optimize = o.optimize, .link_libc = true, .link_libcpp = true, .sanitize_c = .off }) });
    setRPaths(c);
    return c;
}

// Per-module flags on top of base_cflags (deduplicated).
const cf_0 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wimplicit-fallthrough",                 "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-Wno-gnu-include-next",                      "-fno-rtti",
    "-ggdb3",                                    "-Wstrict-aliasing",                      "-fstrict-aliasing",                                                 "-Wunreachable-code",                         "-Wredundant-decls",
    "-Wshadow",                                  "-Wunused",                               "-fvisibility=protected",                                            "-Wthread-safety",                            "-Wimplicit-fallthrough",
    "-Wfloat-equal",                             "-Wint-to-void-pointer-cast",             "-Wused-but-marked-unused",                                          "-Wdeprecated",                               "-Wunreachable-code-break",
    "-Wunreachable-code-return",                 "-Wno-invalid-offsetof",                  "-Winconsistent-missing-override",                                   "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",              "-DART_USE_READ_BARRIER=1",               "-DART_READ_BARRIER_TYPE_IS_BAKER=1",                                "-DART_USE_GENERATIONAL_GC=1",                "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",                "-DUSE_D8_DESUGAR=1",                     "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",                "-DART_BASE_ADDRESS=0x70000000",
    "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000", "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",                         "-msse4.2",
    "-mpopcnt",                                  "-std=gnu++20",                           "-fno-rtti",                                                         "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",             "-Wno-pointer-to-int-cast",               "-Wno-unused-variable",                                              "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare",        "-Wno-tautological-type-limit-compare",   "-Wno-implicit-int-float-conversion",                                "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",                 "-Wno-zero-as-null-pointer-constant",     "-Wno-deprecated-anon-enum-enum-conversion",                         "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",            "-Wno-align-mismatch",                    "-Wno-error=unused-but-set-variable",                                "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",                     "-Wno-error=invalid-offsetof",            "-Wno-vla-cxx-extension",
};
const cf_1 = [_][]const u8{
    "-DANDROID_STRICT",                     "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",          "-std=gnu++20",
    "-Wexit-time-destructors",              "-fno-rtti",                                 "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",
    "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",
    "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",           "-Wno-range-loop-construct",
    "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",
    "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",
    "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
};
const cf_2 = [_][]const u8{
    "-DANDROID_STRICT",                     "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",          "-Wexit-time-destructors",
    "-DLIBLOG_LOG_TAG=1006",                "-DSNET_EVENT_LOG_TAG=1397638484",           "-DANDROID_DEBUGGABLE=0",                     "-UANDROID_DEBUGGABLE",           "-DANDROID_DEBUGGABLE=1",
    "-std=gnu++20",                         "-fno-rtti",                                 "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",
    "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",
    "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",           "-Wno-range-loop-construct",
    "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",
    "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",
    "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
};
const cf_3 = [_][]const u8{
    "-Wno-enum-compare",                  "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",              "-Wno-null-dereference",                      "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",    "-Wno-psabi",                           "-Wno-null-pointer-subtraction",             "-Wno-string-concatenation",                  "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                    "-Wno-error=format",                    "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",
    "-UNDEBUG",                           "-std=gnu++20",                         "-fno-rtti",                                 "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                                   "-Wno-format-insufficient-args",
    "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",              "-Wno-unqualified-std-cast-call",
    "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_4 = [_][]const u8{
    "-Wno-enum-compare",                  "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",              "-Wno-null-dereference",                "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",    "-Wno-psabi",                           "-Wno-null-pointer-subtraction",             "-Wno-string-concatenation",            "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                    "-Wno-error=format",                    "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",
    "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call",
    "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_5 = [_][]const u8{
    "-Wno-enum-compare",                  "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",              "-Wno-null-dereference",                "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",    "-Wno-psabi",                           "-Wno-null-pointer-subtraction",             "-Wno-string-concatenation",            "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                    "-Wno-error=format",                    "-DZ7_ST",                                   "-Wno-empty-body",                      "-Wno-enum-conversion",
    "-Wno-logical-op-parentheses",        "-Wno-self-assign",                     "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",
    "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call",
    "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_6 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wimplicit-fallthrough",               "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",              "-Wno-deprecated-volatile",
    "-Wexit-time-destructors",                   "-fno-c++-static-destructors",          "-DDEXFILE_SUPPORT",                          "-O0",                                "-std=gnu++20",
    "-fno-rtti",                                 "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",             "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                        "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                       "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",                "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",             "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",
};
const cf_7 = [_][]const u8{
    "-D__ASSEMBLY__", "-fdebug-default-version=4",
};
const cf_8 = [_][]const u8{
    "-Wno-enum-compare",                    "-Wno-enum-compare-switch",                  "-Wno-null-pointer-arithmetic",         "-Wno-null-dereference",          "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",      "-Wno-psabi",                                "-Wno-null-pointer-subtraction",        "-Wno-string-concatenation",      "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                      "-Wno-error=format",                         "-DHAVE_HIDDEN",                        "-DZLIB_CONST",                   "-DCHROMIUM_ZLIB_NO_CASTAGNOLI",
    "-O3",                                  "-Wno-deprecated-non-prototype",             "-Wno-unused-parameter",                "-DX86_NOT_WINDOWS",              "-DCPU_NO_SIMD",
    "-DINFLATE_CHUNK_READ_64LE",            "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",
    "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",
    "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",
    "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",
    "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",
    "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",  "-Wno-misleading-indentation",
    "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call", "-Wno-array-parameter",
    "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_9 = [_][]const u8{
    "-Wno-enum-compare",                         "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",   "-Wno-null-dereference",              "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",           "-Wno-psabi",                           "-Wno-null-pointer-subtraction",  "-Wno-string-concatenation",          "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                           "-Wno-error=format",                    "-DZSTD_HAVE_WEAK_SYMBOLS=0",     "-DZSTD_TRACE=0",                     "-DZSTD_DISABLE_ASM",
    "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",  "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",
    "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call", "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",
    "-Wno-pessimizing-move",
};
const cf_10 = [_][]const u8{
    "-DANDROID_STRICT",                        "-Wimplicit-fallthrough",                     "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                     "-DZLIB_CONST",
    "-D_FILE_OFFSET_BITS=64",                  "-DINCFS_SUPPORT_DISABLED=1",                 "-fno-sanitize=vptr,function",                "-fno-sanitize-recover=all",                 "-fno-sanitize-trap=integer,undefined",
    "-fno-sanitize-recover=integer,undefined", "-fno-sanitize=implicit-integer-sign-change", "-fno-sanitize=unsigned-shift-base",          "-std=c++2a",                                "-Wno-missing-field-initializers",
    "-Wconversion",                            "-Wno-sign-conversion",                       "-Wold-style-cast",                           "-fno-rtti",                                 "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast",          "-Wno-void-pointer-to-int-cast",              "-Wno-pointer-to-int-cast",                   "-Wno-unused-variable",                      "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",                     "-Wno-tautological-constant-compare",         "-Wno-tautological-type-limit-compare",       "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",                    "-Wno-range-loop-construct",                  "-Wno-zero-as-null-pointer-constant",         "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",             "-Wno-non-c-typedef-for-linkage",             "-Wno-align-mismatch",                        "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins",          "-Wno-error=deprecated",                      "-Wno-error=invalid-offsetof",                "-Wno-vla-cxx-extension",
};
const cf_11 = [_][]const u8{
    "-Wno-enum-compare",                  "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",              "-Wno-null-dereference",                      "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",    "-Wno-psabi",                           "-Wno-null-pointer-subtraction",             "-Wno-string-concatenation",                  "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                    "-Wno-error=format",                    "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",
    "-Wno-implicit-fallthrough",          "-std=gnu++20",                         "-fno-rtti",                                 "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                                   "-Wno-format-insufficient-args",
    "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",              "-Wno-unqualified-std-cast-call",
    "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_12 = [_][]const u8{
    "-DANDROID_STRICT",               "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                                             "-fno-rtti",
    "-ggdb3",                         "-Wstrict-aliasing",                         "-fstrict-aliasing",                          "-Wunreachable-code",                                                "-Wredundant-decls",
    "-Wshadow",                       "-Wunused",                                  "-fvisibility=protected",                     "-Wthread-safety",                                                   "-Wimplicit-fallthrough",
    "-Wfloat-equal",                  "-Wint-to-void-pointer-cast",                "-Wused-but-marked-unused",                   "-Wdeprecated",                                                      "-Wunreachable-code-break",
    "-Wunreachable-code-return",      "-Wno-invalid-offsetof",                     "-Winconsistent-missing-override",            "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",   "-DART_USE_READ_BARRIER=1",                  "-DART_READ_BARRIER_TYPE_IS_BAKER=1",         "-DART_USE_GENERATIONAL_GC=1",                                       "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",     "-DUSE_D8_DESUGAR=1",                        "-Wno-unused-command-line-argument",          "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",
    "-DART_BASE_ADDRESS=0x70000000",  "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000",     "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",
    "-msse4.2",                       "-mpopcnt",                                  "-std=gnu++20",                               "-fno-rtti",                                                         "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",             "-Wno-pointer-to-int-cast",                   "-Wno-unused-variable",                                              "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",        "-Wno-tautological-type-limit-compare",       "-Wno-implicit-int-float-conversion",                                "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",                 "-Wno-zero-as-null-pointer-constant",         "-Wno-deprecated-anon-enum-enum-conversion",                         "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",            "-Wno-align-mismatch",                        "-Wno-error=unused-but-set-variable",                                "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",                     "-Wno-error=invalid-offsetof",                "-Wno-vla-cxx-extension",
};
const cf_14 = [_][]const u8{
    "-Wno-enum-compare",                    "-Wno-enum-compare-switch",       "-Wno-null-pointer-arithmetic",         "-Wno-null-dereference",                "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",      "-Wno-psabi",                     "-Wno-null-pointer-subtraction",        "-Wno-string-concatenation",            "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                      "-Wno-error=format",              "-DSTACK_LINE_READER_BUFFER_SIZE=1024", "-Wno-gnu-designator",                  "-std=gnu23",
    "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",        "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",
    "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",   "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",
    "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",            "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion",
    "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",       "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",
    "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",                "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
    "-fcommon",                             "-Wno-format-insufficient-args",  "-Wno-misleading-indentation",          "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",
    "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call", "-Wno-array-parameter",                 "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_15 = [_][]const u8{
    "-Wno-enum-compare",                         "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",         "-Wno-null-dereference",              "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",           "-Wno-psabi",                           "-Wno-null-pointer-subtraction",        "-Wno-string-concatenation",          "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                           "-Wno-error=format",                    "-DSTACK_LINE_READER_BUFFER_SIZE=1024", "-Wno-gnu-designator",                "-Wno-unused-variable",
    "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",       "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                  "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                 "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",          "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",       "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",        "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",
    "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call",       "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",
    "-Wno-pessimizing-move",
};
const cf_16 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wimplicit-fallthrough",               "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                      "-fno-rtti",
    "-ggdb3",                                    "-Wstrict-aliasing",                    "-fstrict-aliasing",                          "-Wunreachable-code",                         "-Wredundant-decls",
    "-Wshadow",                                  "-Wunused",                             "-fvisibility=protected",                     "-Wthread-safety",                            "-Wimplicit-fallthrough",
    "-Wfloat-equal",                             "-Wint-to-void-pointer-cast",           "-Wused-but-marked-unused",                   "-Wdeprecated",                               "-Wunreachable-code-break",
    "-Wunreachable-code-return",                 "-Wno-invalid-offsetof",                "-Winconsistent-missing-override",            "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",              "-DART_USE_READ_BARRIER=1",             "-DART_READ_BARRIER_TYPE_IS_BAKER=1",         "-DART_USE_GENERATIONAL_GC=1",                "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",                "-DUSE_D8_DESUGAR=1",                   "-DBUILDING_LIBART",                          "-Wno-unused-command-line-argument",          "-Wframe-larger-than=1744",
    "-DART_FRAME_SIZE_LIMIT=1744",               "-DART_BASE_ADDRESS=0x70000000",        "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)",  "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000",     "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"",
    "-Wno-unused-command-line-argument",         "-Wmissing-noreturn",                   "-msse4.2",                                   "-mpopcnt",                                   "-std=gnu++20",
    "-fno-rtti",                                 "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",             "-Wno-void-pointer-to-int-cast",              "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                        "-Wno-tautological-constant-compare",         "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                       "-Wno-range-loop-construct",                  "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",                "-Wno-non-c-typedef-for-linkage",             "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",             "-Wno-error=deprecated",                      "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",
};
const cf_17 = [_][]const u8{
    "-D__ASSEMBLY__", "-fdebug-default-version=4", "-DART_USE_READ_BARRIER=1", "-DART_READ_BARRIER_TYPE_IS_BAKER=1",
};
const cf_18 = [_][]const u8{
    "-DANDROID_STRICT",               "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                                             "-fno-rtti",
    "-ggdb3",                         "-Wstrict-aliasing",                         "-fstrict-aliasing",                          "-Wunreachable-code",                                                "-Wredundant-decls",
    "-Wshadow",                       "-Wunused",                                  "-fvisibility=protected",                     "-Wthread-safety",                                                   "-Wimplicit-fallthrough",
    "-Wfloat-equal",                  "-Wint-to-void-pointer-cast",                "-Wused-but-marked-unused",                   "-Wdeprecated",                                                      "-Wunreachable-code-break",
    "-Wunreachable-code-return",      "-Wno-invalid-offsetof",                     "-Winconsistent-missing-override",            "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",   "-DART_USE_READ_BARRIER=1",                  "-DART_READ_BARRIER_TYPE_IS_BAKER=1",         "-DART_USE_GENERATIONAL_GC=1",                                       "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",     "-DUSE_D8_DESUGAR=1",                        "-DSTATIC_LIB",                               "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",
    "-DART_BASE_ADDRESS=0x70000000",  "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000",     "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",
    "-msse4.2",                       "-mpopcnt",                                  "-std=gnu++20",                               "-fno-rtti",                                                         "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",             "-Wno-pointer-to-int-cast",                   "-Wno-unused-variable",                                              "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",        "-Wno-tautological-type-limit-compare",       "-Wno-implicit-int-float-conversion",                                "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",                 "-Wno-zero-as-null-pointer-constant",         "-Wno-deprecated-anon-enum-enum-conversion",                         "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",            "-Wno-align-mismatch",                        "-Wno-error=unused-but-set-variable",                                "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",                     "-Wno-error=invalid-offsetof",                "-Wno-vla-cxx-extension",
};
const cf_19 = [_][]const u8{
    "-DANDROID_STRICT",                     "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                                             "-fno-rtti",
    "-ggdb3",                               "-Wstrict-aliasing",                         "-fstrict-aliasing",                          "-Wunreachable-code",                                                "-Wredundant-decls",
    "-Wshadow",                             "-Wunused",                                  "-fvisibility=protected",                     "-Wthread-safety",                                                   "-Wimplicit-fallthrough",
    "-Wfloat-equal",                        "-Wint-to-void-pointer-cast",                "-Wused-but-marked-unused",                   "-Wdeprecated",                                                      "-Wunreachable-code-break",
    "-Wunreachable-code-return",            "-Wno-invalid-offsetof",                     "-Winconsistent-missing-override",            "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",         "-DART_USE_READ_BARRIER=1",                  "-DART_READ_BARRIER_TYPE_IS_BAKER=1",         "-DART_USE_GENERATIONAL_GC=1",                                       "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",           "-DUSE_D8_DESUGAR=1",                        "-Wno-unused-command-line-argument",          "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",
    "-DART_BASE_ADDRESS=0x70000000",        "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000",     "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",
    "-msse4.2",                             "-mpopcnt",                                  "-DSTATIC_LIB",                               "-std=gnu++20",                                                      "-fno-rtti",
    "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",            "-Wno-void-pointer-to-int-cast",              "-Wno-pointer-to-int-cast",                                          "-Wno-unused-variable",
    "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                       "-Wno-tautological-constant-compare",         "-Wno-tautological-type-limit-compare",                              "-Wno-implicit-int-float-conversion",
    "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                      "-Wno-range-loop-construct",                  "-Wno-zero-as-null-pointer-constant",                                "-Wno-deprecated-anon-enum-enum-conversion",
    "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",               "-Wno-non-c-typedef-for-linkage",             "-Wno-align-mismatch",                                               "-Wno-error=unused-but-set-variable",
    "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",            "-Wno-error=deprecated",                      "-Wno-error=invalid-offsetof",                                       "-Wno-vla-cxx-extension",
};
const cf_20 = [_][]const u8{
    "-DANDROID_STRICT",                     "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",                                             "-fno-rtti",
    "-ggdb3",                               "-Wstrict-aliasing",                         "-fstrict-aliasing",                          "-Wunreachable-code",                                                "-Wredundant-decls",
    "-Wshadow",                             "-Wunused",                                  "-fvisibility=protected",                     "-Wthread-safety",                                                   "-Wimplicit-fallthrough",
    "-Wfloat-equal",                        "-Wint-to-void-pointer-cast",                "-Wused-but-marked-unused",                   "-Wdeprecated",                                                      "-Wunreachable-code-break",
    "-Wunreachable-code-return",            "-Wno-invalid-offsetof",                     "-Winconsistent-missing-override",            "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-O3",
    "-DART_DEFAULT_GC_TYPE_IS_CMC",         "-DART_USE_READ_BARRIER=1",                  "-DART_READ_BARRIER_TYPE_IS_BAKER=1",         "-DART_USE_GENERATIONAL_GC=1",                                       "-DART_USE_TLAB=1",
    "-DART_PAGE_SIZE_AGNOSTIC=1",           "-DUSE_D8_DESUGAR=1",                        "-Wno-unused-command-line-argument",          "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",
    "-DART_BASE_ADDRESS=0x70000000",        "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000",     "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",
    "-msse4.2",                             "-mpopcnt",                                  "-DART_STATIC_LIBARTBASE",                    "-std=gnu++20",                                                      "-fno-rtti",
    "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",            "-Wno-void-pointer-to-int-cast",              "-Wno-pointer-to-int-cast",                                          "-Wno-unused-variable",
    "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                       "-Wno-tautological-constant-compare",         "-Wno-tautological-type-limit-compare",                              "-Wno-implicit-int-float-conversion",
    "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                      "-Wno-range-loop-construct",                  "-Wno-zero-as-null-pointer-constant",                                "-Wno-deprecated-anon-enum-enum-conversion",
    "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",               "-Wno-non-c-typedef-for-linkage",             "-Wno-align-mismatch",                                               "-Wno-error=unused-but-set-variable",
    "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",            "-Wno-error=deprecated",                      "-Wno-error=invalid-offsetof",                                       "-Wno-vla-cxx-extension",
};
const cf_21 = [_][]const u8{
    "-Wno-enum-compare",               "-Wno-enum-compare-switch",           "-Wno-null-pointer-arithmetic",         "-Wno-null-dereference",                      "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class", "-Wno-psabi",                         "-Wno-null-pointer-subtraction",        "-Wno-string-concatenation",                  "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                 "-Wno-error=format",                  "-Wimplicit-fallthrough",               "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",
    "-fvisibility=hidden",             "-DBORINGSSL_SHARED_LIBRARY",         "-DOPENSSL_SMALL",                      "-Wno-unused-parameter",                      "-DBORINGSSL_ANDROID_SYSTEM",
    "-DBORINGSSL_IMPLEMENTATION",      "-std=gnu++20",                       "-std=c++17",                           "-fno-rtti",                                  "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast",  "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                       "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",             "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",         "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",            "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion",  "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",     "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",         "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins",  "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                     "-fcommon",
    "-Wno-format-insufficient-args",   "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                      "-Wno-unused-but-set-parameter",
    "-Wno-unqualified-std-cast-call",  "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_22 = [_][]const u8{
    "-Wno-enum-compare",               "-Wno-enum-compare-switch", "-Wno-null-pointer-arithmetic",  "-Wno-null-dereference",     "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class", "-Wno-psabi",               "-Wno-null-pointer-subtraction", "-Wno-string-concatenation", "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                 "-Wno-error=format",        "-D__ASSEMBLY__",                "-fdebug-default-version=4",
};
const cf_23 = [_][]const u8{
    "-DANDROID_STRICT",               "-fvisibility=protected",             "-std=c11",                             "-std=gnu23",                                "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
};
const cf_24 = [_][]const u8{
    "-Wno-enum-compare",                    "-Wno-enum-compare-switch",       "-Wno-null-pointer-arithmetic",       "-Wno-null-dereference",                "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",      "-Wno-psabi",                     "-Wno-null-pointer-subtraction",      "-Wno-string-concatenation",            "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                      "-Wno-error=format",              "-D_IEEE_LIBM",                       "-D__LITTLE_ENDIAN",                    "-Wno-dangling-else",
    "-Wno-unknown-pragmas",                 "-Wno-dangling-else",             "-Wno-logical-op-parentheses",        "-Wno-sometimes-uninitialized",         "-std=c99",
    "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",
    "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",
    "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion",
    "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",
    "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
    "-fcommon",                             "-Wno-format-insufficient-args",  "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",
    "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call", "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_25 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wimplicit-fallthrough",               "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",              "-std=gnu++20",
    "-fno-rtti",                                 "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",             "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                        "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                       "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",                "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",             "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",
};
const cf_26 = [_][]const u8{
    "-DANDROID_STRICT",               "-fPIE",                                     "-Wimplicit-fallthrough",                 "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",                        "-Wno-gnu-include-next",
    "-fno-rtti",                      "-ggdb3",                                    "-Wstrict-aliasing",                      "-fstrict-aliasing",                                                 "-Wunreachable-code",
    "-Wredundant-decls",              "-Wshadow",                                  "-Wunused",                               "-fvisibility=protected",                                            "-Wthread-safety",
    "-Wimplicit-fallthrough",         "-Wfloat-equal",                             "-Wint-to-void-pointer-cast",             "-Wused-but-marked-unused",                                          "-Wdeprecated",
    "-Wunreachable-code-break",       "-Wunreachable-code-return",                 "-Wno-invalid-offsetof",                  "-Winconsistent-missing-override",                                   "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS",
    "-O3",                            "-DART_DEFAULT_GC_TYPE_IS_CMC",              "-DART_USE_READ_BARRIER=1",               "-DART_READ_BARRIER_TYPE_IS_BAKER=1",                                "-DART_USE_GENERATIONAL_GC=1",
    "-DART_USE_TLAB=1",               "-DART_PAGE_SIZE_AGNOSTIC=1",                "-DUSE_D8_DESUGAR=1",                     "-Wframe-larger-than=1744",                                          "-DART_FRAME_SIZE_LIMIT=1744",
    "-DART_BASE_ADDRESS=0x70000000",  "-DART_BASE_ADDRESS_MIN_DELTA=(-0x1000000)", "-DART_BASE_ADDRESS_MAX_DELTA=0x1000000", "-DART_CLANG_PATH=\"prebuilts/clang/host/linux-x86/clang-r547379\"", "-Wmissing-noreturn",
    "-msse4.2",                       "-mpopcnt",                                  "-std=gnu++20",                           "-fno-rtti",                                                         "-Wimplicit-int-float-conversion",
    "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",             "-Wno-pointer-to-int-cast",               "-Wno-unused-variable",                                              "-Wno-missing-field-initializers",
    "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",        "-Wno-tautological-type-limit-compare",   "-Wno-implicit-int-float-conversion",                                "-Wno-tautological-overlap-compare",
    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",                 "-Wno-zero-as-null-pointer-constant",     "-Wno-deprecated-anon-enum-enum-conversion",                         "-Wno-deprecated-enum-enum-conversion",
    "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",            "-Wno-align-mismatch",                    "-Wno-error=unused-but-set-variable",                                "-Wno-error=unused-but-set-parameter",
    "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",                     "-Wno-error=invalid-offsetof",            "-Wno-vla-cxx-extension",
};
const cf_27 = [_][]const u8{
    "-Wno-enum-compare",                  "-Wno-enum-compare-switch",             "-Wno-null-pointer-arithmetic",              "-Wno-null-dereference",                "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",    "-Wno-psabi",                           "-Wno-null-pointer-subtraction",             "-Wno-string-concatenation",            "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                    "-Wno-error=format",                    "-Wmissing-prototypes",                      "-Wstrict-prototypes",                  "-DHAVE_EXPAT_CONFIG_H",
    "-DXML_DEV_URANDOM=1",                "-UWIN32_LEAN_AND_MEAN",                "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",
    "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",
    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",
    "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",                    "-fcommon",                             "-Wno-format-insufficient-args",
    "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",      "-Wno-unused-parameter",                     "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call",
    "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",         "-Wno-pessimizing-move",
};
const cf_28 = [_][]const u8{
    "-DANDROID_STRICT",                     "-Wimplicit-fallthrough",                    "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",          "-std=gnu++20",
    "-DU_USING_ICU_NAMESPACE=0",            "-fno-rtti",                                 "-Wimplicit-int-float-conversion",            "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",
    "-Wno-pointer-to-int-cast",             "-Wno-unused-variable",                      "-Wno-missing-field-initializers",            "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare",
    "-Wno-tautological-type-limit-compare", "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",          "-Wno-deprecated-copy",           "-Wno-range-loop-construct",
    "-Wno-zero-as-null-pointer-constant",   "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion",       "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",
    "-Wno-align-mismatch",                  "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",        "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",
    "-Wno-error=invalid-offsetof",          "-Wno-vla-cxx-extension",
};
const cf_29 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wno-unused-parameter",                "-Wno-unused-variable",           "-Wno-parentheses-equality",          "-Wno-constant-logical-operand",
    "-Wno-sometimes-uninitialized",              "-D__GLIBC__",                          "-D_LARGEFILE64_SOURCE",          "-D_GNU_SOURCE",                      "-DLINUX",
    "-std=gnu23",                                "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",
};
const cf_30 = [_][]const u8{
    "-DANDROID_STRICT",                          "-Wimplicit-fallthrough",               "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",              "-Wno-unused-parameter",
    "-Wno-unused-variable",                      "-Wno-parentheses-equality",            "-Wno-constant-logical-operand",              "-Wno-sometimes-uninitialized",       "-D__GLIBC__",
    "-D_LARGEFILE64_SOURCE",                     "-D_GNU_SOURCE",                        "-DLINUX",                                    "-std=gnu++20",                       "-DU_USING_ICU_NAMESPACE=0",
    "-fno-rtti",                                 "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast",             "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",
    "-Wno-unused-variable",                      "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",                        "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion",        "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",                       "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",
    "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",                "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",
    "-Wno-error=unused-but-set-variable",        "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins",             "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",
    "-Wno-vla-cxx-extension",
};
const cf_31 = [_][]const u8{
    "-Wno-enum-compare",                    "-Wno-enum-compare-switch",       "-Wno-null-pointer-arithmetic",       "-Wno-null-dereference",                      "-Wno-pointer-compare",
    "-Wno-final-dtor-non-final-class",      "-Wno-psabi",                     "-Wno-null-pointer-subtraction",      "-Wno-string-concatenation",                  "-Wno-deprecated-non-prototype",
    "-Wno-deprecated",                      "-Wno-error=format",              "-Wimplicit-fallthrough",             "-D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS", "-Wno-gnu-include-next",
    "-Wunused",                             "-fvisibility=hidden",            "-DJNI_JARJAR_PREFIX=com/android/",   "-std=gnu++20",                               "-fno-rtti",
    "-Wimplicit-int-float-conversion",      "-Wno-void-pointer-to-enum-cast", "-Wno-void-pointer-to-int-cast",      "-Wno-pointer-to-int-cast",                   "-Wno-unused-variable",
    "-Wno-missing-field-initializers",      "-Wno-packed-non-pod",            "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",       "-Wno-implicit-int-float-conversion",
    "-Wno-tautological-overlap-compare",    "-Wno-deprecated-copy",           "-Wno-range-loop-construct",          "-Wno-zero-as-null-pointer-constant",         "-Wno-deprecated-anon-enum-enum-conversion",
    "-Wno-deprecated-enum-enum-conversion", "-Wno-error=pessimizing-move",    "-Wno-non-c-typedef-for-linkage",     "-Wno-align-mismatch",                        "-Wno-error=unused-but-set-variable",
    "-Wno-error=unused-but-set-parameter",  "-Wno-error=deprecated-builtins", "-Wno-error=deprecated",              "-Wno-error=invalid-offsetof",                "-Wno-vla-cxx-extension",
    "-fcommon",                             "-Wno-format-insufficient-args",  "-Wno-misleading-indentation",        "-Wno-bitwise-instead-of-logical",            "-Wno-unused-parameter",
    "-Wno-unused-but-set-parameter",        "-Wno-unqualified-std-cast-call", "-Wno-array-parameter",               "-Wno-gnu-offsetof-extensions",               "-Wno-pessimizing-move",
};

const inc_0 = [_]Inc{
    .{ .root = .vendor, .rel = "art/sigchainlib" },
    .{ .root = .vendor, .rel = "art/sigchainlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_1 = [_]Inc{
    .{ .root = .vendor, .rel = "system/libbase" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_2 = [_]Inc{
    .{ .root = .vendor, .rel = "system/logging/liblog" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/core/libcutils/include" },
    .{ .root = .vendor, .rel = "system/core/libprocessgroup/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_3 = [_]Inc{
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/fmtlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_4 = [_]Inc{
    .{ .root = .vendor, .rel = "external/lz4/lib" },
    .{ .root = .vendor, .rel = "external/lz4/lib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_5 = [_]Inc{
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "external/lzma" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_6 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libnativebridge/include" },
    .{ .root = .vendor, .rel = "art/libnativebridge" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libnativebridge/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_7 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libnativeloader" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libnativeloader/include" },
    .{ .root = .vendor, .rel = "art/libnativebridge/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_8 = [_]Inc{
    .{ .root = .vendor, .rel = "system/unwinding/libunwindstack/include" },
    .{ .root = .vendor, .rel = "system/unwinding/libunwindstack" },
    .{ .root = .vendor, .rel = "external/rust/android-crates-io/crates/rustc-demangle-capi/include" },
    .{ .root = .vendor, .rel = "external/zstd/lib" },
    .{ .root = .vendor, .rel = "system/libprocinfo/include" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_9 = [_]Inc{
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_10 = [_]Inc{
    .{ .root = .vendor, .rel = "external/zstd/lib" },
    .{ .root = .vendor, .rel = "external/zstd/lib/common" },
    .{ .root = .vendor, .rel = "external/zstd" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_11 = [_]Inc{
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "system/libziparchive/incfs_support/include" },
    .{ .root = .vendor, .rel = "system/libziparchive" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_12 = [_]Inc{
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_13 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_14 = [_]Inc{
    .{ .root = .vendor, .rel = "art/disassembler" },
    .{ .root = .vendor, .rel = "art/disassembler" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_16 = [_]Inc{
    .{ .root = .vendor, .rel = "art/odrefresh/include" },
    .{ .root = .vendor, .rel = "art/odrefresh/include" },
    .{ .root = .vendor, .rel = "art/odrefresh" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_17 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_18 = [_]Inc{
    .{ .root = .vendor, .rel = "art/compiler" },
    .{ .root = .vendor, .rel = "art/compiler" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .rel = "", .generated = .asm_defines },
    .{ .root = .vendor, .rel = "art/cmdline" },
    .{ .root = .vendor, .rel = "art/disassembler" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .rel = "gensrcs", .generated = .compiler_operators },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_19 = [_]Inc{
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "external/cpu_features" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_20 = [_]Inc{
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "external/cpu_features" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_21 = [_]Inc{
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/cmdline" },
    .{ .root = .vendor, .rel = "art/tools/cpp-define-generator" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .rel = "", .generated = .asm_defines },
    .{ .root = .vendor, .rel = "art/compiler/export" },
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "art/odrefresh/include" },
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/lz4/lib" },
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "art/libnativebridge/include" },
    .{ .root = .vendor, .rel = "art/libnativeloader/include" },
    .{ .root = .vendor, .rel = "art/sigchainlib" },
    .{ .root = .vendor, .rel = "system/unwinding/libunwindstack/include" },
    .{ .root = .vendor, .rel = "external/rust/android-crates-io/crates/rustc-demangle-capi/include" },
    .{ .root = .vendor, .rel = "external/zstd/lib" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .rel = "gensrcs", .generated = .runtime_operators },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_22 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_23 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .rel = "gensrcs", .generated = .dexfile_operators },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_24 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .rel = "gensrcs", .generated = .libartbase_operators },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_25 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "art/libartpalette" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_26 = [_]Inc{
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "external/boringssl" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_27 = [_]Inc{
    .{ .root = .vendor, .rel = "art/dex2oat" },
    .{ .root = .vendor, .rel = "art/dex2oat" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/lz4/lib" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .rel = "", .generated = .asm_defines },
    .{ .root = .vendor, .rel = "art/compiler" },
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .rel = "gensrcs", .generated = .dex2oat_operators },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_28 = [_]Inc{
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "libnativehelper" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_29 = [_]Inc{
    .{ .root = .vendor, .rel = "external/fdlibm" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_30 = [_]Inc{
    .{ .root = .vendor, .rel = "system/libprocinfo/include" },
    .{ .root = .vendor, .rel = "system/libprocinfo/include" },
    .{ .root = .vendor, .rel = "system/libprocinfo" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_31 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_32 = [_]Inc{
    .{ .root = .vendor, .rel = "art/build/flags" },
    .{ .root = .vendor, .rel = "build/make/tools/aconfig/aconfig_storage_read_api/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_33 = [_]Inc{
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "external/boringssl" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_34 = [_]Inc{
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "libnativehelper" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_35 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_36 = [_]Inc{
    .{ .root = .vendor, .rel = "art/dalvikvm" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "art/sigchainlib" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_37 = [_]Inc{
    .{ .root = .vendor, .rel = "art/dex2oat" },
    .{ .root = .vendor, .rel = "art/cmdline" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .root = .vendor, .rel = "art/disassembler" },
    .{ .root = .vendor, .rel = "art/compiler" },
    .{ .root = .vendor, .rel = "art/libprofile" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/unwinding/libunwindstack/include" },
    .{ .root = .vendor, .rel = "external/rust/android-crates-io/crates/rustc-demangle-capi/include" },
    .{ .root = .vendor, .rel = "external/zstd/lib" },
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "external/lzma/C" },
    .{ .root = .vendor, .rel = "art/libnativebridge/include" },
    .{ .root = .vendor, .rel = "art/libnativeloader/include" },
    .{ .root = .vendor, .rel = "art/odrefresh/include" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .rel = "", .generated = .asm_defines },
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "art/libelffile" },
    .{ .root = .vendor, .rel = "art/sigchainlib" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/lz4/lib" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "art/dex2oat" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_38 = [_]Inc{
    .{ .root = .vendor, .rel = "external/expat/lib" },
    .{ .root = .vendor, .rel = "external/expat/lib" },
    .{ .root = .vendor, .rel = "external/expat" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_39 = [_]Inc{
    .{ .root = .project, .rel = "src" },
    .{ .root = .vendor, .rel = "libcore" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "external/icu/libicu/ndk_headers" },
    .{ .rel = "", .host = .libcap },
    .{ .root = .vendor, .rel = "external/expat/lib" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_40 = [_]Inc{
    .{ .root = .project, .rel = "src" },
    .{ .root = .vendor, .rel = "libcore/luni/src/main/native" },
    .{ .root = .vendor, .rel = "libcore" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "external/icu/libicu/ndk_headers" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_41 = [_]Inc{
    .{ .root = .vendor, .rel = "libcore" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_42 = [_]Inc{
    .{ .root = .vendor, .rel = "art/libartpalette/include" },
    .{ .root = .vendor, .rel = "art/libartpalette" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_43 = [_]Inc{
    .{ .root = .vendor, .rel = "art/openjdkjvm" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .rel = "", .generated = .asm_defines },
    .{ .root = .vendor, .rel = "art/compiler" },
    .{ .root = .vendor, .rel = "external/cpu_features/include" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "art/libdexfile/external/include" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_44 = [_]Inc{
    .{ .root = .vendor, .rel = "external/conscrypt/common/src/jni/main/include" },
    .{ .root = .vendor, .rel = "external/conscrypt" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "external/boringssl/src/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_45 = [_]Inc{
    .{ .root = .vendor, .rel = "libnativehelper" },
    .{ .root = .vendor, .rel = "libnativehelper/header_only_include" },
    .{ .root = .vendor, .rel = "libnativehelper/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform" },
    .{ .root = .vendor, .rel = "libnativehelper/include_platform_header_only" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_46 = [_]Inc{
    .{ .root = .vendor, .rel = "art/test/generate-boot-image" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "prebuilts/runtime/mainline/platform/sdk/common_os/include/external/libcap/libcap/include" },
    .{ .rel = "include", .generated = .aconfig },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "system/libziparchive/include" },
    .{ .root = .vendor, .rel = "external/googletest/googletest/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
    .{ .root = .vendor, .rel = "external/zlib" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};
const inc_47 = [_]Inc{
    .{ .root = .vendor, .rel = "art/tools/cpp-define-generator" },
    .{ .root = .vendor, .rel = "art/libartbase" },
    .{ .root = .vendor, .rel = "external/tinyxml2" },
    .{ .root = .vendor, .rel = "system/libbase/include" },
    .{ .root = .vendor, .rel = "external/fmtlib/include" },
    .{ .root = .vendor, .rel = "libnativehelper/include_jni" },
    .{ .root = .vendor, .rel = "art/runtime" },
    .{ .root = .vendor, .rel = "external/dlmalloc" },
    .{ .root = .vendor, .rel = "art/libdexfile" },
    .{ .root = .vendor, .rel = "system/core/include" },
    .{ .root = .vendor, .rel = "system/logging/liblog/include" },
};

/// Resolved include sets, filled in by build().
const Incs = struct {
    inc_0: IncludeSet,
    inc_1: IncludeSet,
    inc_2: IncludeSet,
    inc_3: IncludeSet,
    inc_4: IncludeSet,
    inc_5: IncludeSet,
    inc_6: IncludeSet,
    inc_7: IncludeSet,
    inc_8: IncludeSet,
    inc_9: IncludeSet,
    inc_10: IncludeSet,
    inc_11: IncludeSet,
    inc_12: IncludeSet,
    inc_13: IncludeSet,
    inc_14: IncludeSet,
    inc_16: IncludeSet,
    inc_17: IncludeSet,
    inc_18: IncludeSet,
    inc_19: IncludeSet,
    inc_20: IncludeSet,
    inc_21: IncludeSet,
    inc_22: IncludeSet,
    inc_23: IncludeSet,
    inc_24: IncludeSet,
    inc_25: IncludeSet,
    inc_26: IncludeSet,
    inc_27: IncludeSet,
    inc_28: IncludeSet,
    inc_29: IncludeSet,
    inc_30: IncludeSet,
    inc_31: IncludeSet,
    inc_32: IncludeSet,
    inc_33: IncludeSet,
    inc_34: IncludeSet,
    inc_35: IncludeSet,
    inc_36: IncludeSet,
    inc_37: IncludeSet,
    inc_38: IncludeSet,
    inc_39: IncludeSet,
    inc_40: IncludeSet,
    inc_41: IncludeSet,
    inc_42: IncludeSet,
    inc_43: IncludeSet,
    inc_44: IncludeSet,
    inc_45: IncludeSet,
    inc_46: IncludeSet,
    inc_47: IncludeSet,
};

fn srcs_libsigchain_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/sigchainlib/sigchain.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_0),
    });
}
fn srcs_libdex2oat_static_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libbase/abi_compatibility.cpp", "system/libbase/chrono_utils.cpp",     "system/libbase/cmsg.cpp",        "system/libbase/file.cpp",
            "system/libbase/hex.cpp",               "system/libbase/logging.cpp",          "system/libbase/mapped_file.cpp", "system/libbase/parsebool.cpp",
            "system/libbase/parsenetaddress.cpp",   "system/libbase/posix_strerror_r.cpp", "system/libbase/process.cpp",     "system/libbase/properties.cpp",
            "system/libbase/result.cpp",            "system/libbase/stringprintf.cpp",     "system/libbase/strings.cpp",     "system/libbase/threads.cpp",
            "system/libbase/test_utils.cpp",        "system/libbase/errors_unix.cpp",
        },
        .flags = flags(b, o, &cf_1, I.inc_1),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/logging/liblog/log_event_list.cpp", "system/logging/liblog/log_event_write.cpp", "system/logging/liblog/logger_name.cpp", "system/logging/liblog/logger_read.cpp",
            "system/logging/liblog/logger_write.cpp",   "system/logging/liblog/logprint.cpp",        "system/logging/liblog/properties.cpp",  "system/logging/liblog/event_tag_map.cpp",
        },
        .flags = flags(b, o, &cf_2, I.inc_2),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/fmtlib/src/format.cc",
        },
        .flags = flags(b, o, &cf_3, I.inc_3),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/lz4/lib/lz4.c", "external/lz4/lib/lz4hc.c", "external/lz4/lib/lz4frame.c", "external/lz4/lib/xxhash.c",
        },
        .flags = flags(b, o, &cf_4, I.inc_4),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/lzma/C/7zAlloc.c",  "external/lzma/C/7zArcIn.c",   "external/lzma/C/7zBuf2.c",     "external/lzma/C/7zBuf.c",
            "external/lzma/C/7zCrc.c",    "external/lzma/C/7zCrcOpt.c",  "external/lzma/C/7zDec.c",      "external/lzma/C/7zFile.c",
            "external/lzma/C/7zStream.c", "external/lzma/C/Aes.c",       "external/lzma/C/AesOpt.c",     "external/lzma/C/Alloc.c",
            "external/lzma/C/Bcj2.c",     "external/lzma/C/Bra86.c",     "external/lzma/C/Bra.c",        "external/lzma/C/BraIA64.c",
            "external/lzma/C/CpuArch.c",  "external/lzma/C/Delta.c",     "external/lzma/C/LzFind.c",     "external/lzma/C/Lzma2Dec.c",
            "external/lzma/C/Lzma2Enc.c", "external/lzma/C/Lzma86Dec.c", "external/lzma/C/Lzma86Enc.c",  "external/lzma/C/LzmaDec.c",
            "external/lzma/C/LzmaEnc.c",  "external/lzma/C/LzmaLib.c",   "external/lzma/C/Ppmd7.c",      "external/lzma/C/Ppmd7Dec.c",
            "external/lzma/C/Ppmd7Enc.c", "external/lzma/C/Sha256.c",    "external/lzma/C/Sha256Opt.c",  "external/lzma/C/Sort.c",
            "external/lzma/C/Xz.c",       "external/lzma/C/XzCrc64.c",   "external/lzma/C/XzCrc64Opt.c", "external/lzma/C/XzDec.c",
            "external/lzma/C/XzEnc.c",    "external/lzma/C/XzIn.c",
        },
        .flags = flags(b, o, &cf_5, I.inc_5),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libnativebridge/native_bridge.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_6),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libnativeloader/native_loader.cpp",
        },
        .flags = flags(b, o, &cf_0, I.inc_7),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/unwinding/libunwindstack/AndroidUnwinder.cpp", "system/unwinding/libunwindstack/ArmExidx.cpp",            "system/unwinding/libunwindstack/Demangle.cpp",     "system/unwinding/libunwindstack/DexFiles.cpp",
            "system/unwinding/libunwindstack/DwarfCfa.cpp",        "system/unwinding/libunwindstack/DwarfEhFrameWithHdr.cpp", "system/unwinding/libunwindstack/DwarfMemory.cpp",  "system/unwinding/libunwindstack/DwarfOp.cpp",
            "system/unwinding/libunwindstack/DwarfSection.cpp",    "system/unwinding/libunwindstack/Elf.cpp",                 "system/unwinding/libunwindstack/ElfInterface.cpp", "system/unwinding/libunwindstack/ElfInterfaceArm.cpp",
            "system/unwinding/libunwindstack/Global.cpp",          "system/unwinding/libunwindstack/JitDebug.cpp",            "system/unwinding/libunwindstack/MapInfo.cpp",      "system/unwinding/libunwindstack/Maps.cpp",
            "system/unwinding/libunwindstack/Memory.cpp",          "system/unwinding/libunwindstack/MemoryMte.cpp",           "system/unwinding/libunwindstack/MemoryXz.cpp",     "system/unwinding/libunwindstack/Regs.cpp",
            "system/unwinding/libunwindstack/RegsArm.cpp",         "system/unwinding/libunwindstack/RegsArm64.cpp",           "system/unwinding/libunwindstack/RegsX86.cpp",      "system/unwinding/libunwindstack/RegsX86_64.cpp",
            "system/unwinding/libunwindstack/RegsRiscv64.cpp",     "system/unwinding/libunwindstack/Symbols.cpp",             "system/unwinding/libunwindstack/ThreadEntry.cpp",  "system/unwinding/libunwindstack/ThreadUnwinder.cpp",
            "system/unwinding/libunwindstack/Unwinder.cpp",        "system/unwinding/libunwindstack/DexFile.cpp",             "system/unwinding/libunwindstack/LogAndroid.cpp",
        },
        .flags = flags(b, o, &cf_6, I.inc_8),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/unwinding/libunwindstack/AsmGetRegsX86_64.S",
        },
        .flags = flags(b, o, &cf_7, I.inc_8),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/zlib/adler32.c", "external/zlib/adler32_simd.c", "external/zlib/compress.c",    "external/zlib/cpu_features.c",
            "external/zlib/crc32.c",   "external/zlib/crc32_simd.c",   "external/zlib/crc_folding.c", "external/zlib/deflate.c",
            "external/zlib/gzclose.c", "external/zlib/gzlib.c",        "external/zlib/gzread.c",      "external/zlib/gzwrite.c",
            "external/zlib/infback.c", "external/zlib/inffast.c",      "external/zlib/inflate.c",     "external/zlib/inftrees.c",
            "external/zlib/trees.c",   "external/zlib/uncompr.c",      "external/zlib/zutil.c",
        },
        .flags = flags(b, o, &cf_8, I.inc_9),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/zstd/lib/common/debug.c",                    "external/zstd/lib/common/entropy_common.c",            "external/zstd/lib/common/error_private.c",              "external/zstd/lib/common/fse_decompress.c",
            "external/zstd/lib/common/pool.c",                     "external/zstd/lib/common/threading.c",                 "external/zstd/lib/common/xxhash.c",                     "external/zstd/lib/common/zstd_common.c",
            "external/zstd/lib/compress/fse_compress.c",           "external/zstd/lib/compress/hist.c",                    "external/zstd/lib/compress/huf_compress.c",             "external/zstd/lib/compress/zstd_compress.c",
            "external/zstd/lib/compress/zstd_compress_literals.c", "external/zstd/lib/compress/zstd_compress_sequences.c", "external/zstd/lib/compress/zstd_compress_superblock.c", "external/zstd/lib/compress/zstd_double_fast.c",
            "external/zstd/lib/compress/zstd_fast.c",              "external/zstd/lib/compress/zstd_lazy.c",               "external/zstd/lib/compress/zstd_ldm.c",                 "external/zstd/lib/compress/zstd_opt.c",
            "external/zstd/lib/compress/zstd_preSplit.c",          "external/zstd/lib/compress/zstdmt_compress.c",         "external/zstd/lib/decompress/huf_decompress.c",         "external/zstd/lib/decompress/zstd_ddict.c",
            "external/zstd/lib/decompress/zstd_decompress.c",      "external/zstd/lib/decompress/zstd_decompress_block.c", "external/zstd/lib/deprecated/zbuff_common.c",           "external/zstd/lib/deprecated/zbuff_compress.c",
            "external/zstd/lib/deprecated/zbuff_decompress.c",     "external/zstd/lib/dictBuilder/cover.c",                "external/zstd/lib/dictBuilder/divsufsort.c",            "external/zstd/lib/dictBuilder/fastcover.c",
            "external/zstd/lib/dictBuilder/zdict.c",               "external/zstd/lib/legacy/zstd_v01.c",                  "external/zstd/lib/legacy/zstd_v02.c",                   "external/zstd/lib/legacy/zstd_v03.c",
            "external/zstd/lib/legacy/zstd_v04.c",                 "external/zstd/lib/legacy/zstd_v05.c",                  "external/zstd/lib/legacy/zstd_v06.c",                   "external/zstd/lib/legacy/zstd_v07.c",
        },
        .flags = flags(b, o, &cf_9, I.inc_10),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libziparchive/zip_archive.cc", "system/libziparchive/zip_archive_stream_entry.cc", "system/libziparchive/zip_cd_entry_map.cc", "system/libziparchive/zip_error.cpp",
            "system/libziparchive/zip_writer.cc",
        },
        .flags = flags(b, o, &cf_10, I.inc_11),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/tinyxml2/tinyxml2.cpp",
        },
        .flags = flags(b, o, &cf_11, I.inc_12),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libprofile/profile/profile_boot_info.cc", "art/libprofile/profile/profile_compilation_info.cc",
        },
        .flags = flags(b, o, &cf_12, I.inc_13),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/disassembler/disassembler.cc", "art/disassembler/disassembler_x86.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_14),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/odrefresh/odr_statslog_host.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_16),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libelffile/elf/xz_utils.cc",                "art/libelffile/stream/buffered_output_stream.cc", "art/libelffile/stream/file_output_stream.cc", "art/libelffile/stream/output_stream.cc",
            "art/libelffile/stream/vector_output_stream.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_17),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/compiler/debug/elf_debug_writer.cc",                "art/compiler/dex/inline_method_analyser.cc",                   "art/compiler/driver/compiler_options.cc",                             "art/compiler/driver/dex_compilation_unit.cc",
            "art/compiler/jit/jit_compiler.cc",                      "art/compiler/jit/jit_logger.cc",                               "art/compiler/jni/quick/calling_convention.cc",                        "art/compiler/jni/quick/jni_compiler.cc",
            "art/compiler/optimizing/block_builder.cc",              "art/compiler/optimizing/block_namer.cc",                       "art/compiler/optimizing/bounds_check_elimination.cc",                 "art/compiler/optimizing/builder.cc",
            "art/compiler/optimizing/cha_guard_optimization.cc",     "art/compiler/optimizing/code_generation_data.cc",              "art/compiler/optimizing/code_generator.cc",                           "art/compiler/optimizing/code_generator_utils.cc",
            "art/compiler/optimizing/code_sinking.cc",               "art/compiler/optimizing/constant_folding.cc",                  "art/compiler/optimizing/constructor_fence_redundancy_elimination.cc", "art/compiler/optimizing/control_flow_simplifier.cc",
            "art/compiler/optimizing/data_type.cc",                  "art/compiler/optimizing/dead_code_elimination.cc",             "art/compiler/optimizing/escape.cc",                                   "art/compiler/optimizing/graph_checker.cc",
            "art/compiler/optimizing/graph_visualizer.cc",           "art/compiler/optimizing/gvn.cc",                               "art/compiler/optimizing/handle_cache.cc",                             "art/compiler/optimizing/induction_var_analysis.cc",
            "art/compiler/optimizing/induction_var_range.cc",        "art/compiler/optimizing/inliner.cc",                           "art/compiler/optimizing/instruction_builder.cc",                      "art/compiler/optimizing/instruction_simplifier.cc",
            "art/compiler/optimizing/intrinsic_objects.cc",          "art/compiler/optimizing/intrinsics.cc",                        "art/compiler/optimizing/licm.cc",                                     "art/compiler/optimizing/linear_order.cc",
            "art/compiler/optimizing/load_store_analysis.cc",        "art/compiler/optimizing/load_store_elimination.cc",            "art/compiler/optimizing/locations.cc",                                "art/compiler/optimizing/loop_analysis.cc",
            "art/compiler/optimizing/loop_optimization.cc",          "art/compiler/optimizing/nodes.cc",                             "art/compiler/optimizing/nodes_vector.cc",                             "art/compiler/optimizing/optimizing_compiler.cc",
            "art/compiler/optimizing/parallel_move_resolver.cc",     "art/compiler/optimizing/prepare_for_register_allocation.cc",   "art/compiler/optimizing/profiling_info_builder.cc",                   "art/compiler/optimizing/reference_type_info.cc",
            "art/compiler/optimizing/reference_type_propagation.cc", "art/compiler/optimizing/register_allocation_resolver.cc",      "art/compiler/optimizing/register_allocator.cc",                       "art/compiler/optimizing/register_allocator_linear_scan.cc",
            "art/compiler/optimizing/scheduler.cc",                  "art/compiler/optimizing/sharpening.cc",                        "art/compiler/optimizing/side_effects_analysis.cc",                    "art/compiler/optimizing/ssa_builder.cc",
            "art/compiler/optimizing/ssa_liveness_analysis.cc",      "art/compiler/optimizing/ssa_phi_elimination.cc",               "art/compiler/optimizing/stack_map_stream.cc",                         "art/compiler/optimizing/superblock_cloner.cc",
            "art/compiler/optimizing/write_barrier_elimination.cc",  "art/compiler/trampolines/trampoline_compiler.cc",              "art/compiler/utils/assembler.cc",                                     "art/compiler/utils/jni_macro_assembler.cc",
            "art/compiler/compiler.cc",                              "art/compiler/optimizing/instruction_simplifier_x86_shared.cc", "art/compiler/optimizing/x86_memory_gen.cc",                           "art/compiler/jni/quick/x86_64/calling_convention_x86_64.cc",
            "art/compiler/optimizing/intrinsics_x86_64.cc",          "art/compiler/optimizing/instruction_simplifier_x86_64.cc",     "art/compiler/optimizing/code_generator_x86_64.cc",                    "art/compiler/optimizing/code_generator_vector_x86_64.cc",
            "art/compiler/utils/x86_64/assembler_x86_64.cc",         "art/compiler/utils/x86_64/jni_macro_assembler_x86_64.cc",      "art/compiler/utils/x86_64/managed_register_x86_64.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_18),
    });
    // The packaged ART patch 0008 exposes the shared x86 memory pass to x86_64.
    // Compile that source directly; applying a second build-time patch diverges
    // from the dependency hash and fails on a fresh source extraction.
    m.addCSourceFile(.{ .file = o.vendor.path(b, "art/compiler/optimizing/optimization.cc"), .flags = compilerOptimizationFlags(b, o, I) });
    addGeneratedSources(m, o.generated.compiler_operators, flags(b, o, &cf_0, I.inc_18));
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/cpu_features/src/filesystem.c", "external/cpu_features/src/stack_line_reader.c", "external/cpu_features/src/string_view.c",
        },
        .flags = flags(b, o, &cf_14, I.inc_19),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/cpu_features/src/impl_x86_linux_or_android.c",
        },
        .flags = flags(b, o, &cf_15, I.inc_20),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/runtime/app_info.cc",                                                  "art/runtime/art_field.cc",                                    "art/runtime/art_method.cc",                                      "art/runtime/backtrace_helper.cc",
            "art/runtime/barrier.cc",                                                   "art/runtime/base/gc_visited_arena_pool.cc",                   "art/runtime/base/locks.cc",                                      "art/runtime/base/mem_map_arena_pool.cc",
            "art/runtime/base/mutex.cc",                                                "art/runtime/base/quasi_atomic.cc",                            "art/runtime/base/timing_logger.cc",                              "art/runtime/cha.cc",
            "art/runtime/class_linker.cc",                                              "art/runtime/class_loader_context.cc",                         "art/runtime/class_root.cc",                                      "art/runtime/class_table.cc",
            "art/runtime/common_throws.cc",                                             "art/runtime/compat_framework.cc",                             "art/runtime/debug_print.cc",                                     "art/runtime/debugger.cc",
            "art/runtime/dex/dex_file_annotations.cc",                                  "art/runtime/dex_register_location.cc",                        "art/runtime/exec_utils.cc",                                      "art/runtime/fault_handler.cc",
            "art/runtime/gc/accounting/bitmap.cc",                                      "art/runtime/gc/accounting/card_table.cc",                     "art/runtime/gc/accounting/heap_bitmap.cc",                       "art/runtime/gc/accounting/mod_union_table.cc",
            "art/runtime/gc/accounting/remembered_set.cc",                              "art/runtime/gc/accounting/space_bitmap.cc",                   "art/runtime/gc/allocation_record.cc",                            "art/runtime/gc/allocator/art-dlmalloc.cc",
            "art/runtime/gc/allocator/rosalloc.cc",                                     "art/runtime/gc/collector/concurrent_copying.cc",              "art/runtime/gc/collector/garbage_collector.cc",                  "art/runtime/gc/collector/immune_region.cc",
            "art/runtime/gc/collector/immune_spaces.cc",                                "art/runtime/gc/collector/mark_compact.cc",                    "art/runtime/gc/collector/mark_sweep.cc",                         "art/runtime/gc/collector/partial_mark_sweep.cc",
            "art/runtime/gc/collector/semi_space.cc",                                   "art/runtime/gc/collector/sticky_mark_sweep.cc",               "art/runtime/gc/gc_cause.cc",                                     "art/runtime/gc/heap.cc",
            "art/runtime/gc/reference_processor.cc",                                    "art/runtime/gc/reference_queue.cc",                           "art/runtime/gc/scoped_gc_critical_section.cc",                   "art/runtime/gc/space/bump_pointer_space.cc",
            "art/runtime/gc/space/dlmalloc_space.cc",                                   "art/runtime/gc/space/image_space.cc",                         "art/runtime/gc/space/large_object_space.cc",                     "art/runtime/gc/space/malloc_space.cc",
            "art/runtime/gc/space/region_space.cc",                                     "art/runtime/gc/space/rosalloc_space.cc",                      "art/runtime/gc/space/space.cc",                                  "art/runtime/gc/space/zygote_space.cc",
            "art/runtime/gc/task_processor.cc",                                         "art/runtime/gc/verification.cc",                              "art/runtime/handle.cc",                                          "art/runtime/hidden_api.cc",
            "art/runtime/hprof/hprof.cc",                                               "art/runtime/indirect_reference_table.cc",                     "art/runtime/instrumentation.cc",                                 "art/runtime/intern_table.cc",
            "art/runtime/interpreter/interpreter.cc",                                   "art/runtime/interpreter/interpreter_cache.cc",                "art/runtime/interpreter/interpreter_common.cc",                  "art/runtime/interpreter/interpreter_switch_impl0.cc",
            "art/runtime/interpreter/lock_count_data.cc",                               "art/runtime/interpreter/shadow_frame.cc",                     "art/runtime/interpreter/unstarted_runtime.cc",                   "art/runtime/java_frame_root_info.cc",
            "art/runtime/javaheapprof/javaheapsampler.cc",                              "art/runtime/jit/debugger_interface.cc",                       "art/runtime/jit/jit.cc",                                         "art/runtime/jit/jit_code_cache.cc",
            "art/runtime/jit/jit_memory_region.cc",                                     "art/runtime/jit/jit_options.cc",                              "art/runtime/jit/profile_saver.cc",                               "art/runtime/jit/profiling_info.cc",
            "art/runtime/jit/small_pattern_matcher.cc",                                 "art/runtime/jni/check_jni.cc",                                "art/runtime/jni/java_vm_ext.cc",                                 "art/runtime/jni/jni_env_ext.cc",
            "art/runtime/jni/jni_id_manager.cc",                                        "art/runtime/jni/jni_internal.cc",                             "art/runtime/jni/local_reference_table.cc",                       "art/runtime/method_handles.cc",
            "art/runtime/metrics/reporter.cc",                                          "art/runtime/mirror/array.cc",                                 "art/runtime/mirror/class.cc",                                    "art/runtime/mirror/class_ext.cc",
            "art/runtime/mirror/dex_cache.cc",                                          "art/runtime/mirror/emulated_stack_frame.cc",                  "art/runtime/mirror/executable.cc",                               "art/runtime/mirror/field.cc",
            "art/runtime/mirror/method.cc",                                             "art/runtime/mirror/method_handle_impl.cc",                    "art/runtime/mirror/method_handles_lookup.cc",                    "art/runtime/mirror/method_type.cc",
            "art/runtime/mirror/object.cc",                                             "art/runtime/mirror/stack_frame_info.cc",                      "art/runtime/mirror/stack_trace_element.cc",                      "art/runtime/mirror/string.cc",
            "art/runtime/mirror/throwable.cc",                                          "art/runtime/mirror/var_handle.cc",                            "art/runtime/monitor.cc",                                         "art/runtime/monitor_objects_stack_visitor.cc",
            "art/runtime/native/dalvik_system_BaseDexClassLoader.cc",                   "art/runtime/native/dalvik_system_DexFile.cc",                 "art/runtime/native/dalvik_system_VMDebug.cc",                    "art/runtime/native/dalvik_system_VMRuntime.cc",
            "art/runtime/native/dalvik_system_VMStack.cc",                              "art/runtime/native/dalvik_system_ZygoteHooks.cc",             "art/runtime/native/java_lang_Class.cc",                          "art/runtime/native/java_lang_Object.cc",
            "art/runtime/native/java_lang_StackStreamFactory.cc",                       "art/runtime/native/java_lang_String.cc",                      "art/runtime/native/java_lang_StringFactory.cc",                  "art/runtime/native/java_lang_System.cc",
            "art/runtime/native/java_lang_Thread.cc",                                   "art/runtime/native/java_lang_Throwable.cc",                   "art/runtime/native/java_lang_VMClassLoader.cc",                  "art/runtime/native/java_lang_invoke_MethodHandle.cc",
            "art/runtime/native/java_lang_invoke_MethodHandleImpl.cc",                  "art/runtime/native/java_lang_ref_FinalizerReference.cc",      "art/runtime/native/java_lang_ref_Reference.cc",                  "art/runtime/native/java_lang_reflect_Array.cc",
            "art/runtime/native/java_lang_reflect_Constructor.cc",                      "art/runtime/native/java_lang_reflect_Executable.cc",          "art/runtime/native/java_lang_reflect_Field.cc",                  "art/runtime/native/java_lang_reflect_Method.cc",
            "art/runtime/native/java_lang_reflect_Parameter.cc",                        "art/runtime/native/java_lang_reflect_Proxy.cc",               "art/runtime/native/java_util_concurrent_atomic_AtomicLong.cc",   "art/runtime/native/jdk_internal_misc_Unsafe.cc",
            "art/runtime/native/libcore_io_Memory.cc",                                  "art/runtime/native/libcore_util_CharsetUtils.cc",             "art/runtime/native/org_apache_harmony_dalvik_ddmc_DdmServer.cc", "art/runtime/native/org_apache_harmony_dalvik_ddmc_DdmVmInternal.cc",
            "art/runtime/native/sun_misc_Unsafe.cc",                                    "art/runtime/native_bridge_art_interface.cc",                  "art/runtime/native_stack_dump.cc",                               "art/runtime/non_debuggable_classes.cc",
            "art/runtime/nterp_helpers.cc",                                             "art/runtime/oat/elf_file.cc",                                 "art/runtime/oat/image.cc",                                       "art/runtime/oat/index_bss_mapping.cc",
            "art/runtime/oat/jni_stub_hash_map.cc",                                     "art/runtime/oat/oat.cc",                                      "art/runtime/oat/oat_file.cc",                                    "art/runtime/oat/oat_file_assistant.cc",
            "art/runtime/oat/oat_file_assistant_context.cc",                            "art/runtime/oat/oat_file_manager.cc",                         "art/runtime/oat/oat_quick_method_header.cc",                     "art/runtime/oat/sdc_file.cc",
            "art/runtime/oat/stack_map.cc",                                             "art/runtime/object_lock.cc",                                  "art/runtime/offsets.cc",                                         "art/runtime/parsed_options.cc",
            "art/runtime/plugin.cc",                                                    "art/runtime/quick_exception_handler.cc",                      "art/runtime/read_barrier.cc",                                    "art/runtime/reference_table.cc",
            "art/runtime/reflection.cc",                                                "art/runtime/reflective_handle_scope.cc",                      "art/runtime/reflective_value_visitor.cc",                        "art/runtime/runtime.cc",
            "art/runtime/runtime_callbacks.cc",                                         "art/runtime/runtime_common.cc",                               "art/runtime/runtime_image.cc",                                   "art/runtime/runtime_intrinsics.cc",
            "art/runtime/runtime_options.cc",                                           "art/runtime/scoped_thread_state_change.cc",                   "art/runtime/signal_catcher.cc",                                  "art/runtime/stack.cc",
            "art/runtime/startup_completed_task.cc",                                    "art/runtime/string_builder_append.cc",                        "art/runtime/thread.cc",                                          "art/runtime/thread_list.cc",
            "art/runtime/thread_pool.cc",                                               "art/runtime/ti/agent.cc",                                     "art/runtime/trace.cc",                                           "art/runtime/trace_profile.cc",
            "art/runtime/var_handles.cc",                                               "art/runtime/vdex_file.cc",                                    "art/runtime/verifier/class_verifier.cc",                         "art/runtime/verifier/instruction_flags.cc",
            "art/runtime/verifier/method_verifier.cc",                                  "art/runtime/verifier/reg_type.cc",                            "art/runtime/verifier/reg_type_cache.cc",                         "art/runtime/verifier/register_line.cc",
            "art/runtime/verifier/verifier_deps.cc",                                    "art/runtime/verify_object.cc",                                "art/runtime/well_known_classes.cc",                              "art/runtime/arch/context.cc",
            "art/runtime/arch/instruction_set_features.cc",                             "art/runtime/arch/memcmp16.cc",                                "art/runtime/arch/arm/instruction_set_features_arm.cc",           "art/runtime/arch/arm/registers_arm.cc",
            "art/runtime/arch/arm64/instruction_set_features_arm64.cc",                 "art/runtime/arch/arm64/registers_arm64.cc",                   "art/runtime/arch/riscv64/instruction_set_features_riscv64.cc",   "art/runtime/arch/riscv64/registers_riscv64.cc",
            "art/runtime/arch/x86/instruction_set_features_x86.cc",                     "art/runtime/arch/x86/registers_x86.cc",                       "art/runtime/arch/x86_64/registers_x86_64.cc",                    "art/runtime/entrypoints/entrypoint_utils.cc",
            "art/runtime/entrypoints/jni/jni_entrypoints.cc",                           "art/runtime/entrypoints/math_entrypoints.cc",                 "art/runtime/entrypoints/quick/quick_alloc_entrypoints.cc",       "art/runtime/entrypoints/quick/quick_cast_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_deoptimization_entrypoints.cc",        "art/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc", "art/runtime/entrypoints/quick/quick_entrypoints_enum.cc",        "art/runtime/entrypoints/quick/quick_field_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_fillarray_entrypoints.cc",             "art/runtime/entrypoints/quick/quick_jni_entrypoints.cc",      "art/runtime/entrypoints/quick/quick_lock_entrypoints.cc",        "art/runtime/entrypoints/quick/quick_math_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_string_builder_append_entrypoints.cc", "art/runtime/entrypoints/quick/quick_thread_entrypoints.cc",   "art/runtime/entrypoints/quick/quick_throw_entrypoints.cc",       "art/runtime/entrypoints/quick/quick_trampoline_entrypoints.cc",
            "art/runtime/monitor_linux.cc",                                             "art/runtime/runtime_linux.cc",                                "art/runtime/thread_linux.cc",                                    "art/runtime/interpreter/mterp/nterp.cc",
            "art/runtime/arch/x86_64/thread_x86_64.cc",                                 "art/runtime/monitor_pool.cc",                                 "art/runtime/arch/x86/fault_handler_x86.cc",                      "art/runtime/arch/x86_64/context_x86_64.cc",
            "art/runtime/arch/x86_64/entrypoints_init_x86_64.cc",
        },
        .flags = flags(b, o, &cf_16, I.inc_21),
    });
    addGeneratedSources(m, o.generated.runtime_operators, flags(b, o, &cf_16, I.inc_21));
    m.addCSourceFile(.{ .file = o.generated.mterp, .flags = flags(b, o, &cf_17, I.inc_21) });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/runtime/arch/x86_64/memcmp16_x86_64.S", "art/runtime/arch/x86_64/native_entrypoints_x86_64.S", "art/runtime/arch/x86_64/jni_entrypoints_x86_64.S", "art/runtime/arch/x86_64/quick_entrypoints_x86_64.S",
        },
        .flags = flags(b, o, &cf_17, I.inc_21),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libdexfile/external/dex_file_supp.cc",
        },
        .flags = flags(b, o, &cf_18, I.inc_22),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libdexfile/dex/art_dex_file_loader.cc",         "art/libdexfile/dex/compact_dex_file.cc",           "art/libdexfile/dex/compact_offset_table.cc", "art/libdexfile/dex/descriptors_names.cc",
            "art/libdexfile/dex/dex_file.cc",                    "art/libdexfile/dex/dex_file_exception_helpers.cc", "art/libdexfile/dex/dex_file_layout.cc",      "art/libdexfile/dex/dex_file_loader.cc",
            "art/libdexfile/dex/dex_file_tracking_registrar.cc", "art/libdexfile/dex/dex_file_verifier.cc",          "art/libdexfile/dex/dex_instruction.cc",      "art/libdexfile/dex/modifiers.cc",
            "art/libdexfile/dex/primitive.cc",                   "art/libdexfile/dex/signature.cc",                  "art/libdexfile/dex/standard_dex_file.cc",    "art/libdexfile/dex/type_lookup_table.cc",
            "art/libdexfile/dex/utf.cc",                         "art/libdexfile/external/dex_file_ext.cc",
        },
        .flags = flags(b, o, &cf_19, I.inc_23),
    });
    addGeneratedSources(m, o.generated.dexfile_operators, flags(b, o, &cf_19, I.inc_23));
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartbase/arch/instruction_set.cc",        "art/libartbase/base/allocator.cc",                          "art/libartbase/base/arena_allocator.cc",        "art/libartbase/base/arena_bit_vector.cc",
            "art/libartbase/base/bit_vector.cc",             "art/libartbase/base/compiler_filter.cc",                    "art/libartbase/base/file_magic.cc",             "art/libartbase/base/file_utils.cc",
            "art/libartbase/base/flags.cc",                  "art/libartbase/base/hex_dump.cc",                           "art/libartbase/base/logging.cc",                "art/libartbase/base/malloc_arena_pool.cc",
            "art/libartbase/base/membarrier.cc",             "art/libartbase/base/memfd.cc",                              "art/libartbase/base/memory_region.cc",          "art/libartbase/base/mem_map.cc",
            "art/libartbase/base/metrics/metrics_common.cc", "art/libartbase/base/os_linux.cc",                           "art/libartbase/base/pointer_size.cc",           "art/libartbase/base/runtime_debug.cc",
            "art/libartbase/base/scoped_arena_allocator.cc", "art/libartbase/base/scoped_flock.cc",                       "art/libartbase/base/socket_peer_is_trusted.cc", "art/libartbase/base/time_utils.cc",
            "art/libartbase/base/unix_file/fd_file.cc",      "art/libartbase/base/unix_file/random_access_file_utils.cc", "art/libartbase/base/utils.cc",                  "art/libartbase/base/zip_archive.cc",
            "art/libartbase/base/globals_unix.cc",           "art/libartbase/base/mem_map_unix.cc",
        },
        .flags = flags(b, o, &cf_20, I.inc_24),
    });
    addGeneratedSources(m, o.generated.libartbase_operators, flags(b, o, &cf_20, I.inc_24));
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartpalette/system/palette_fake.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_25),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/sigchainlib/sigchain_fake.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_0),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/boringssl/src/crypto/asn1/a_bitstr.cc",                "external/boringssl/src/crypto/asn1/a_bool.cc",                     "external/boringssl/src/crypto/asn1/a_d2i_fp.cc",           "external/boringssl/src/crypto/asn1/a_dup.cc",
            "external/boringssl/src/crypto/asn1/a_gentm.cc",                 "external/boringssl/src/crypto/asn1/a_i2d_fp.cc",                   "external/boringssl/src/crypto/asn1/a_int.cc",              "external/boringssl/src/crypto/asn1/a_mbstr.cc",
            "external/boringssl/src/crypto/asn1/a_object.cc",                "external/boringssl/src/crypto/asn1/a_octet.cc",                    "external/boringssl/src/crypto/asn1/a_strex.cc",            "external/boringssl/src/crypto/asn1/a_strnid.cc",
            "external/boringssl/src/crypto/asn1/a_time.cc",                  "external/boringssl/src/crypto/asn1/a_type.cc",                     "external/boringssl/src/crypto/asn1/a_utctm.cc",            "external/boringssl/src/crypto/asn1/asn1_lib.cc",
            "external/boringssl/src/crypto/asn1/asn1_par.cc",                "external/boringssl/src/crypto/asn1/asn_pack.cc",                   "external/boringssl/src/crypto/asn1/f_int.cc",              "external/boringssl/src/crypto/asn1/f_string.cc",
            "external/boringssl/src/crypto/asn1/posix_time.cc",              "external/boringssl/src/crypto/asn1/tasn_dec.cc",                   "external/boringssl/src/crypto/asn1/tasn_enc.cc",           "external/boringssl/src/crypto/asn1/tasn_fre.cc",
            "external/boringssl/src/crypto/asn1/tasn_new.cc",                "external/boringssl/src/crypto/asn1/tasn_typ.cc",                   "external/boringssl/src/crypto/asn1/tasn_utl.cc",           "external/boringssl/src/crypto/base64/base64.cc",
            "external/boringssl/src/crypto/bio/bio.cc",                      "external/boringssl/src/crypto/bio/bio_mem.cc",                     "external/boringssl/src/crypto/bio/connect.cc",             "external/boringssl/src/crypto/bio/errno.cc",
            "external/boringssl/src/crypto/bio/fd.cc",                       "external/boringssl/src/crypto/bio/file.cc",                        "external/boringssl/src/crypto/bio/hexdump.cc",             "external/boringssl/src/crypto/bio/pair.cc",
            "external/boringssl/src/crypto/bio/printf.cc",                   "external/boringssl/src/crypto/bio/socket.cc",                      "external/boringssl/src/crypto/bio/socket_helper.cc",       "external/boringssl/src/crypto/blake2/blake2.cc",
            "external/boringssl/src/crypto/bn_extra/bn_asn1.cc",             "external/boringssl/src/crypto/bn_extra/convert.cc",                "external/boringssl/src/crypto/buf/buf.cc",                 "external/boringssl/src/crypto/bytestring/asn1_compat.cc",
            "external/boringssl/src/crypto/bytestring/ber.cc",               "external/boringssl/src/crypto/bytestring/cbb.cc",                  "external/boringssl/src/crypto/bytestring/cbs.cc",          "external/boringssl/src/crypto/bytestring/unicode.cc",
            "external/boringssl/src/crypto/chacha/chacha.cc",                "external/boringssl/src/crypto/cipher_extra/cipher_extra.cc",       "external/boringssl/src/crypto/cipher_extra/derive_key.cc", "external/boringssl/src/crypto/cipher_extra/e_aesctrhmac.cc",
            "external/boringssl/src/crypto/cipher_extra/e_aesgcmsiv.cc",     "external/boringssl/src/crypto/cipher_extra/e_chacha20poly1305.cc", "external/boringssl/src/crypto/cipher_extra/e_des.cc",      "external/boringssl/src/crypto/cipher_extra/e_null.cc",
            "external/boringssl/src/crypto/cipher_extra/e_rc2.cc",           "external/boringssl/src/crypto/cipher_extra/e_rc4.cc",              "external/boringssl/src/crypto/cipher_extra/e_tls.cc",      "external/boringssl/src/crypto/cipher_extra/tls_cbc.cc",
            "external/boringssl/src/crypto/conf/conf.cc",                    "external/boringssl/src/crypto/cpu_aarch64_apple.cc",               "external/boringssl/src/crypto/cpu_aarch64_fuchsia.cc",     "external/boringssl/src/crypto/cpu_aarch64_linux.cc",
            "external/boringssl/src/crypto/cpu_aarch64_openbsd.cc",          "external/boringssl/src/crypto/cpu_aarch64_sysreg.cc",              "external/boringssl/src/crypto/cpu_aarch64_win.cc",         "external/boringssl/src/crypto/cpu_arm_freebsd.cc",
            "external/boringssl/src/crypto/cpu_arm_linux.cc",                "external/boringssl/src/crypto/cpu_intel.cc",                       "external/boringssl/src/crypto/crypto.cc",                  "external/boringssl/src/crypto/curve25519/curve25519.cc",
            "external/boringssl/src/crypto/curve25519/curve25519_64_adx.cc", "external/boringssl/src/crypto/curve25519/spake25519.cc",           "external/boringssl/src/crypto/des/des.cc",                 "external/boringssl/src/crypto/dh_extra/dh_asn1.cc",
            "external/boringssl/src/crypto/dh_extra/params.cc",              "external/boringssl/src/crypto/digest_extra/digest_extra.cc",       "external/boringssl/src/crypto/dsa/dsa.cc",                 "external/boringssl/src/crypto/dsa/dsa_asn1.cc",
            "external/boringssl/src/crypto/ec_extra/ec_asn1.cc",             "external/boringssl/src/crypto/ec_extra/ec_derive.cc",              "external/boringssl/src/crypto/ec_extra/hash_to_curve.cc",  "external/boringssl/src/crypto/ecdh_extra/ecdh_extra.cc",
            "external/boringssl/src/crypto/ecdsa_extra/ecdsa_asn1.cc",       "external/boringssl/src/crypto/engine/engine.cc",                   "external/boringssl/src/crypto/err/err.cc",                 "external/boringssl/src/crypto/evp/evp.cc",
            "external/boringssl/src/crypto/evp/evp_asn1.cc",                 "external/boringssl/src/crypto/evp/evp_ctx.cc",                     "external/boringssl/src/crypto/evp/p_dh.cc",                "external/boringssl/src/crypto/evp/p_dh_asn1.cc",
            "external/boringssl/src/crypto/evp/p_dsa_asn1.cc",               "external/boringssl/src/crypto/evp/p_ec.cc",                        "external/boringssl/src/crypto/evp/p_ec_asn1.cc",           "external/boringssl/src/crypto/evp/p_ed25519.cc",
            "external/boringssl/src/crypto/evp/p_ed25519_asn1.cc",           "external/boringssl/src/crypto/evp/p_hkdf.cc",                      "external/boringssl/src/crypto/evp/p_rsa.cc",               "external/boringssl/src/crypto/evp/p_rsa_asn1.cc",
            "external/boringssl/src/crypto/evp/p_x25519.cc",                 "external/boringssl/src/crypto/evp/p_x25519_asn1.cc",               "external/boringssl/src/crypto/evp/pbkdf.cc",               "external/boringssl/src/crypto/evp/print.cc",
            "external/boringssl/src/crypto/evp/scrypt.cc",                   "external/boringssl/src/crypto/evp/sign.cc",                        "external/boringssl/src/crypto/ex_data.cc",                 "external/boringssl/src/crypto/fipsmodule/fips_shared_support.cc",
            "external/boringssl/src/crypto/hpke/hpke.cc",                    "external/boringssl/src/crypto/hrss/hrss.cc",                       "external/boringssl/src/crypto/kyber/kyber.cc",             "external/boringssl/src/crypto/lhash/lhash.cc",
            "external/boringssl/src/crypto/md4/md4.cc",                      "external/boringssl/src/crypto/md5/md5.cc",                         "external/boringssl/src/crypto/mem.cc",                     "external/boringssl/src/crypto/mldsa/mldsa.cc",
            "external/boringssl/src/crypto/mlkem/mlkem.cc",                  "external/boringssl/src/crypto/obj/obj.cc",                         "external/boringssl/src/crypto/obj/obj_xref.cc",            "external/boringssl/src/crypto/pem/pem_all.cc",
            "external/boringssl/src/crypto/pem/pem_info.cc",                 "external/boringssl/src/crypto/pem/pem_lib.cc",                     "external/boringssl/src/crypto/pem/pem_oth.cc",             "external/boringssl/src/crypto/pem/pem_pk8.cc",
            "external/boringssl/src/crypto/pem/pem_pkey.cc",                 "external/boringssl/src/crypto/pem/pem_x509.cc",                    "external/boringssl/src/crypto/pem/pem_xaux.cc",            "external/boringssl/src/crypto/pkcs7/pkcs7.cc",
            "external/boringssl/src/crypto/pkcs7/pkcs7_x509.cc",             "external/boringssl/src/crypto/pkcs8/p5_pbev2.cc",                  "external/boringssl/src/crypto/pkcs8/pkcs8.cc",             "external/boringssl/src/crypto/pkcs8/pkcs8_x509.cc",
            "external/boringssl/src/crypto/poly1305/poly1305.cc",            "external/boringssl/src/crypto/poly1305/poly1305_arm.cc",           "external/boringssl/src/crypto/poly1305/poly1305_vec.cc",   "external/boringssl/src/crypto/pool/pool.cc",
            "external/boringssl/src/crypto/rand_extra/deterministic.cc",     "external/boringssl/src/crypto/rand_extra/fork_detect.cc",          "external/boringssl/src/crypto/rand_extra/forkunsafe.cc",   "external/boringssl/src/crypto/rand_extra/getentropy.cc",
            "external/boringssl/src/crypto/rand_extra/ios.cc",               "external/boringssl/src/crypto/rand_extra/passive.cc",              "external/boringssl/src/crypto/rand_extra/rand_extra.cc",   "external/boringssl/src/crypto/rand_extra/trusty.cc",
            "external/boringssl/src/crypto/rand_extra/urandom.cc",           "external/boringssl/src/crypto/rand_extra/windows.cc",              "external/boringssl/src/crypto/rc4/rc4.cc",                 "external/boringssl/src/crypto/refcount.cc",
            "external/boringssl/src/crypto/rsa_extra/rsa_asn1.cc",           "external/boringssl/src/crypto/rsa_extra/rsa_crypt.cc",             "external/boringssl/src/crypto/rsa_extra/rsa_extra.cc",     "external/boringssl/src/crypto/rsa_extra/rsa_print.cc",
            "external/boringssl/src/crypto/sha/sha1.cc",                     "external/boringssl/src/crypto/sha/sha256.cc",                      "external/boringssl/src/crypto/sha/sha512.cc",              "external/boringssl/src/crypto/siphash/siphash.cc",
            "external/boringssl/src/crypto/slhdsa/slhdsa.cc",                "external/boringssl/src/crypto/spake2plus/spake2plus.cc",           "external/boringssl/src/crypto/stack/stack.cc",             "external/boringssl/src/crypto/thread.cc",
            "external/boringssl/src/crypto/thread_none.cc",                  "external/boringssl/src/crypto/thread_pthread.cc",                  "external/boringssl/src/crypto/thread_win.cc",              "external/boringssl/src/crypto/trust_token/pmbtoken.cc",
            "external/boringssl/src/crypto/trust_token/trust_token.cc",      "external/boringssl/src/crypto/trust_token/voprf.cc",               "external/boringssl/src/crypto/x509/a_digest.cc",           "external/boringssl/src/crypto/x509/a_sign.cc",
            "external/boringssl/src/crypto/x509/a_verify.cc",                "external/boringssl/src/crypto/x509/algorithm.cc",                  "external/boringssl/src/crypto/x509/asn1_gen.cc",           "external/boringssl/src/crypto/x509/by_dir.cc",
            "external/boringssl/src/crypto/x509/by_file.cc",                 "external/boringssl/src/crypto/x509/i2d_pr.cc",                     "external/boringssl/src/crypto/x509/name_print.cc",         "external/boringssl/src/crypto/x509/policy.cc",
            "external/boringssl/src/crypto/x509/rsa_pss.cc",                 "external/boringssl/src/crypto/x509/t_crl.cc",                      "external/boringssl/src/crypto/x509/t_req.cc",              "external/boringssl/src/crypto/x509/t_x509.cc",
            "external/boringssl/src/crypto/x509/t_x509a.cc",                 "external/boringssl/src/crypto/x509/v3_akey.cc",                    "external/boringssl/src/crypto/x509/v3_akeya.cc",           "external/boringssl/src/crypto/x509/v3_alt.cc",
            "external/boringssl/src/crypto/x509/v3_bcons.cc",                "external/boringssl/src/crypto/x509/v3_bitst.cc",                   "external/boringssl/src/crypto/x509/v3_conf.cc",            "external/boringssl/src/crypto/x509/v3_cpols.cc",
            "external/boringssl/src/crypto/x509/v3_crld.cc",                 "external/boringssl/src/crypto/x509/v3_enum.cc",                    "external/boringssl/src/crypto/x509/v3_extku.cc",           "external/boringssl/src/crypto/x509/v3_genn.cc",
            "external/boringssl/src/crypto/x509/v3_ia5.cc",                  "external/boringssl/src/crypto/x509/v3_info.cc",                    "external/boringssl/src/crypto/x509/v3_int.cc",             "external/boringssl/src/crypto/x509/v3_lib.cc",
            "external/boringssl/src/crypto/x509/v3_ncons.cc",                "external/boringssl/src/crypto/x509/v3_ocsp.cc",                    "external/boringssl/src/crypto/x509/v3_pcons.cc",           "external/boringssl/src/crypto/x509/v3_pmaps.cc",
            "external/boringssl/src/crypto/x509/v3_prn.cc",                  "external/boringssl/src/crypto/x509/v3_purp.cc",                    "external/boringssl/src/crypto/x509/v3_skey.cc",            "external/boringssl/src/crypto/x509/v3_utl.cc",
            "external/boringssl/src/crypto/x509/x509.cc",                    "external/boringssl/src/crypto/x509/x509_att.cc",                   "external/boringssl/src/crypto/x509/x509_cmp.cc",           "external/boringssl/src/crypto/x509/x509_d2.cc",
            "external/boringssl/src/crypto/x509/x509_def.cc",                "external/boringssl/src/crypto/x509/x509_ext.cc",                   "external/boringssl/src/crypto/x509/x509_lu.cc",            "external/boringssl/src/crypto/x509/x509_obj.cc",
            "external/boringssl/src/crypto/x509/x509_req.cc",                "external/boringssl/src/crypto/x509/x509_set.cc",                   "external/boringssl/src/crypto/x509/x509_trs.cc",           "external/boringssl/src/crypto/x509/x509_txt.cc",
            "external/boringssl/src/crypto/x509/x509_v3.cc",                 "external/boringssl/src/crypto/x509/x509_vfy.cc",                   "external/boringssl/src/crypto/x509/x509_vpm.cc",           "external/boringssl/src/crypto/x509/x509cset.cc",
            "external/boringssl/src/crypto/x509/x509name.cc",                "external/boringssl/src/crypto/x509/x509rset.cc",                   "external/boringssl/src/crypto/x509/x509spki.cc",           "external/boringssl/src/crypto/x509/x_algor.cc",
            "external/boringssl/src/crypto/x509/x_all.cc",                   "external/boringssl/src/crypto/x509/x_attrib.cc",                   "external/boringssl/src/crypto/x509/x_crl.cc",              "external/boringssl/src/crypto/x509/x_exten.cc",
            "external/boringssl/src/crypto/x509/x_name.cc",                  "external/boringssl/src/crypto/x509/x_pubkey.cc",                   "external/boringssl/src/crypto/x509/x_req.cc",              "external/boringssl/src/crypto/x509/x_sig.cc",
            "external/boringssl/src/crypto/x509/x_spki.cc",                  "external/boringssl/src/crypto/x509/x_val.cc",                      "external/boringssl/src/crypto/x509/x_x509.cc",             "external/boringssl/src/crypto/x509/x_x509a.cc",
            "external/boringssl/src/gen/crypto/err_data.cc",                 "external/boringssl/src/crypto/fipsmodule/bcm.cc",
        },
        .flags = flags(b, o, &cf_21, I.inc_26),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &linux_x86_64_crypto_asm,
        .flags = flags(b, o, &cf_22, I.inc_26),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/dex2oat/aot_class_linker.cc",                     "art/dex2oat/dex/quick_compiler_callbacks.cc",          "art/dex2oat/dex/verification_results.cc",             "art/dex2oat/driver/compiled_method.cc",
            "art/dex2oat/driver/compiled_method_storage.cc",       "art/dex2oat/driver/compiler_driver.cc",                "art/dex2oat/interpreter/interpreter_switch_impl1.cc", "art/dex2oat/linker/code_info_table_deduper.cc",
            "art/dex2oat/linker/elf_writer_quick.cc",              "art/dex2oat/linker/image_writer.cc",                   "art/dex2oat/linker/multi_oat_relative_patcher.cc",    "art/dex2oat/linker/oat_writer.cc",
            "art/dex2oat/linker/relative_patcher.cc",              "art/dex2oat/sdk_checker.cc",                           "art/dex2oat/transaction.cc",                          "art/dex2oat/utils/swap_space.cc",
            "art/dex2oat/linker/x86/relative_patcher_x86_base.cc", "art/dex2oat/linker/x86_64/relative_patcher_x86_64.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_27),
    });
    addGeneratedSources(m, o.generated.dex2oat_operators, flags(b, o, &cf_0, I.inc_27));
}
fn srcs_libnativehelper_compat_libc___static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libnativehelper/ExpandableString.c", "libnativehelper/JNIHelp.c",
        },
        .flags = flags(b, o, &cf_23, I.inc_28),
    });
}
fn srcs_libcrypto_static_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/boringssl/src/crypto/asn1/a_bitstr.cc",                "external/boringssl/src/crypto/asn1/a_bool.cc",                     "external/boringssl/src/crypto/asn1/a_d2i_fp.cc",           "external/boringssl/src/crypto/asn1/a_dup.cc",
            "external/boringssl/src/crypto/asn1/a_gentm.cc",                 "external/boringssl/src/crypto/asn1/a_i2d_fp.cc",                   "external/boringssl/src/crypto/asn1/a_int.cc",              "external/boringssl/src/crypto/asn1/a_mbstr.cc",
            "external/boringssl/src/crypto/asn1/a_object.cc",                "external/boringssl/src/crypto/asn1/a_octet.cc",                    "external/boringssl/src/crypto/asn1/a_strex.cc",            "external/boringssl/src/crypto/asn1/a_strnid.cc",
            "external/boringssl/src/crypto/asn1/a_time.cc",                  "external/boringssl/src/crypto/asn1/a_type.cc",                     "external/boringssl/src/crypto/asn1/a_utctm.cc",            "external/boringssl/src/crypto/asn1/asn1_lib.cc",
            "external/boringssl/src/crypto/asn1/asn1_par.cc",                "external/boringssl/src/crypto/asn1/asn_pack.cc",                   "external/boringssl/src/crypto/asn1/f_int.cc",              "external/boringssl/src/crypto/asn1/f_string.cc",
            "external/boringssl/src/crypto/asn1/posix_time.cc",              "external/boringssl/src/crypto/asn1/tasn_dec.cc",                   "external/boringssl/src/crypto/asn1/tasn_enc.cc",           "external/boringssl/src/crypto/asn1/tasn_fre.cc",
            "external/boringssl/src/crypto/asn1/tasn_new.cc",                "external/boringssl/src/crypto/asn1/tasn_typ.cc",                   "external/boringssl/src/crypto/asn1/tasn_utl.cc",           "external/boringssl/src/crypto/base64/base64.cc",
            "external/boringssl/src/crypto/bio/bio.cc",                      "external/boringssl/src/crypto/bio/bio_mem.cc",                     "external/boringssl/src/crypto/bio/connect.cc",             "external/boringssl/src/crypto/bio/errno.cc",
            "external/boringssl/src/crypto/bio/fd.cc",                       "external/boringssl/src/crypto/bio/file.cc",                        "external/boringssl/src/crypto/bio/hexdump.cc",             "external/boringssl/src/crypto/bio/pair.cc",
            "external/boringssl/src/crypto/bio/printf.cc",                   "external/boringssl/src/crypto/bio/socket.cc",                      "external/boringssl/src/crypto/bio/socket_helper.cc",       "external/boringssl/src/crypto/blake2/blake2.cc",
            "external/boringssl/src/crypto/bn_extra/bn_asn1.cc",             "external/boringssl/src/crypto/bn_extra/convert.cc",                "external/boringssl/src/crypto/buf/buf.cc",                 "external/boringssl/src/crypto/bytestring/asn1_compat.cc",
            "external/boringssl/src/crypto/bytestring/ber.cc",               "external/boringssl/src/crypto/bytestring/cbb.cc",                  "external/boringssl/src/crypto/bytestring/cbs.cc",          "external/boringssl/src/crypto/bytestring/unicode.cc",
            "external/boringssl/src/crypto/chacha/chacha.cc",                "external/boringssl/src/crypto/cipher_extra/cipher_extra.cc",       "external/boringssl/src/crypto/cipher_extra/derive_key.cc", "external/boringssl/src/crypto/cipher_extra/e_aesctrhmac.cc",
            "external/boringssl/src/crypto/cipher_extra/e_aesgcmsiv.cc",     "external/boringssl/src/crypto/cipher_extra/e_chacha20poly1305.cc", "external/boringssl/src/crypto/cipher_extra/e_des.cc",      "external/boringssl/src/crypto/cipher_extra/e_null.cc",
            "external/boringssl/src/crypto/cipher_extra/e_rc2.cc",           "external/boringssl/src/crypto/cipher_extra/e_rc4.cc",              "external/boringssl/src/crypto/cipher_extra/e_tls.cc",      "external/boringssl/src/crypto/cipher_extra/tls_cbc.cc",
            "external/boringssl/src/crypto/conf/conf.cc",                    "external/boringssl/src/crypto/cpu_aarch64_apple.cc",               "external/boringssl/src/crypto/cpu_aarch64_fuchsia.cc",     "external/boringssl/src/crypto/cpu_aarch64_linux.cc",
            "external/boringssl/src/crypto/cpu_aarch64_openbsd.cc",          "external/boringssl/src/crypto/cpu_aarch64_sysreg.cc",              "external/boringssl/src/crypto/cpu_aarch64_win.cc",         "external/boringssl/src/crypto/cpu_arm_freebsd.cc",
            "external/boringssl/src/crypto/cpu_arm_linux.cc",                "external/boringssl/src/crypto/cpu_intel.cc",                       "external/boringssl/src/crypto/crypto.cc",                  "external/boringssl/src/crypto/curve25519/curve25519.cc",
            "external/boringssl/src/crypto/curve25519/curve25519_64_adx.cc", "external/boringssl/src/crypto/curve25519/spake25519.cc",           "external/boringssl/src/crypto/des/des.cc",                 "external/boringssl/src/crypto/dh_extra/dh_asn1.cc",
            "external/boringssl/src/crypto/dh_extra/params.cc",              "external/boringssl/src/crypto/digest_extra/digest_extra.cc",       "external/boringssl/src/crypto/dsa/dsa.cc",                 "external/boringssl/src/crypto/dsa/dsa_asn1.cc",
            "external/boringssl/src/crypto/ec_extra/ec_asn1.cc",             "external/boringssl/src/crypto/ec_extra/ec_derive.cc",              "external/boringssl/src/crypto/ec_extra/hash_to_curve.cc",  "external/boringssl/src/crypto/ecdh_extra/ecdh_extra.cc",
            "external/boringssl/src/crypto/ecdsa_extra/ecdsa_asn1.cc",       "external/boringssl/src/crypto/engine/engine.cc",                   "external/boringssl/src/crypto/err/err.cc",                 "external/boringssl/src/crypto/evp/evp.cc",
            "external/boringssl/src/crypto/evp/evp_asn1.cc",                 "external/boringssl/src/crypto/evp/evp_ctx.cc",                     "external/boringssl/src/crypto/evp/p_dh.cc",                "external/boringssl/src/crypto/evp/p_dh_asn1.cc",
            "external/boringssl/src/crypto/evp/p_dsa_asn1.cc",               "external/boringssl/src/crypto/evp/p_ec.cc",                        "external/boringssl/src/crypto/evp/p_ec_asn1.cc",           "external/boringssl/src/crypto/evp/p_ed25519.cc",
            "external/boringssl/src/crypto/evp/p_ed25519_asn1.cc",           "external/boringssl/src/crypto/evp/p_hkdf.cc",                      "external/boringssl/src/crypto/evp/p_rsa.cc",               "external/boringssl/src/crypto/evp/p_rsa_asn1.cc",
            "external/boringssl/src/crypto/evp/p_x25519.cc",                 "external/boringssl/src/crypto/evp/p_x25519_asn1.cc",               "external/boringssl/src/crypto/evp/pbkdf.cc",               "external/boringssl/src/crypto/evp/print.cc",
            "external/boringssl/src/crypto/evp/scrypt.cc",                   "external/boringssl/src/crypto/evp/sign.cc",                        "external/boringssl/src/crypto/ex_data.cc",                 "external/boringssl/src/crypto/fipsmodule/fips_shared_support.cc",
            "external/boringssl/src/crypto/hpke/hpke.cc",                    "external/boringssl/src/crypto/hrss/hrss.cc",                       "external/boringssl/src/crypto/kyber/kyber.cc",             "external/boringssl/src/crypto/lhash/lhash.cc",
            "external/boringssl/src/crypto/md4/md4.cc",                      "external/boringssl/src/crypto/md5/md5.cc",                         "external/boringssl/src/crypto/mem.cc",                     "external/boringssl/src/crypto/mldsa/mldsa.cc",
            "external/boringssl/src/crypto/mlkem/mlkem.cc",                  "external/boringssl/src/crypto/obj/obj.cc",                         "external/boringssl/src/crypto/obj/obj_xref.cc",            "external/boringssl/src/crypto/pem/pem_all.cc",
            "external/boringssl/src/crypto/pem/pem_info.cc",                 "external/boringssl/src/crypto/pem/pem_lib.cc",                     "external/boringssl/src/crypto/pem/pem_oth.cc",             "external/boringssl/src/crypto/pem/pem_pk8.cc",
            "external/boringssl/src/crypto/pem/pem_pkey.cc",                 "external/boringssl/src/crypto/pem/pem_x509.cc",                    "external/boringssl/src/crypto/pem/pem_xaux.cc",            "external/boringssl/src/crypto/pkcs7/pkcs7.cc",
            "external/boringssl/src/crypto/pkcs7/pkcs7_x509.cc",             "external/boringssl/src/crypto/pkcs8/p5_pbev2.cc",                  "external/boringssl/src/crypto/pkcs8/pkcs8.cc",             "external/boringssl/src/crypto/pkcs8/pkcs8_x509.cc",
            "external/boringssl/src/crypto/poly1305/poly1305.cc",            "external/boringssl/src/crypto/poly1305/poly1305_arm.cc",           "external/boringssl/src/crypto/poly1305/poly1305_vec.cc",   "external/boringssl/src/crypto/pool/pool.cc",
            "external/boringssl/src/crypto/rand_extra/deterministic.cc",     "external/boringssl/src/crypto/rand_extra/fork_detect.cc",          "external/boringssl/src/crypto/rand_extra/forkunsafe.cc",   "external/boringssl/src/crypto/rand_extra/getentropy.cc",
            "external/boringssl/src/crypto/rand_extra/ios.cc",               "external/boringssl/src/crypto/rand_extra/passive.cc",              "external/boringssl/src/crypto/rand_extra/rand_extra.cc",   "external/boringssl/src/crypto/rand_extra/trusty.cc",
            "external/boringssl/src/crypto/rand_extra/urandom.cc",           "external/boringssl/src/crypto/rand_extra/windows.cc",              "external/boringssl/src/crypto/rc4/rc4.cc",                 "external/boringssl/src/crypto/refcount.cc",
            "external/boringssl/src/crypto/rsa_extra/rsa_asn1.cc",           "external/boringssl/src/crypto/rsa_extra/rsa_crypt.cc",             "external/boringssl/src/crypto/rsa_extra/rsa_extra.cc",     "external/boringssl/src/crypto/rsa_extra/rsa_print.cc",
            "external/boringssl/src/crypto/sha/sha1.cc",                     "external/boringssl/src/crypto/sha/sha256.cc",                      "external/boringssl/src/crypto/sha/sha512.cc",              "external/boringssl/src/crypto/siphash/siphash.cc",
            "external/boringssl/src/crypto/slhdsa/slhdsa.cc",                "external/boringssl/src/crypto/spake2plus/spake2plus.cc",           "external/boringssl/src/crypto/stack/stack.cc",             "external/boringssl/src/crypto/thread.cc",
            "external/boringssl/src/crypto/thread_none.cc",                  "external/boringssl/src/crypto/thread_pthread.cc",                  "external/boringssl/src/crypto/thread_win.cc",              "external/boringssl/src/crypto/trust_token/pmbtoken.cc",
            "external/boringssl/src/crypto/trust_token/trust_token.cc",      "external/boringssl/src/crypto/trust_token/voprf.cc",               "external/boringssl/src/crypto/x509/a_digest.cc",           "external/boringssl/src/crypto/x509/a_sign.cc",
            "external/boringssl/src/crypto/x509/a_verify.cc",                "external/boringssl/src/crypto/x509/algorithm.cc",                  "external/boringssl/src/crypto/x509/asn1_gen.cc",           "external/boringssl/src/crypto/x509/by_dir.cc",
            "external/boringssl/src/crypto/x509/by_file.cc",                 "external/boringssl/src/crypto/x509/i2d_pr.cc",                     "external/boringssl/src/crypto/x509/name_print.cc",         "external/boringssl/src/crypto/x509/policy.cc",
            "external/boringssl/src/crypto/x509/rsa_pss.cc",                 "external/boringssl/src/crypto/x509/t_crl.cc",                      "external/boringssl/src/crypto/x509/t_req.cc",              "external/boringssl/src/crypto/x509/t_x509.cc",
            "external/boringssl/src/crypto/x509/t_x509a.cc",                 "external/boringssl/src/crypto/x509/v3_akey.cc",                    "external/boringssl/src/crypto/x509/v3_akeya.cc",           "external/boringssl/src/crypto/x509/v3_alt.cc",
            "external/boringssl/src/crypto/x509/v3_bcons.cc",                "external/boringssl/src/crypto/x509/v3_bitst.cc",                   "external/boringssl/src/crypto/x509/v3_conf.cc",            "external/boringssl/src/crypto/x509/v3_cpols.cc",
            "external/boringssl/src/crypto/x509/v3_crld.cc",                 "external/boringssl/src/crypto/x509/v3_enum.cc",                    "external/boringssl/src/crypto/x509/v3_extku.cc",           "external/boringssl/src/crypto/x509/v3_genn.cc",
            "external/boringssl/src/crypto/x509/v3_ia5.cc",                  "external/boringssl/src/crypto/x509/v3_info.cc",                    "external/boringssl/src/crypto/x509/v3_int.cc",             "external/boringssl/src/crypto/x509/v3_lib.cc",
            "external/boringssl/src/crypto/x509/v3_ncons.cc",                "external/boringssl/src/crypto/x509/v3_ocsp.cc",                    "external/boringssl/src/crypto/x509/v3_pcons.cc",           "external/boringssl/src/crypto/x509/v3_pmaps.cc",
            "external/boringssl/src/crypto/x509/v3_prn.cc",                  "external/boringssl/src/crypto/x509/v3_purp.cc",                    "external/boringssl/src/crypto/x509/v3_skey.cc",            "external/boringssl/src/crypto/x509/v3_utl.cc",
            "external/boringssl/src/crypto/x509/x509.cc",                    "external/boringssl/src/crypto/x509/x509_att.cc",                   "external/boringssl/src/crypto/x509/x509_cmp.cc",           "external/boringssl/src/crypto/x509/x509_d2.cc",
            "external/boringssl/src/crypto/x509/x509_def.cc",                "external/boringssl/src/crypto/x509/x509_ext.cc",                   "external/boringssl/src/crypto/x509/x509_lu.cc",            "external/boringssl/src/crypto/x509/x509_obj.cc",
            "external/boringssl/src/crypto/x509/x509_req.cc",                "external/boringssl/src/crypto/x509/x509_set.cc",                   "external/boringssl/src/crypto/x509/x509_trs.cc",           "external/boringssl/src/crypto/x509/x509_txt.cc",
            "external/boringssl/src/crypto/x509/x509_v3.cc",                 "external/boringssl/src/crypto/x509/x509_vfy.cc",                   "external/boringssl/src/crypto/x509/x509_vpm.cc",           "external/boringssl/src/crypto/x509/x509cset.cc",
            "external/boringssl/src/crypto/x509/x509name.cc",                "external/boringssl/src/crypto/x509/x509rset.cc",                   "external/boringssl/src/crypto/x509/x509spki.cc",           "external/boringssl/src/crypto/x509/x_algor.cc",
            "external/boringssl/src/crypto/x509/x_all.cc",                   "external/boringssl/src/crypto/x509/x_attrib.cc",                   "external/boringssl/src/crypto/x509/x_crl.cc",              "external/boringssl/src/crypto/x509/x_exten.cc",
            "external/boringssl/src/crypto/x509/x_name.cc",                  "external/boringssl/src/crypto/x509/x_pubkey.cc",                   "external/boringssl/src/crypto/x509/x_req.cc",              "external/boringssl/src/crypto/x509/x_sig.cc",
            "external/boringssl/src/crypto/x509/x_spki.cc",                  "external/boringssl/src/crypto/x509/x_val.cc",                      "external/boringssl/src/crypto/x509/x_x509.cc",             "external/boringssl/src/crypto/x509/x_x509a.cc",
            "external/boringssl/src/gen/crypto/err_data.cc",                 "external/boringssl/src/crypto/fipsmodule/bcm.cc",
        },
        .flags = flags(b, o, &cf_21, I.inc_26),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &linux_x86_64_crypto_asm,
        .flags = flags(b, o, &cf_22, I.inc_26),
    });
}
fn srcs_libfdlibm_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/fdlibm/k_standard.c",    "external/fdlibm/k_rem_pio2.c",  "external/fdlibm/k_cos.c",     "external/fdlibm/k_sin.c",
            "external/fdlibm/k_tan.c",         "external/fdlibm/e_acos.c",      "external/fdlibm/e_acosh.c",   "external/fdlibm/e_asin.c",
            "external/fdlibm/e_atan2.c",       "external/fdlibm/e_atanh.c",     "external/fdlibm/e_cosh.c",    "external/fdlibm/e_exp.c",
            "external/fdlibm/e_fmod.c",        "external/fdlibm/e_gamma.c",     "external/fdlibm/e_gamma_r.c", "external/fdlibm/e_hypot.c",
            "external/fdlibm/e_j0.c",          "external/fdlibm/e_j1.c",        "external/fdlibm/e_jn.c",      "external/fdlibm/e_lgamma.c",
            "external/fdlibm/e_lgamma_r.c",    "external/fdlibm/e_log.c",       "external/fdlibm/e_log10.c",   "external/fdlibm/e_pow.c",
            "external/fdlibm/e_rem_pio2.c",    "external/fdlibm/e_remainder.c", "external/fdlibm/e_scalb.c",   "external/fdlibm/e_sinh.c",
            "external/fdlibm/e_sqrt.c",        "external/fdlibm/w_acos.c",      "external/fdlibm/w_acosh.c",   "external/fdlibm/w_asin.c",
            "external/fdlibm/w_atan2.c",       "external/fdlibm/w_atanh.c",     "external/fdlibm/w_cosh.c",    "external/fdlibm/w_exp.c",
            "external/fdlibm/w_fmod.c",        "external/fdlibm/w_gamma.c",     "external/fdlibm/w_gamma_r.c", "external/fdlibm/w_hypot.c",
            "external/fdlibm/w_j0.c",          "external/fdlibm/w_j1.c",        "external/fdlibm/w_jn.c",      "external/fdlibm/w_lgamma.c",
            "external/fdlibm/w_lgamma_r.c",    "external/fdlibm/w_log.c",       "external/fdlibm/w_log10.c",   "external/fdlibm/w_pow.c",
            "external/fdlibm/w_remainder.c",   "external/fdlibm/w_scalb.c",     "external/fdlibm/w_sinh.c",    "external/fdlibm/w_sqrt.c",
            "external/fdlibm/s_asinh.c",       "external/fdlibm/s_atan.c",      "external/fdlibm/s_cbrt.c",    "external/fdlibm/s_ceil.c",
            "external/fdlibm/s_copysign.c",    "external/fdlibm/s_cos.c",       "external/fdlibm/s_erf.c",     "external/fdlibm/s_expm1.c",
            "external/fdlibm/s_fabs.c",        "external/fdlibm/s_finite.c",    "external/fdlibm/s_floor.c",   "external/fdlibm/s_frexp.c",
            "external/fdlibm/s_ilogb.c",       "external/fdlibm/s_isnan.c",     "external/fdlibm/s_ldexp.c",   "external/fdlibm/s_lib_version.c",
            "external/fdlibm/s_log1p.c",       "external/fdlibm/s_logb.c",      "external/fdlibm/s_matherr.c", "external/fdlibm/s_modf.c",
            "external/fdlibm/s_nextafter.c",   "external/fdlibm/s_rint.c",      "external/fdlibm/s_scalbn.c",  "external/fdlibm/s_signgam.c",
            "external/fdlibm/s_significand.c", "external/fdlibm/s_sin.c",       "external/fdlibm/s_tan.c",     "external/fdlibm/s_tanh.c",
        },
        .flags = flags(b, o, &cf_24, I.inc_29),
    });
}
fn srcs_libart_compiler_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/compiler/debug/elf_debug_writer.cc",                "art/compiler/dex/inline_method_analyser.cc",                   "art/compiler/driver/compiler_options.cc",                             "art/compiler/driver/dex_compilation_unit.cc",
            "art/compiler/jit/jit_compiler.cc",                      "art/compiler/jit/jit_logger.cc",                               "art/compiler/jni/quick/calling_convention.cc",                        "art/compiler/jni/quick/jni_compiler.cc",
            "art/compiler/optimizing/block_builder.cc",              "art/compiler/optimizing/block_namer.cc",                       "art/compiler/optimizing/bounds_check_elimination.cc",                 "art/compiler/optimizing/builder.cc",
            "art/compiler/optimizing/cha_guard_optimization.cc",     "art/compiler/optimizing/code_generation_data.cc",              "art/compiler/optimizing/code_generator.cc",                           "art/compiler/optimizing/code_generator_utils.cc",
            "art/compiler/optimizing/code_sinking.cc",               "art/compiler/optimizing/constant_folding.cc",                  "art/compiler/optimizing/constructor_fence_redundancy_elimination.cc", "art/compiler/optimizing/control_flow_simplifier.cc",
            "art/compiler/optimizing/data_type.cc",                  "art/compiler/optimizing/dead_code_elimination.cc",             "art/compiler/optimizing/escape.cc",                                   "art/compiler/optimizing/graph_checker.cc",
            "art/compiler/optimizing/graph_visualizer.cc",           "art/compiler/optimizing/gvn.cc",                               "art/compiler/optimizing/handle_cache.cc",                             "art/compiler/optimizing/induction_var_analysis.cc",
            "art/compiler/optimizing/induction_var_range.cc",        "art/compiler/optimizing/inliner.cc",                           "art/compiler/optimizing/instruction_builder.cc",                      "art/compiler/optimizing/instruction_simplifier.cc",
            "art/compiler/optimizing/intrinsic_objects.cc",          "art/compiler/optimizing/intrinsics.cc",                        "art/compiler/optimizing/licm.cc",                                     "art/compiler/optimizing/linear_order.cc",
            "art/compiler/optimizing/load_store_analysis.cc",        "art/compiler/optimizing/load_store_elimination.cc",            "art/compiler/optimizing/locations.cc",                                "art/compiler/optimizing/loop_analysis.cc",
            "art/compiler/optimizing/loop_optimization.cc",          "art/compiler/optimizing/nodes.cc",                             "art/compiler/optimizing/nodes_vector.cc",                             "art/compiler/optimizing/optimizing_compiler.cc",
            "art/compiler/optimizing/parallel_move_resolver.cc",     "art/compiler/optimizing/prepare_for_register_allocation.cc",   "art/compiler/optimizing/profiling_info_builder.cc",                   "art/compiler/optimizing/reference_type_info.cc",
            "art/compiler/optimizing/reference_type_propagation.cc", "art/compiler/optimizing/register_allocation_resolver.cc",      "art/compiler/optimizing/register_allocator.cc",                       "art/compiler/optimizing/register_allocator_linear_scan.cc",
            "art/compiler/optimizing/scheduler.cc",                  "art/compiler/optimizing/sharpening.cc",                        "art/compiler/optimizing/side_effects_analysis.cc",                    "art/compiler/optimizing/ssa_builder.cc",
            "art/compiler/optimizing/ssa_liveness_analysis.cc",      "art/compiler/optimizing/ssa_phi_elimination.cc",               "art/compiler/optimizing/stack_map_stream.cc",                         "art/compiler/optimizing/superblock_cloner.cc",
            "art/compiler/optimizing/write_barrier_elimination.cc",  "art/compiler/trampolines/trampoline_compiler.cc",              "art/compiler/utils/assembler.cc",                                     "art/compiler/utils/jni_macro_assembler.cc",
            "art/compiler/compiler.cc",                              "art/compiler/optimizing/instruction_simplifier_x86_shared.cc", "art/compiler/optimizing/x86_memory_gen.cc",                           "art/compiler/jni/quick/x86_64/calling_convention_x86_64.cc",
            "art/compiler/optimizing/intrinsics_x86_64.cc",          "art/compiler/optimizing/instruction_simplifier_x86_64.cc",     "art/compiler/optimizing/code_generator_x86_64.cc",                    "art/compiler/optimizing/code_generator_vector_x86_64.cc",
            "art/compiler/utils/x86_64/assembler_x86_64.cc",         "art/compiler/utils/x86_64/jni_macro_assembler_x86_64.cc",      "art/compiler/utils/x86_64/managed_register_x86_64.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_18),
    });
    m.addCSourceFile(.{ .file = o.vendor.path(b, "art/compiler/optimizing/optimization.cc"), .flags = compilerOptimizationFlags(b, o, I) });
    addGeneratedSources(m, o.generated.compiler_operators, flags(b, o, &cf_0, I.inc_18));
}
fn srcs_libart_runtime_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/cpu_features/src/filesystem.c", "external/cpu_features/src/stack_line_reader.c", "external/cpu_features/src/string_view.c",
        },
        .flags = flags(b, o, &cf_14, I.inc_19),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/cpu_features/src/impl_x86_linux_or_android.c",
        },
        .flags = flags(b, o, &cf_15, I.inc_20),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/runtime/app_info.cc",                                                  "art/runtime/art_field.cc",                                    "art/runtime/art_method.cc",                                      "art/runtime/backtrace_helper.cc",
            "art/runtime/barrier.cc",                                                   "art/runtime/base/gc_visited_arena_pool.cc",                   "art/runtime/base/locks.cc",                                      "art/runtime/base/mem_map_arena_pool.cc",
            "art/runtime/base/mutex.cc",                                                "art/runtime/base/quasi_atomic.cc",                            "art/runtime/base/timing_logger.cc",                              "art/runtime/cha.cc",
            "art/runtime/class_linker.cc",                                              "art/runtime/class_loader_context.cc",                         "art/runtime/class_root.cc",                                      "art/runtime/class_table.cc",
            "art/runtime/common_throws.cc",                                             "art/runtime/compat_framework.cc",                             "art/runtime/debug_print.cc",                                     "art/runtime/debugger.cc",
            "art/runtime/dex/dex_file_annotations.cc",                                  "art/runtime/dex_register_location.cc",                        "art/runtime/exec_utils.cc",                                      "art/runtime/fault_handler.cc",
            "art/runtime/gc/accounting/bitmap.cc",                                      "art/runtime/gc/accounting/card_table.cc",                     "art/runtime/gc/accounting/heap_bitmap.cc",                       "art/runtime/gc/accounting/mod_union_table.cc",
            "art/runtime/gc/accounting/remembered_set.cc",                              "art/runtime/gc/accounting/space_bitmap.cc",                   "art/runtime/gc/allocation_record.cc",                            "art/runtime/gc/allocator/art-dlmalloc.cc",
            "art/runtime/gc/allocator/rosalloc.cc",                                     "art/runtime/gc/collector/concurrent_copying.cc",              "art/runtime/gc/collector/garbage_collector.cc",                  "art/runtime/gc/collector/immune_region.cc",
            "art/runtime/gc/collector/immune_spaces.cc",                                "art/runtime/gc/collector/mark_compact.cc",                    "art/runtime/gc/collector/mark_sweep.cc",                         "art/runtime/gc/collector/partial_mark_sweep.cc",
            "art/runtime/gc/collector/semi_space.cc",                                   "art/runtime/gc/collector/sticky_mark_sweep.cc",               "art/runtime/gc/gc_cause.cc",                                     "art/runtime/gc/heap.cc",
            "art/runtime/gc/reference_processor.cc",                                    "art/runtime/gc/reference_queue.cc",                           "art/runtime/gc/scoped_gc_critical_section.cc",                   "art/runtime/gc/space/bump_pointer_space.cc",
            "art/runtime/gc/space/dlmalloc_space.cc",                                   "art/runtime/gc/space/image_space.cc",                         "art/runtime/gc/space/large_object_space.cc",                     "art/runtime/gc/space/malloc_space.cc",
            "art/runtime/gc/space/region_space.cc",                                     "art/runtime/gc/space/rosalloc_space.cc",                      "art/runtime/gc/space/space.cc",                                  "art/runtime/gc/space/zygote_space.cc",
            "art/runtime/gc/task_processor.cc",                                         "art/runtime/gc/verification.cc",                              "art/runtime/handle.cc",                                          "art/runtime/hidden_api.cc",
            "art/runtime/hprof/hprof.cc",                                               "art/runtime/indirect_reference_table.cc",                     "art/runtime/instrumentation.cc",                                 "art/runtime/intern_table.cc",
            "art/runtime/interpreter/interpreter.cc",                                   "art/runtime/interpreter/interpreter_cache.cc",                "art/runtime/interpreter/interpreter_common.cc",                  "art/runtime/interpreter/interpreter_switch_impl0.cc",
            "art/runtime/interpreter/lock_count_data.cc",                               "art/runtime/interpreter/shadow_frame.cc",                     "art/runtime/interpreter/unstarted_runtime.cc",                   "art/runtime/java_frame_root_info.cc",
            "art/runtime/javaheapprof/javaheapsampler.cc",                              "art/runtime/jit/debugger_interface.cc",                       "art/runtime/jit/jit.cc",                                         "art/runtime/jit/jit_code_cache.cc",
            "art/runtime/jit/jit_memory_region.cc",                                     "art/runtime/jit/jit_options.cc",                              "art/runtime/jit/profile_saver.cc",                               "art/runtime/jit/profiling_info.cc",
            "art/runtime/jit/small_pattern_matcher.cc",                                 "art/runtime/jni/check_jni.cc",                                "art/runtime/jni/java_vm_ext.cc",                                 "art/runtime/jni/jni_env_ext.cc",
            "art/runtime/jni/jni_id_manager.cc",                                        "art/runtime/jni/jni_internal.cc",                             "art/runtime/jni/local_reference_table.cc",                       "art/runtime/method_handles.cc",
            "art/runtime/metrics/reporter.cc",                                          "art/runtime/mirror/array.cc",                                 "art/runtime/mirror/class.cc",                                    "art/runtime/mirror/class_ext.cc",
            "art/runtime/mirror/dex_cache.cc",                                          "art/runtime/mirror/emulated_stack_frame.cc",                  "art/runtime/mirror/executable.cc",                               "art/runtime/mirror/field.cc",
            "art/runtime/mirror/method.cc",                                             "art/runtime/mirror/method_handle_impl.cc",                    "art/runtime/mirror/method_handles_lookup.cc",                    "art/runtime/mirror/method_type.cc",
            "art/runtime/mirror/object.cc",                                             "art/runtime/mirror/stack_frame_info.cc",                      "art/runtime/mirror/stack_trace_element.cc",                      "art/runtime/mirror/string.cc",
            "art/runtime/mirror/throwable.cc",                                          "art/runtime/mirror/var_handle.cc",                            "art/runtime/monitor.cc",                                         "art/runtime/monitor_objects_stack_visitor.cc",
            "art/runtime/native/dalvik_system_BaseDexClassLoader.cc",                   "art/runtime/native/dalvik_system_DexFile.cc",                 "art/runtime/native/dalvik_system_VMDebug.cc",                    "art/runtime/native/dalvik_system_VMRuntime.cc",
            "art/runtime/native/dalvik_system_VMStack.cc",                              "art/runtime/native/dalvik_system_ZygoteHooks.cc",             "art/runtime/native/java_lang_Class.cc",                          "art/runtime/native/java_lang_Object.cc",
            "art/runtime/native/java_lang_StackStreamFactory.cc",                       "art/runtime/native/java_lang_String.cc",                      "art/runtime/native/java_lang_StringFactory.cc",                  "art/runtime/native/java_lang_System.cc",
            "art/runtime/native/java_lang_Thread.cc",                                   "art/runtime/native/java_lang_Throwable.cc",                   "art/runtime/native/java_lang_VMClassLoader.cc",                  "art/runtime/native/java_lang_invoke_MethodHandle.cc",
            "art/runtime/native/java_lang_invoke_MethodHandleImpl.cc",                  "art/runtime/native/java_lang_ref_FinalizerReference.cc",      "art/runtime/native/java_lang_ref_Reference.cc",                  "art/runtime/native/java_lang_reflect_Array.cc",
            "art/runtime/native/java_lang_reflect_Constructor.cc",                      "art/runtime/native/java_lang_reflect_Executable.cc",          "art/runtime/native/java_lang_reflect_Field.cc",                  "art/runtime/native/java_lang_reflect_Method.cc",
            "art/runtime/native/java_lang_reflect_Parameter.cc",                        "art/runtime/native/java_lang_reflect_Proxy.cc",               "art/runtime/native/java_util_concurrent_atomic_AtomicLong.cc",   "art/runtime/native/jdk_internal_misc_Unsafe.cc",
            "art/runtime/native/libcore_io_Memory.cc",                                  "art/runtime/native/libcore_util_CharsetUtils.cc",             "art/runtime/native/org_apache_harmony_dalvik_ddmc_DdmServer.cc", "art/runtime/native/org_apache_harmony_dalvik_ddmc_DdmVmInternal.cc",
            "art/runtime/native/sun_misc_Unsafe.cc",                                    "art/runtime/native_bridge_art_interface.cc",                  "art/runtime/native_stack_dump.cc",                               "art/runtime/non_debuggable_classes.cc",
            "art/runtime/nterp_helpers.cc",                                             "art/runtime/oat/elf_file.cc",                                 "art/runtime/oat/image.cc",                                       "art/runtime/oat/index_bss_mapping.cc",
            "art/runtime/oat/jni_stub_hash_map.cc",                                     "art/runtime/oat/oat.cc",                                      "art/runtime/oat/oat_file.cc",                                    "art/runtime/oat/oat_file_assistant.cc",
            "art/runtime/oat/oat_file_assistant_context.cc",                            "art/runtime/oat/oat_file_manager.cc",                         "art/runtime/oat/oat_quick_method_header.cc",                     "art/runtime/oat/sdc_file.cc",
            "art/runtime/oat/stack_map.cc",                                             "art/runtime/object_lock.cc",                                  "art/runtime/offsets.cc",                                         "art/runtime/parsed_options.cc",
            "art/runtime/plugin.cc",                                                    "art/runtime/quick_exception_handler.cc",                      "art/runtime/read_barrier.cc",                                    "art/runtime/reference_table.cc",
            "art/runtime/reflection.cc",                                                "art/runtime/reflective_handle_scope.cc",                      "art/runtime/reflective_value_visitor.cc",                        "art/runtime/runtime.cc",
            "art/runtime/runtime_callbacks.cc",                                         "art/runtime/runtime_common.cc",                               "art/runtime/runtime_image.cc",                                   "art/runtime/runtime_intrinsics.cc",
            "art/runtime/runtime_options.cc",                                           "art/runtime/scoped_thread_state_change.cc",                   "art/runtime/signal_catcher.cc",                                  "art/runtime/stack.cc",
            "art/runtime/startup_completed_task.cc",                                    "art/runtime/string_builder_append.cc",                        "art/runtime/thread.cc",                                          "art/runtime/thread_list.cc",
            "art/runtime/thread_pool.cc",                                               "art/runtime/ti/agent.cc",                                     "art/runtime/trace.cc",                                           "art/runtime/trace_profile.cc",
            "art/runtime/var_handles.cc",                                               "art/runtime/vdex_file.cc",                                    "art/runtime/verifier/class_verifier.cc",                         "art/runtime/verifier/instruction_flags.cc",
            "art/runtime/verifier/method_verifier.cc",                                  "art/runtime/verifier/reg_type.cc",                            "art/runtime/verifier/reg_type_cache.cc",                         "art/runtime/verifier/register_line.cc",
            "art/runtime/verifier/verifier_deps.cc",                                    "art/runtime/verify_object.cc",                                "art/runtime/well_known_classes.cc",                              "art/runtime/arch/context.cc",
            "art/runtime/arch/instruction_set_features.cc",                             "art/runtime/arch/memcmp16.cc",                                "art/runtime/arch/arm/instruction_set_features_arm.cc",           "art/runtime/arch/arm/registers_arm.cc",
            "art/runtime/arch/arm64/instruction_set_features_arm64.cc",                 "art/runtime/arch/arm64/registers_arm64.cc",                   "art/runtime/arch/riscv64/instruction_set_features_riscv64.cc",   "art/runtime/arch/riscv64/registers_riscv64.cc",
            "art/runtime/arch/x86/instruction_set_features_x86.cc",                     "art/runtime/arch/x86/registers_x86.cc",                       "art/runtime/arch/x86_64/registers_x86_64.cc",                    "art/runtime/entrypoints/entrypoint_utils.cc",
            "art/runtime/entrypoints/jni/jni_entrypoints.cc",                           "art/runtime/entrypoints/math_entrypoints.cc",                 "art/runtime/entrypoints/quick/quick_alloc_entrypoints.cc",       "art/runtime/entrypoints/quick/quick_cast_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_deoptimization_entrypoints.cc",        "art/runtime/entrypoints/quick/quick_dexcache_entrypoints.cc", "art/runtime/entrypoints/quick/quick_entrypoints_enum.cc",        "art/runtime/entrypoints/quick/quick_field_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_fillarray_entrypoints.cc",             "art/runtime/entrypoints/quick/quick_jni_entrypoints.cc",      "art/runtime/entrypoints/quick/quick_lock_entrypoints.cc",        "art/runtime/entrypoints/quick/quick_math_entrypoints.cc",
            "art/runtime/entrypoints/quick/quick_string_builder_append_entrypoints.cc", "art/runtime/entrypoints/quick/quick_thread_entrypoints.cc",   "art/runtime/entrypoints/quick/quick_throw_entrypoints.cc",       "art/runtime/entrypoints/quick/quick_trampoline_entrypoints.cc",
            "art/runtime/monitor_linux.cc",                                             "art/runtime/runtime_linux.cc",                                "art/runtime/thread_linux.cc",                                    "art/runtime/interpreter/mterp/nterp.cc",
            "art/runtime/arch/x86_64/thread_x86_64.cc",                                 "art/runtime/monitor_pool.cc",                                 "art/runtime/arch/x86/fault_handler_x86.cc",                      "art/runtime/arch/x86_64/context_x86_64.cc",
            "art/runtime/arch/x86_64/entrypoints_init_x86_64.cc",
        },
        .flags = flags(b, o, &cf_16, I.inc_21),
    });
    addGeneratedSources(m, o.generated.runtime_operators, flags(b, o, &cf_16, I.inc_21));
    m.addCSourceFile(.{ .file = o.generated.mterp, .flags = flags(b, o, &cf_17, I.inc_21) });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/runtime/arch/x86_64/memcmp16_x86_64.S", "art/runtime/arch/x86_64/native_entrypoints_x86_64.S", "art/runtime/arch/x86_64/jni_entrypoints_x86_64.S", "art/runtime/arch/x86_64/quick_entrypoints_x86_64.S",
        },
        .flags = flags(b, o, &cf_17, I.inc_21),
    });
}
fn srcs_libodrstatslog_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/odrefresh/odr_statslog_host.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_16),
    });
}
fn srcs_libelffile_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libelffile/elf/xz_utils.cc",                "art/libelffile/stream/buffered_output_stream.cc", "art/libelffile/stream/file_output_stream.cc", "art/libelffile/stream/output_stream.cc",
            "art/libelffile/stream/vector_output_stream.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_17),
    });
}
fn srcs_libzstd_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/zstd/lib/common/debug.c",                    "external/zstd/lib/common/entropy_common.c",            "external/zstd/lib/common/error_private.c",              "external/zstd/lib/common/fse_decompress.c",
            "external/zstd/lib/common/pool.c",                     "external/zstd/lib/common/threading.c",                 "external/zstd/lib/common/xxhash.c",                     "external/zstd/lib/common/zstd_common.c",
            "external/zstd/lib/compress/fse_compress.c",           "external/zstd/lib/compress/hist.c",                    "external/zstd/lib/compress/huf_compress.c",             "external/zstd/lib/compress/zstd_compress.c",
            "external/zstd/lib/compress/zstd_compress_literals.c", "external/zstd/lib/compress/zstd_compress_sequences.c", "external/zstd/lib/compress/zstd_compress_superblock.c", "external/zstd/lib/compress/zstd_double_fast.c",
            "external/zstd/lib/compress/zstd_fast.c",              "external/zstd/lib/compress/zstd_lazy.c",               "external/zstd/lib/compress/zstd_ldm.c",                 "external/zstd/lib/compress/zstd_opt.c",
            "external/zstd/lib/compress/zstd_preSplit.c",          "external/zstd/lib/compress/zstdmt_compress.c",         "external/zstd/lib/decompress/huf_decompress.c",         "external/zstd/lib/decompress/zstd_ddict.c",
            "external/zstd/lib/decompress/zstd_decompress.c",      "external/zstd/lib/decompress/zstd_decompress_block.c", "external/zstd/lib/deprecated/zbuff_common.c",           "external/zstd/lib/deprecated/zbuff_compress.c",
            "external/zstd/lib/deprecated/zbuff_decompress.c",     "external/zstd/lib/dictBuilder/cover.c",                "external/zstd/lib/dictBuilder/divsufsort.c",            "external/zstd/lib/dictBuilder/fastcover.c",
            "external/zstd/lib/dictBuilder/zdict.c",               "external/zstd/lib/legacy/zstd_v01.c",                  "external/zstd/lib/legacy/zstd_v02.c",                   "external/zstd/lib/legacy/zstd_v03.c",
            "external/zstd/lib/legacy/zstd_v04.c",                 "external/zstd/lib/legacy/zstd_v05.c",                  "external/zstd/lib/legacy/zstd_v06.c",                   "external/zstd/lib/legacy/zstd_v07.c",
        },
        .flags = flags(b, o, &cf_9, I.inc_10),
    });
}
fn srcs_libprocinfo_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libprocinfo/process.cpp",
        },
        .flags = flags(b, o, &cf_25, I.inc_30),
    });
}
fn srcs_libdexfile_support_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libdexfile/external/dex_file_supp.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_31),
    });
}
fn srcs_libtinyxml2_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/tinyxml2/tinyxml2.cpp",
        },
        .flags = flags(b, o, &cf_11, I.inc_12),
    });
}
fn srcs_art_aconfig_flags_lib_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    addGeneratedSources(m, o.generated.aconfig, flags(b, o, &cf_25, I.inc_32));
}
fn srcs_fmtlib_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/fmtlib/src/format.cc",
        },
        .flags = flags(b, o, &cf_3, I.inc_3),
    });
}
fn srcs_libssl_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/boringssl/src/ssl/bio_ssl.cc",          "external/boringssl/src/ssl/d1_both.cc",        "external/boringssl/src/ssl/d1_lib.cc",      "external/boringssl/src/ssl/d1_pkt.cc",
            "external/boringssl/src/ssl/d1_srtp.cc",          "external/boringssl/src/ssl/dtls_method.cc",    "external/boringssl/src/ssl/dtls_record.cc", "external/boringssl/src/ssl/encrypted_client_hello.cc",
            "external/boringssl/src/ssl/extensions.cc",       "external/boringssl/src/ssl/handoff.cc",        "external/boringssl/src/ssl/handshake.cc",   "external/boringssl/src/ssl/handshake_client.cc",
            "external/boringssl/src/ssl/handshake_server.cc", "external/boringssl/src/ssl/s3_both.cc",        "external/boringssl/src/ssl/s3_lib.cc",      "external/boringssl/src/ssl/s3_pkt.cc",
            "external/boringssl/src/ssl/ssl_aead_ctx.cc",     "external/boringssl/src/ssl/ssl_asn1.cc",       "external/boringssl/src/ssl/ssl_buffer.cc",  "external/boringssl/src/ssl/ssl_cert.cc",
            "external/boringssl/src/ssl/ssl_cipher.cc",       "external/boringssl/src/ssl/ssl_credential.cc", "external/boringssl/src/ssl/ssl_file.cc",    "external/boringssl/src/ssl/ssl_key_share.cc",
            "external/boringssl/src/ssl/ssl_lib.cc",          "external/boringssl/src/ssl/ssl_privkey.cc",    "external/boringssl/src/ssl/ssl_session.cc", "external/boringssl/src/ssl/ssl_stat.cc",
            "external/boringssl/src/ssl/ssl_transcript.cc",   "external/boringssl/src/ssl/ssl_versions.cc",   "external/boringssl/src/ssl/ssl_x509.cc",    "external/boringssl/src/ssl/t1_enc.cc",
            "external/boringssl/src/ssl/tls13_both.cc",       "external/boringssl/src/ssl/tls13_client.cc",   "external/boringssl/src/ssl/tls13_enc.cc",   "external/boringssl/src/ssl/tls13_server.cc",
            "external/boringssl/src/ssl/tls_method.cc",       "external/boringssl/src/ssl/tls_record.cc",
        },
        .flags = flags(b, o, &cf_21, I.inc_33),
    });
}
fn srcs_libcrypto_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/boringssl/src/crypto/asn1/a_bitstr.cc",                "external/boringssl/src/crypto/asn1/a_bool.cc",                     "external/boringssl/src/crypto/asn1/a_d2i_fp.cc",           "external/boringssl/src/crypto/asn1/a_dup.cc",
            "external/boringssl/src/crypto/asn1/a_gentm.cc",                 "external/boringssl/src/crypto/asn1/a_i2d_fp.cc",                   "external/boringssl/src/crypto/asn1/a_int.cc",              "external/boringssl/src/crypto/asn1/a_mbstr.cc",
            "external/boringssl/src/crypto/asn1/a_object.cc",                "external/boringssl/src/crypto/asn1/a_octet.cc",                    "external/boringssl/src/crypto/asn1/a_strex.cc",            "external/boringssl/src/crypto/asn1/a_strnid.cc",
            "external/boringssl/src/crypto/asn1/a_time.cc",                  "external/boringssl/src/crypto/asn1/a_type.cc",                     "external/boringssl/src/crypto/asn1/a_utctm.cc",            "external/boringssl/src/crypto/asn1/asn1_lib.cc",
            "external/boringssl/src/crypto/asn1/asn1_par.cc",                "external/boringssl/src/crypto/asn1/asn_pack.cc",                   "external/boringssl/src/crypto/asn1/f_int.cc",              "external/boringssl/src/crypto/asn1/f_string.cc",
            "external/boringssl/src/crypto/asn1/posix_time.cc",              "external/boringssl/src/crypto/asn1/tasn_dec.cc",                   "external/boringssl/src/crypto/asn1/tasn_enc.cc",           "external/boringssl/src/crypto/asn1/tasn_fre.cc",
            "external/boringssl/src/crypto/asn1/tasn_new.cc",                "external/boringssl/src/crypto/asn1/tasn_typ.cc",                   "external/boringssl/src/crypto/asn1/tasn_utl.cc",           "external/boringssl/src/crypto/base64/base64.cc",
            "external/boringssl/src/crypto/bio/bio.cc",                      "external/boringssl/src/crypto/bio/bio_mem.cc",                     "external/boringssl/src/crypto/bio/connect.cc",             "external/boringssl/src/crypto/bio/errno.cc",
            "external/boringssl/src/crypto/bio/fd.cc",                       "external/boringssl/src/crypto/bio/file.cc",                        "external/boringssl/src/crypto/bio/hexdump.cc",             "external/boringssl/src/crypto/bio/pair.cc",
            "external/boringssl/src/crypto/bio/printf.cc",                   "external/boringssl/src/crypto/bio/socket.cc",                      "external/boringssl/src/crypto/bio/socket_helper.cc",       "external/boringssl/src/crypto/blake2/blake2.cc",
            "external/boringssl/src/crypto/bn_extra/bn_asn1.cc",             "external/boringssl/src/crypto/bn_extra/convert.cc",                "external/boringssl/src/crypto/buf/buf.cc",                 "external/boringssl/src/crypto/bytestring/asn1_compat.cc",
            "external/boringssl/src/crypto/bytestring/ber.cc",               "external/boringssl/src/crypto/bytestring/cbb.cc",                  "external/boringssl/src/crypto/bytestring/cbs.cc",          "external/boringssl/src/crypto/bytestring/unicode.cc",
            "external/boringssl/src/crypto/chacha/chacha.cc",                "external/boringssl/src/crypto/cipher_extra/cipher_extra.cc",       "external/boringssl/src/crypto/cipher_extra/derive_key.cc", "external/boringssl/src/crypto/cipher_extra/e_aesctrhmac.cc",
            "external/boringssl/src/crypto/cipher_extra/e_aesgcmsiv.cc",     "external/boringssl/src/crypto/cipher_extra/e_chacha20poly1305.cc", "external/boringssl/src/crypto/cipher_extra/e_des.cc",      "external/boringssl/src/crypto/cipher_extra/e_null.cc",
            "external/boringssl/src/crypto/cipher_extra/e_rc2.cc",           "external/boringssl/src/crypto/cipher_extra/e_rc4.cc",              "external/boringssl/src/crypto/cipher_extra/e_tls.cc",      "external/boringssl/src/crypto/cipher_extra/tls_cbc.cc",
            "external/boringssl/src/crypto/conf/conf.cc",                    "external/boringssl/src/crypto/cpu_aarch64_apple.cc",               "external/boringssl/src/crypto/cpu_aarch64_fuchsia.cc",     "external/boringssl/src/crypto/cpu_aarch64_linux.cc",
            "external/boringssl/src/crypto/cpu_aarch64_openbsd.cc",          "external/boringssl/src/crypto/cpu_aarch64_sysreg.cc",              "external/boringssl/src/crypto/cpu_aarch64_win.cc",         "external/boringssl/src/crypto/cpu_arm_freebsd.cc",
            "external/boringssl/src/crypto/cpu_arm_linux.cc",                "external/boringssl/src/crypto/cpu_intel.cc",                       "external/boringssl/src/crypto/crypto.cc",                  "external/boringssl/src/crypto/curve25519/curve25519.cc",
            "external/boringssl/src/crypto/curve25519/curve25519_64_adx.cc", "external/boringssl/src/crypto/curve25519/spake25519.cc",           "external/boringssl/src/crypto/des/des.cc",                 "external/boringssl/src/crypto/dh_extra/dh_asn1.cc",
            "external/boringssl/src/crypto/dh_extra/params.cc",              "external/boringssl/src/crypto/digest_extra/digest_extra.cc",       "external/boringssl/src/crypto/dsa/dsa.cc",                 "external/boringssl/src/crypto/dsa/dsa_asn1.cc",
            "external/boringssl/src/crypto/ec_extra/ec_asn1.cc",             "external/boringssl/src/crypto/ec_extra/ec_derive.cc",              "external/boringssl/src/crypto/ec_extra/hash_to_curve.cc",  "external/boringssl/src/crypto/ecdh_extra/ecdh_extra.cc",
            "external/boringssl/src/crypto/ecdsa_extra/ecdsa_asn1.cc",       "external/boringssl/src/crypto/engine/engine.cc",                   "external/boringssl/src/crypto/err/err.cc",                 "external/boringssl/src/crypto/evp/evp.cc",
            "external/boringssl/src/crypto/evp/evp_asn1.cc",                 "external/boringssl/src/crypto/evp/evp_ctx.cc",                     "external/boringssl/src/crypto/evp/p_dh.cc",                "external/boringssl/src/crypto/evp/p_dh_asn1.cc",
            "external/boringssl/src/crypto/evp/p_dsa_asn1.cc",               "external/boringssl/src/crypto/evp/p_ec.cc",                        "external/boringssl/src/crypto/evp/p_ec_asn1.cc",           "external/boringssl/src/crypto/evp/p_ed25519.cc",
            "external/boringssl/src/crypto/evp/p_ed25519_asn1.cc",           "external/boringssl/src/crypto/evp/p_hkdf.cc",                      "external/boringssl/src/crypto/evp/p_rsa.cc",               "external/boringssl/src/crypto/evp/p_rsa_asn1.cc",
            "external/boringssl/src/crypto/evp/p_x25519.cc",                 "external/boringssl/src/crypto/evp/p_x25519_asn1.cc",               "external/boringssl/src/crypto/evp/pbkdf.cc",               "external/boringssl/src/crypto/evp/print.cc",
            "external/boringssl/src/crypto/evp/scrypt.cc",                   "external/boringssl/src/crypto/evp/sign.cc",                        "external/boringssl/src/crypto/ex_data.cc",                 "external/boringssl/src/crypto/fipsmodule/fips_shared_support.cc",
            "external/boringssl/src/crypto/hpke/hpke.cc",                    "external/boringssl/src/crypto/hrss/hrss.cc",                       "external/boringssl/src/crypto/kyber/kyber.cc",             "external/boringssl/src/crypto/lhash/lhash.cc",
            "external/boringssl/src/crypto/md4/md4.cc",                      "external/boringssl/src/crypto/md5/md5.cc",                         "external/boringssl/src/crypto/mem.cc",                     "external/boringssl/src/crypto/mldsa/mldsa.cc",
            "external/boringssl/src/crypto/mlkem/mlkem.cc",                  "external/boringssl/src/crypto/obj/obj.cc",                         "external/boringssl/src/crypto/obj/obj_xref.cc",            "external/boringssl/src/crypto/pem/pem_all.cc",
            "external/boringssl/src/crypto/pem/pem_info.cc",                 "external/boringssl/src/crypto/pem/pem_lib.cc",                     "external/boringssl/src/crypto/pem/pem_oth.cc",             "external/boringssl/src/crypto/pem/pem_pk8.cc",
            "external/boringssl/src/crypto/pem/pem_pkey.cc",                 "external/boringssl/src/crypto/pem/pem_x509.cc",                    "external/boringssl/src/crypto/pem/pem_xaux.cc",            "external/boringssl/src/crypto/pkcs7/pkcs7.cc",
            "external/boringssl/src/crypto/pkcs7/pkcs7_x509.cc",             "external/boringssl/src/crypto/pkcs8/p5_pbev2.cc",                  "external/boringssl/src/crypto/pkcs8/pkcs8.cc",             "external/boringssl/src/crypto/pkcs8/pkcs8_x509.cc",
            "external/boringssl/src/crypto/poly1305/poly1305.cc",            "external/boringssl/src/crypto/poly1305/poly1305_arm.cc",           "external/boringssl/src/crypto/poly1305/poly1305_vec.cc",   "external/boringssl/src/crypto/pool/pool.cc",
            "external/boringssl/src/crypto/rand_extra/deterministic.cc",     "external/boringssl/src/crypto/rand_extra/fork_detect.cc",          "external/boringssl/src/crypto/rand_extra/forkunsafe.cc",   "external/boringssl/src/crypto/rand_extra/getentropy.cc",
            "external/boringssl/src/crypto/rand_extra/ios.cc",               "external/boringssl/src/crypto/rand_extra/passive.cc",              "external/boringssl/src/crypto/rand_extra/rand_extra.cc",   "external/boringssl/src/crypto/rand_extra/trusty.cc",
            "external/boringssl/src/crypto/rand_extra/urandom.cc",           "external/boringssl/src/crypto/rand_extra/windows.cc",              "external/boringssl/src/crypto/rc4/rc4.cc",                 "external/boringssl/src/crypto/refcount.cc",
            "external/boringssl/src/crypto/rsa_extra/rsa_asn1.cc",           "external/boringssl/src/crypto/rsa_extra/rsa_crypt.cc",             "external/boringssl/src/crypto/rsa_extra/rsa_extra.cc",     "external/boringssl/src/crypto/rsa_extra/rsa_print.cc",
            "external/boringssl/src/crypto/sha/sha1.cc",                     "external/boringssl/src/crypto/sha/sha256.cc",                      "external/boringssl/src/crypto/sha/sha512.cc",              "external/boringssl/src/crypto/siphash/siphash.cc",
            "external/boringssl/src/crypto/slhdsa/slhdsa.cc",                "external/boringssl/src/crypto/spake2plus/spake2plus.cc",           "external/boringssl/src/crypto/stack/stack.cc",             "external/boringssl/src/crypto/thread.cc",
            "external/boringssl/src/crypto/thread_none.cc",                  "external/boringssl/src/crypto/thread_pthread.cc",                  "external/boringssl/src/crypto/thread_win.cc",              "external/boringssl/src/crypto/trust_token/pmbtoken.cc",
            "external/boringssl/src/crypto/trust_token/trust_token.cc",      "external/boringssl/src/crypto/trust_token/voprf.cc",               "external/boringssl/src/crypto/x509/a_digest.cc",           "external/boringssl/src/crypto/x509/a_sign.cc",
            "external/boringssl/src/crypto/x509/a_verify.cc",                "external/boringssl/src/crypto/x509/algorithm.cc",                  "external/boringssl/src/crypto/x509/asn1_gen.cc",           "external/boringssl/src/crypto/x509/by_dir.cc",
            "external/boringssl/src/crypto/x509/by_file.cc",                 "external/boringssl/src/crypto/x509/i2d_pr.cc",                     "external/boringssl/src/crypto/x509/name_print.cc",         "external/boringssl/src/crypto/x509/policy.cc",
            "external/boringssl/src/crypto/x509/rsa_pss.cc",                 "external/boringssl/src/crypto/x509/t_crl.cc",                      "external/boringssl/src/crypto/x509/t_req.cc",              "external/boringssl/src/crypto/x509/t_x509.cc",
            "external/boringssl/src/crypto/x509/t_x509a.cc",                 "external/boringssl/src/crypto/x509/v3_akey.cc",                    "external/boringssl/src/crypto/x509/v3_akeya.cc",           "external/boringssl/src/crypto/x509/v3_alt.cc",
            "external/boringssl/src/crypto/x509/v3_bcons.cc",                "external/boringssl/src/crypto/x509/v3_bitst.cc",                   "external/boringssl/src/crypto/x509/v3_conf.cc",            "external/boringssl/src/crypto/x509/v3_cpols.cc",
            "external/boringssl/src/crypto/x509/v3_crld.cc",                 "external/boringssl/src/crypto/x509/v3_enum.cc",                    "external/boringssl/src/crypto/x509/v3_extku.cc",           "external/boringssl/src/crypto/x509/v3_genn.cc",
            "external/boringssl/src/crypto/x509/v3_ia5.cc",                  "external/boringssl/src/crypto/x509/v3_info.cc",                    "external/boringssl/src/crypto/x509/v3_int.cc",             "external/boringssl/src/crypto/x509/v3_lib.cc",
            "external/boringssl/src/crypto/x509/v3_ncons.cc",                "external/boringssl/src/crypto/x509/v3_ocsp.cc",                    "external/boringssl/src/crypto/x509/v3_pcons.cc",           "external/boringssl/src/crypto/x509/v3_pmaps.cc",
            "external/boringssl/src/crypto/x509/v3_prn.cc",                  "external/boringssl/src/crypto/x509/v3_purp.cc",                    "external/boringssl/src/crypto/x509/v3_skey.cc",            "external/boringssl/src/crypto/x509/v3_utl.cc",
            "external/boringssl/src/crypto/x509/x509.cc",                    "external/boringssl/src/crypto/x509/x509_att.cc",                   "external/boringssl/src/crypto/x509/x509_cmp.cc",           "external/boringssl/src/crypto/x509/x509_d2.cc",
            "external/boringssl/src/crypto/x509/x509_def.cc",                "external/boringssl/src/crypto/x509/x509_ext.cc",                   "external/boringssl/src/crypto/x509/x509_lu.cc",            "external/boringssl/src/crypto/x509/x509_obj.cc",
            "external/boringssl/src/crypto/x509/x509_req.cc",                "external/boringssl/src/crypto/x509/x509_set.cc",                   "external/boringssl/src/crypto/x509/x509_trs.cc",           "external/boringssl/src/crypto/x509/x509_txt.cc",
            "external/boringssl/src/crypto/x509/x509_v3.cc",                 "external/boringssl/src/crypto/x509/x509_vfy.cc",                   "external/boringssl/src/crypto/x509/x509_vpm.cc",           "external/boringssl/src/crypto/x509/x509cset.cc",
            "external/boringssl/src/crypto/x509/x509name.cc",                "external/boringssl/src/crypto/x509/x509rset.cc",                   "external/boringssl/src/crypto/x509/x509spki.cc",           "external/boringssl/src/crypto/x509/x_algor.cc",
            "external/boringssl/src/crypto/x509/x_all.cc",                   "external/boringssl/src/crypto/x509/x_attrib.cc",                   "external/boringssl/src/crypto/x509/x_crl.cc",              "external/boringssl/src/crypto/x509/x_exten.cc",
            "external/boringssl/src/crypto/x509/x_name.cc",                  "external/boringssl/src/crypto/x509/x_pubkey.cc",                   "external/boringssl/src/crypto/x509/x_req.cc",              "external/boringssl/src/crypto/x509/x_sig.cc",
            "external/boringssl/src/crypto/x509/x_spki.cc",                  "external/boringssl/src/crypto/x509/x_val.cc",                      "external/boringssl/src/crypto/x509/x_x509.cc",             "external/boringssl/src/crypto/x509/x_x509a.cc",
            "external/boringssl/src/gen/crypto/err_data.cc",
        },
        .flags = flags(b, o, &cf_21, I.inc_26),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &linux_x86_64_crypto_asm,
        .flags = flags(b, o, &cf_22, I.inc_26),
    });
}
fn srcs_libnativehelper_any_vm_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libnativehelper/DlHelp.c", "libnativehelper/ExpandableString.c", "libnativehelper/JNIHelp.c", "libnativehelper/JniInvocation.c",
        },
        .flags = flags(b, o, &cf_23, I.inc_34),
    });
}
fn srcs_libartbase_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/tinyxml2/tinyxml2.cpp",
        },
        .flags = flags(b, o, &cf_11, I.inc_12),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartbase/arch/instruction_set.cc",        "art/libartbase/base/allocator.cc",                          "art/libartbase/base/arena_allocator.cc",        "art/libartbase/base/arena_bit_vector.cc",
            "art/libartbase/base/bit_vector.cc",             "art/libartbase/base/compiler_filter.cc",                    "art/libartbase/base/file_magic.cc",             "art/libartbase/base/file_utils.cc",
            "art/libartbase/base/flags.cc",                  "art/libartbase/base/hex_dump.cc",                           "art/libartbase/base/logging.cc",                "art/libartbase/base/malloc_arena_pool.cc",
            "art/libartbase/base/membarrier.cc",             "art/libartbase/base/memfd.cc",                              "art/libartbase/base/memory_region.cc",          "art/libartbase/base/mem_map.cc",
            "art/libartbase/base/metrics/metrics_common.cc", "art/libartbase/base/os_linux.cc",                           "art/libartbase/base/pointer_size.cc",           "art/libartbase/base/runtime_debug.cc",
            "art/libartbase/base/scoped_arena_allocator.cc", "art/libartbase/base/scoped_flock.cc",                       "art/libartbase/base/socket_peer_is_trusted.cc", "art/libartbase/base/time_utils.cc",
            "art/libartbase/base/unix_file/fd_file.cc",      "art/libartbase/base/unix_file/random_access_file_utils.cc", "art/libartbase/base/utils.cc",                  "art/libartbase/base/zip_archive.cc",
            "art/libartbase/base/globals_unix.cc",           "art/libartbase/base/mem_map_unix.cc",
        },
        .flags = flags(b, o, &cf_20, I.inc_24),
    });
    addGeneratedSources(m, o.generated.libartbase_operators, flags(b, o, &cf_20, I.inc_24));
}
fn srcs_libartbase_testing_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartbase/base/testing.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_35),
    });
}
fn srcs_libziparchive_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libziparchive/zip_archive.cc", "system/libziparchive/zip_archive_stream_entry.cc", "system/libziparchive/zip_cd_entry_map.cc", "system/libziparchive/zip_error.cpp",
            "system/libziparchive/zip_writer.cc",
        },
        .flags = flags(b, o, &cf_10, I.inc_11),
    });
}
fn srcs_libbase_static(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libbase/abi_compatibility.cpp", "system/libbase/chrono_utils.cpp",     "system/libbase/cmsg.cpp",        "system/libbase/file.cpp",
            "system/libbase/hex.cpp",               "system/libbase/logging.cpp",          "system/libbase/mapped_file.cpp", "system/libbase/parsebool.cpp",
            "system/libbase/parsenetaddress.cpp",   "system/libbase/posix_strerror_r.cpp", "system/libbase/process.cpp",     "system/libbase/properties.cpp",
            "system/libbase/result.cpp",            "system/libbase/stringprintf.cpp",     "system/libbase/strings.cpp",     "system/libbase/threads.cpp",
            "system/libbase/test_utils.cpp",        "system/libbase/errors_unix.cpp",
        },
        .flags = flags(b, o, &cf_1, I.inc_1),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/fmtlib/src/format.cc",
        },
        .flags = flags(b, o, &cf_3, I.inc_3),
    });
}
fn srcs_dalvikvm(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/dalvikvm/dalvikvm.cc",
        },
        .flags = flags(b, o, &cf_26, I.inc_36),
    });
}
fn srcs_dex2oat(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/dex2oat/dex2oat_options.cc", "art/dex2oat/dex2oat.cc",
        },
        .flags = flags(b, o, &cf_26, I.inc_37),
    });
}
fn srcs_libexpat(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    const source_flags = flags(b, o, &cf_27, I.inc_38);
    for ([_][]const u8{ "xmlparse.c", "xmlrole.c", "xmltok.c" }) |name| {
        m.addCSourceFile(.{
            .file = o.vendor.path(b, b.fmt("external/expat/expat/lib/{s}", .{name})),
            .flags = source_flags,
        });
    }
}
fn srcs_libjavacore(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libcore/luni/src/main/native/ExecStrings.cpp",           "libcore/luni/src/main/native/IcuUtilities.cpp",                          "libcore/luni/src/main/native/JniConstants.cpp",                        "libcore/luni/src/main/native/JniException.cpp",
            "libcore/luni/src/main/native/NetworkUtilities.cpp",      "libcore/luni/src/main/native/Register.cpp",                              "libcore/luni/src/main/native/ZipUtilities.cpp",                        "libcore/luni/src/main/native/android_system_OsConstants.cpp",
            "libcore/luni/src/main/native/cbigint.cpp",               "libcore/luni/src/main/native/java_lang_StringToReal.cpp",                "libcore/luni/src/main/native/java_lang_invoke_MethodHandle.cpp",       "libcore/luni/src/main/native/java_lang_invoke_VarHandle.cpp",
            "libcore/luni/src/main/native/libcore_math_NativeBN.cpp", "libcore/luni/src/main/native/libcore_icu_ICU.cpp",                       "libcore/luni/src/main/native/libcore_io_AsynchronousCloseMonitor.cpp", "libcore/luni/src/main/native/libcore_io_Linux.cpp",
            "libcore/luni/src/main/native/libcore_io_Memory.cpp",     "libcore/luni/src/main/native/libcore_util_NativeAllocationRegistry.cpp", "libcore/luni/src/main/native/org_apache_harmony_xml_ExpatParser.cpp",  "libcore/luni/src/main/native/sun_misc_Unsafe.cpp",
            "libcore/luni/src/main/native/valueOf.cpp",
        },
        .flags = flags(b, o, &cf_28, I.inc_39),
    });
}
fn srcs_libopenjdk(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libcore/ojluni/src/main/native/Inflater.c",              "libcore/ojluni/src/main/native/Deflater.c",                                "libcore/ojluni/src/main/native/CRC32.c",                             "libcore/ojluni/src/main/native/Adler32.c",
            "libcore/ojluni/src/main/native/jni_util.c",              "libcore/ojluni/src/main/native/jni_util_md.c",                             "libcore/ojluni/src/main/native/io_util.c",                           "libcore/ojluni/src/main/native/canonicalize_md.c",
            "libcore/ojluni/src/main/native/FileDescriptor_md.c",     "libcore/ojluni/src/main/native/DatagramChannelImpl.c",                     "libcore/ojluni/src/main/native/DatagramDispatcher.c",                "libcore/ojluni/src/main/native/Console_md.c",
            "libcore/ojluni/src/main/native/IOUtil.c",                "libcore/ojluni/src/main/native/PollArrayWrapper.c",                        "libcore/ojluni/src/main/native/SocketChannelImpl.c",                 "libcore/ojluni/src/main/native/FileChannelImpl.c",
            "libcore/ojluni/src/main/native/FileDispatcherImpl.c",    "libcore/ojluni/src/main/native/FileInputStream.c",                         "libcore/ojluni/src/main/native/FileSystemPreferences.c",             "libcore/ojluni/src/main/native/EPoll.c",
            "libcore/ojluni/src/main/native/EPollPort.c",             "libcore/ojluni/src/main/native/UnixAsynchronousServerSocketChannelImpl.c", "libcore/ojluni/src/main/native/UnixAsynchronousSocketChannelImpl.c", "libcore/ojluni/src/main/native/io_util_md.c",
            "libcore/ojluni/src/main/native/NativeThread.c",          "libcore/ojluni/src/main/native/FileKey.c",                                 "libcore/ojluni/src/main/native/UnixFileSystem_md.c",                 "libcore/ojluni/src/main/native/ObjectStreamClass.c",
            "libcore/ojluni/src/main/native/ObjectOutputStream.c",    "libcore/ojluni/src/main/native/ObjectInputStream.c",                       "libcore/ojluni/src/main/native/LinuxNativeDispatcher.c",             "libcore/ojluni/src/main/native/LinuxWatchService.c",
            "libcore/ojluni/src/main/native/UnixCopyFile.c",          "libcore/ojluni/src/main/native/UnixNativeDispatcher.c",                    "libcore/ojluni/src/main/native/InetAddress.c",                       "libcore/ojluni/src/main/native/net_util.c",
            "libcore/ojluni/src/main/native/net_util_md.c",           "libcore/ojluni/src/main/native/Net.c",                                     "libcore/ojluni/src/main/native/MappedByteBuffer.c",                  "libcore/ojluni/src/main/native/Inet6Address.c",
            "libcore/ojluni/src/main/native/Inet4Address.c",          "libcore/ojluni/src/main/native/ServerSocketChannelImpl.c",                 "libcore/ojluni/src/main/native/SocketInputStream.c",                 "libcore/ojluni/src/main/native/SocketOutputStream.c",
            "libcore/ojluni/src/main/native/Float.c",                 "libcore/ojluni/src/main/native/Double.c",                                  "libcore/ojluni/src/main/native/StrictMath.c",                        "libcore/ojluni/src/main/native/Math.c",
            "libcore/ojluni/src/main/native/ProcessEnvironment_md.c", "libcore/ojluni/src/main/native/System.c",                                  "libcore/ojluni/src/main/native/Runtime.c",                           "libcore/ojluni/src/main/native/UNIXProcess_md.c",
            "libcore/ojluni/src/main/native/UnixDomainSockets.c",
        },
        .flags = flags(b, o, &cf_29, I.inc_40),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libcore/ojluni/src/main/native/linux_close.cpp",  "libcore/ojluni/src/main/native/Character.cpp",            "libcore/ojluni/src/main/native/socket_tagger_util.cpp", "libcore/ojluni/src/main/native/OnLoad.cpp",
            "libcore/ojluni/src/main/native/JniConstants.cpp", "libcore/ojluni/src/main/native/jdk_internal_misc_VM.cpp",
        },
        .flags = flags(b, o, &cf_30, I.inc_40),
    });
}
fn srcs_libandroidio(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libcore/luni/src/main/native/AsynchronousCloseMonitor.cpp",
        },
        .flags = flags(b, o, &cf_28, I.inc_41),
    });
}
fn srcs_liblz4(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/lz4/lib/lz4.c", "external/lz4/lib/lz4hc.c", "external/lz4/lib/lz4frame.c", "external/lz4/lib/xxhash.c",
        },
        .flags = flags(b, o, &cf_4, I.inc_4),
    });
}
fn srcs_libnativebridge(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libnativebridge/native_bridge.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_6),
    });
}
fn srcs_libnativeloader(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libnativeloader/native_loader.cpp",
        },
        .flags = flags(b, o, &cf_0, I.inc_7),
    });
}
fn srcs_liblzma(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/lzma/C/7zAlloc.c",  "external/lzma/C/7zArcIn.c",   "external/lzma/C/7zBuf2.c",     "external/lzma/C/7zBuf.c",
            "external/lzma/C/7zCrc.c",    "external/lzma/C/7zCrcOpt.c",  "external/lzma/C/7zDec.c",      "external/lzma/C/7zFile.c",
            "external/lzma/C/7zStream.c", "external/lzma/C/Aes.c",       "external/lzma/C/AesOpt.c",     "external/lzma/C/Alloc.c",
            "external/lzma/C/Bcj2.c",     "external/lzma/C/Bra86.c",     "external/lzma/C/Bra.c",        "external/lzma/C/BraIA64.c",
            "external/lzma/C/CpuArch.c",  "external/lzma/C/Delta.c",     "external/lzma/C/LzFind.c",     "external/lzma/C/Lzma2Dec.c",
            "external/lzma/C/Lzma2Enc.c", "external/lzma/C/Lzma86Dec.c", "external/lzma/C/Lzma86Enc.c",  "external/lzma/C/LzmaDec.c",
            "external/lzma/C/LzmaEnc.c",  "external/lzma/C/LzmaLib.c",   "external/lzma/C/Ppmd7.c",      "external/lzma/C/Ppmd7Dec.c",
            "external/lzma/C/Ppmd7Enc.c", "external/lzma/C/Sha256.c",    "external/lzma/C/Sha256Opt.c",  "external/lzma/C/Sort.c",
            "external/lzma/C/Xz.c",       "external/lzma/C/XzCrc64.c",   "external/lzma/C/XzCrc64Opt.c", "external/lzma/C/XzDec.c",
            "external/lzma/C/XzEnc.c",    "external/lzma/C/XzIn.c",
        },
        .flags = flags(b, o, &cf_5, I.inc_5),
    });
}
fn srcs_libunwindstack(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/unwinding/libunwindstack/AndroidUnwinder.cpp", "system/unwinding/libunwindstack/ArmExidx.cpp",            "system/unwinding/libunwindstack/Demangle.cpp",     "system/unwinding/libunwindstack/DexFiles.cpp",
            "system/unwinding/libunwindstack/DwarfCfa.cpp",        "system/unwinding/libunwindstack/DwarfEhFrameWithHdr.cpp", "system/unwinding/libunwindstack/DwarfMemory.cpp",  "system/unwinding/libunwindstack/DwarfOp.cpp",
            "system/unwinding/libunwindstack/DwarfSection.cpp",    "system/unwinding/libunwindstack/Elf.cpp",                 "system/unwinding/libunwindstack/ElfInterface.cpp", "system/unwinding/libunwindstack/ElfInterfaceArm.cpp",
            "system/unwinding/libunwindstack/Global.cpp",          "system/unwinding/libunwindstack/JitDebug.cpp",            "system/unwinding/libunwindstack/MapInfo.cpp",      "system/unwinding/libunwindstack/Maps.cpp",
            "system/unwinding/libunwindstack/Memory.cpp",          "system/unwinding/libunwindstack/MemoryMte.cpp",           "system/unwinding/libunwindstack/MemoryXz.cpp",     "system/unwinding/libunwindstack/Regs.cpp",
            "system/unwinding/libunwindstack/RegsArm.cpp",         "system/unwinding/libunwindstack/RegsArm64.cpp",           "system/unwinding/libunwindstack/RegsX86.cpp",      "system/unwinding/libunwindstack/RegsX86_64.cpp",
            "system/unwinding/libunwindstack/RegsRiscv64.cpp",     "system/unwinding/libunwindstack/Symbols.cpp",             "system/unwinding/libunwindstack/ThreadEntry.cpp",  "system/unwinding/libunwindstack/ThreadUnwinder.cpp",
            "system/unwinding/libunwindstack/Unwinder.cpp",        "system/unwinding/libunwindstack/DexFile.cpp",             "system/unwinding/libunwindstack/LogAndroid.cpp",
        },
        .flags = flags(b, o, &cf_6, I.inc_8),
    });
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/unwinding/libunwindstack/AsmGetRegsX86_64.S",
        },
        .flags = flags(b, o, &cf_7, I.inc_8),
    });
}
fn srcs_libdexfile(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libdexfile/dex/art_dex_file_loader.cc",         "art/libdexfile/dex/compact_dex_file.cc",           "art/libdexfile/dex/compact_offset_table.cc", "art/libdexfile/dex/descriptors_names.cc",
            "art/libdexfile/dex/dex_file.cc",                    "art/libdexfile/dex/dex_file_exception_helpers.cc", "art/libdexfile/dex/dex_file_layout.cc",      "art/libdexfile/dex/dex_file_loader.cc",
            "art/libdexfile/dex/dex_file_tracking_registrar.cc", "art/libdexfile/dex/dex_file_verifier.cc",          "art/libdexfile/dex/dex_instruction.cc",      "art/libdexfile/dex/modifiers.cc",
            "art/libdexfile/dex/primitive.cc",                   "art/libdexfile/dex/signature.cc",                  "art/libdexfile/dex/standard_dex_file.cc",    "art/libdexfile/dex/type_lookup_table.cc",
            "art/libdexfile/dex/utf.cc",                         "art/libdexfile/external/dex_file_ext.cc",
        },
        .flags = flags(b, o, &cf_12, I.inc_23),
    });
    addGeneratedSources(m, o.generated.dexfile_operators, flags(b, o, &cf_12, I.inc_23));
}
fn srcs_libprofile(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libprofile/profile/profile_boot_info.cc", "art/libprofile/profile/profile_compilation_info.cc",
        },
        .flags = flags(b, o, &cf_12, I.inc_13),
    });
}
fn srcs_libart_disassembler(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/disassembler/disassembler.cc", "art/disassembler/disassembler_x86.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_14),
    });
}
fn srcs_libart(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    _ = .{ b, o, I, m };
}
fn srcs_libziparchive(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libziparchive/zip_archive.cc", "system/libziparchive/zip_archive_stream_entry.cc", "system/libziparchive/zip_cd_entry_map.cc", "system/libziparchive/zip_error.cpp",
            "system/libziparchive/zip_writer.cc",
        },
        .flags = flags(b, o, &cf_10, I.inc_11),
    });
}
fn srcs_libartpalette(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartpalette/system/palette_fake.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_42),
    });
}
fn srcs_libbase(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/libbase/abi_compatibility.cpp", "system/libbase/chrono_utils.cpp",     "system/libbase/cmsg.cpp",        "system/libbase/file.cpp",
            "system/libbase/hex.cpp",               "system/libbase/logging.cpp",          "system/libbase/mapped_file.cpp", "system/libbase/parsebool.cpp",
            "system/libbase/parsenetaddress.cpp",   "system/libbase/posix_strerror_r.cpp", "system/libbase/process.cpp",     "system/libbase/properties.cpp",
            "system/libbase/result.cpp",            "system/libbase/stringprintf.cpp",     "system/libbase/strings.cpp",     "system/libbase/threads.cpp",
            "system/libbase/test_utils.cpp",        "system/libbase/errors_unix.cpp",
        },
        .flags = flags(b, o, &cf_1, I.inc_1),
    });
}
fn srcs_libartbase(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/libartbase/arch/instruction_set.cc",        "art/libartbase/base/allocator.cc",                          "art/libartbase/base/arena_allocator.cc",        "art/libartbase/base/arena_bit_vector.cc",
            "art/libartbase/base/bit_vector.cc",             "art/libartbase/base/compiler_filter.cc",                    "art/libartbase/base/file_magic.cc",             "art/libartbase/base/file_utils.cc",
            "art/libartbase/base/flags.cc",                  "art/libartbase/base/hex_dump.cc",                           "art/libartbase/base/logging.cc",                "art/libartbase/base/malloc_arena_pool.cc",
            "art/libartbase/base/membarrier.cc",             "art/libartbase/base/memfd.cc",                              "art/libartbase/base/memory_region.cc",          "art/libartbase/base/mem_map.cc",
            "art/libartbase/base/metrics/metrics_common.cc", "art/libartbase/base/os_linux.cc",                           "art/libartbase/base/pointer_size.cc",           "art/libartbase/base/runtime_debug.cc",
            "art/libartbase/base/scoped_arena_allocator.cc", "art/libartbase/base/scoped_flock.cc",                       "art/libartbase/base/socket_peer_is_trusted.cc", "art/libartbase/base/time_utils.cc",
            "art/libartbase/base/unix_file/fd_file.cc",      "art/libartbase/base/unix_file/random_access_file_utils.cc", "art/libartbase/base/utils.cc",                  "art/libartbase/base/zip_archive.cc",
            "art/libartbase/base/globals_unix.cc",           "art/libartbase/base/mem_map_unix.cc",
        },
        .flags = flags(b, o, &cf_12, I.inc_24),
    });
    addGeneratedSources(m, o.generated.libartbase_operators, flags(b, o, &cf_12, I.inc_24));
}
fn srcs_libopenjdkjvm(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/openjdkjvm/OpenjdkJvm.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_43),
    });
}
fn srcs_libjavacrypto(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/conscrypt/common/src/jni/main/cpp/conscrypt/compatibility_close_monitor.cc", "external/conscrypt/common/src/jni/main/cpp/conscrypt/jniload.cc", "external/conscrypt/common/src/jni/main/cpp/conscrypt/jniutil.cc", "external/conscrypt/common/src/jni/main/cpp/conscrypt/native_crypto.cc",
            "external/conscrypt/common/src/jni/main/cpp/conscrypt/netutil.cc",
        },
        .flags = flags(b, o, &cf_31, I.inc_44),
    });
}
fn srcs_libnativehelper(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "libnativehelper/JNIPlatformHelp.c", "libnativehelper/JniConstants.c", "libnativehelper/file_descriptor_jni.c",
        },
        .flags = flags(b, o, &cf_23, I.inc_45),
    });
}
fn srcs_libsigchain(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/sigchainlib/sigchain.cc",
        },
        .flags = flags(b, o, &cf_0, I.inc_0),
    });
}
fn srcs_liblog(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "system/logging/liblog/log_event_list.cpp", "system/logging/liblog/log_event_write.cpp", "system/logging/liblog/logger_name.cpp", "system/logging/liblog/logger_read.cpp",
            "system/logging/liblog/logger_write.cpp",   "system/logging/liblog/logprint.cpp",        "system/logging/liblog/properties.cpp",  "system/logging/liblog/event_tag_map.cpp",
        },
        .flags = flags(b, o, &cf_2, I.inc_2),
    });
}
fn srcs_libz(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "external/zlib/adler32.c", "external/zlib/adler32_simd.c", "external/zlib/compress.c",    "external/zlib/cpu_features.c",
            "external/zlib/crc32.c",   "external/zlib/crc32_simd.c",   "external/zlib/crc_folding.c", "external/zlib/deflate.c",
            "external/zlib/gzclose.c", "external/zlib/gzlib.c",        "external/zlib/gzread.c",      "external/zlib/gzwrite.c",
            "external/zlib/infback.c", "external/zlib/inffast.c",      "external/zlib/inflate.c",     "external/zlib/inftrees.c",
            "external/zlib/trees.c",   "external/zlib/uncompr.c",      "external/zlib/zutil.c",
        },
        .flags = flags(b, o, &cf_8, I.inc_9),
    });
}
fn srcs_generate_boot_image(b: *std.Build, o: Options, I: Incs, m: *SourceGroups) void {
    m.addCSourceFiles(.{
        .root = o.vendor,
        .files = &.{
            "art/test/generate-boot-image/generate-boot-image.cc",
        },
        .flags = flags(b, o, &cf_26, I.inc_46),
    });
}

pub const OperatorGroup = enum {
    compiler_operators,
    runtime_operators,
    dexfile_operators,
    libartbase_operators,
    dex2oat_operators,
};
pub const OperatorOut = struct { group: OperatorGroup, local: []const u8, headers: []const []const u8 };
/// generate_operator_out.py invocations Soong performs for this target set
pub const operator_out = [_]OperatorOut{
    .{ .group = .compiler_operators, .local = "art/compiler", .headers = &.{
        "art/compiler/linker/linker_patch.h",
        "art/compiler/optimizing/locations.h",
        "art/compiler/optimizing/nodes.h",
        "art/compiler/optimizing/optimizing_compiler_stats.h",
        "art/compiler/linker/linker_patch.h",
        "art/compiler/optimizing/locations.h",
        "art/compiler/optimizing/nodes.h",
        "art/compiler/optimizing/optimizing_compiler_stats.h",
    } },
    .{ .group = .runtime_operators, .local = "art/runtime", .headers = &.{
        "art/runtime/base/callee_save_type.h",
        "art/runtime/base/locks.h",
        "art/runtime/class_status.h",
        "art/runtime/compilation_kind.h",
        "art/runtime/gc/allocator/rosalloc.h",
        "art/runtime/gc/allocator_type.h",
        "art/runtime/gc/collector/gc_type.h",
        "art/runtime/gc/collector/mark_compact.h",
        "art/runtime/gc/collector_type.h",
        "art/runtime/gc/space/region_space.h",
        "art/runtime/gc/space/space.h",
        "art/runtime/gc/weak_root_state.h",
        "art/runtime/gc_root.h",
        "art/runtime/indirect_reference_table.h",
        "art/runtime/instrumentation.h",
        "art/runtime/jdwp_provider.h",
        "art/runtime/jni_id_type.h",
        "art/runtime/linear_alloc.h",
        "art/runtime/lock_word.h",
        "art/runtime/oat/image.h",
        "art/runtime/oat/oat.h",
        "art/runtime/oat/oat_file.h",
        "art/runtime/process_state.h",
        "art/runtime/reflective_value_visitor.h",
        "art/runtime/stack.h",
        "art/runtime/suspend_reason.h",
        "art/runtime/thread.h",
        "art/runtime/thread_state.h",
        "art/runtime/trace.h",
        "art/runtime/trace_profile.h",
        "art/runtime/verifier/verifier_enums.h",
        "art/runtime/base/callee_save_type.h",
        "art/runtime/base/locks.h",
        "art/runtime/class_status.h",
        "art/runtime/compilation_kind.h",
        "art/runtime/gc/allocator/rosalloc.h",
        "art/runtime/gc/allocator_type.h",
        "art/runtime/gc/collector/gc_type.h",
        "art/runtime/gc/collector/mark_compact.h",
        "art/runtime/gc/collector_type.h",
        "art/runtime/gc/space/region_space.h",
        "art/runtime/gc/space/space.h",
        "art/runtime/gc/weak_root_state.h",
        "art/runtime/gc_root.h",
        "art/runtime/indirect_reference_table.h",
        "art/runtime/instrumentation.h",
        "art/runtime/jdwp_provider.h",
        "art/runtime/jni_id_type.h",
        "art/runtime/linear_alloc.h",
        "art/runtime/lock_word.h",
        "art/runtime/oat/image.h",
        "art/runtime/oat/oat.h",
        "art/runtime/oat/oat_file.h",
        "art/runtime/process_state.h",
        "art/runtime/reflective_value_visitor.h",
        "art/runtime/stack.h",
        "art/runtime/suspend_reason.h",
        "art/runtime/thread.h",
        "art/runtime/thread_state.h",
        "art/runtime/trace.h",
        "art/runtime/trace_profile.h",
        "art/runtime/verifier/verifier_enums.h",
    } },
    .{ .group = .dexfile_operators, .local = "art/libdexfile", .headers = &.{
        "art/libdexfile/dex/dex_file.h",
        "art/libdexfile/dex/dex_file_layout.h",
        "art/libdexfile/dex/dex_instruction.h",
        "art/libdexfile/dex/dex_instruction_utils.h",
        "art/libdexfile/dex/invoke_type.h",
        "art/libdexfile/dex/dex_file.h",
        "art/libdexfile/dex/dex_file_layout.h",
        "art/libdexfile/dex/dex_instruction.h",
        "art/libdexfile/dex/dex_instruction_utils.h",
        "art/libdexfile/dex/invoke_type.h",
    } },
    .{ .group = .libartbase_operators, .local = "art/libartbase", .headers = &.{
        "art/libartbase/arch/instruction_set.h",
        "art/libartbase/base/allocator.h",
        "art/libartbase/base/unix_file/fd_file.h",
        "art/libartbase/arch/instruction_set.h",
        "art/libartbase/base/allocator.h",
        "art/libartbase/base/unix_file/fd_file.h",
        "art/libartbase/arch/instruction_set.h",
        "art/libartbase/base/allocator.h",
        "art/libartbase/base/unix_file/fd_file.h",
    } },
    .{ .group = .dex2oat_operators, .local = "art/dex2oat", .headers = &.{
        "art/dex2oat/linker/image_writer.h",
    } },
};
/// flags Soong compiles art/tools/cpp-define-generator/asm_defines.cc with (for build/genrules.zig)
pub fn asmDefinesFlags(b: *std.Build, o: Options) []const []const u8 {
    return flags(b, o, &cf_0, incSet(b, o, &inc_47)).args;
}

pub fn addAsmDefinesInputs(b: *std.Build, o: Options, run: *std.Build.Step.Run) void {
    for (inc_47) |include| {
        std.debug.assert(include.generated == null);
        const path = rootPath(o, include.root).path(b, include.rel);
        if (std.mem.eql(u8, include.kind, "-isystem"))
            run.addPrefixedDirectoryArg("-isystem", path)
        else
            run.addPrefixedDirectoryArg("-I", path);
    }
}

pub const Artifacts = struct {
    libsigchain_static: *std.Build.Step.Compile,
    libdex2oat_static_static: *std.Build.Step.Compile,
    libnativehelper_compat_libc___static: *std.Build.Step.Compile,
    libcrypto_static_static: *std.Build.Step.Compile,
    libfdlibm_static: *std.Build.Step.Compile,
    libart_compiler_static: *std.Build.Step.Compile,
    libart_runtime_static: *std.Build.Step.Compile,
    libodrstatslog_static: *std.Build.Step.Compile,
    libelffile_static: *std.Build.Step.Compile,
    libzstd_static: *std.Build.Step.Compile,
    libprocinfo_static: *std.Build.Step.Compile,
    libdexfile_support_static: *std.Build.Step.Compile,
    libtinyxml2_static: *std.Build.Step.Compile,
    art_aconfig_flags_lib_static: *std.Build.Step.Compile,
    fmtlib_static: *std.Build.Step.Compile,
    libssl_static: *std.Build.Step.Compile,
    libcrypto_static: *std.Build.Step.Compile,
    libnativehelper_any_vm_static: *std.Build.Step.Compile,
    libartbase_static: *std.Build.Step.Compile,
    libartbase_testing_static: *std.Build.Step.Compile,
    libziparchive_static: *std.Build.Step.Compile,
    libbase_static: *std.Build.Step.Compile,
    dalvikvm: *std.Build.Step.Compile,
    dex2oat: *std.Build.Step.Compile,
    libexpat: *std.Build.Step.Compile,
    libjavacore: *std.Build.Step.Compile,
    libopenjdk: *std.Build.Step.Compile,
    libandroidio: *std.Build.Step.Compile,
    liblz4: *std.Build.Step.Compile,
    libnativebridge: *std.Build.Step.Compile,
    libnativeloader: *std.Build.Step.Compile,
    liblzma: *std.Build.Step.Compile,
    libunwindstack: *std.Build.Step.Compile,
    libdexfile: *std.Build.Step.Compile,
    libprofile: *std.Build.Step.Compile,
    libart_disassembler: *std.Build.Step.Compile,
    libart: *std.Build.Step.Compile,
    libziparchive: *std.Build.Step.Compile,
    libartpalette: *std.Build.Step.Compile,
    libbase: *std.Build.Step.Compile,
    libartbase: *std.Build.Step.Compile,
    libopenjdkjvm: *std.Build.Step.Compile,
    libjavacrypto: *std.Build.Step.Compile,
    libnativehelper: *std.Build.Step.Compile,
    libsigchain: *std.Build.Step.Compile,
    liblog: *std.Build.Step.Compile,
    libz: *std.Build.Step.Compile,
    generate_boot_image: *std.Build.Step.Compile,
};

pub fn build(b: *std.Build, o: Options) Artifacts {
    if (o.target.result.cpu.arch != .x86_64 or o.target.result.os.tag != .linux) {
        @panic("Artzig's ART runtime supports only Linux x86_64");
    }
    const I: Incs = .{
        .inc_0 = incSet(b, o, &inc_0),
        .inc_1 = incSet(b, o, &inc_1),
        .inc_2 = incSet(b, o, &inc_2),
        .inc_3 = incSet(b, o, &inc_3),
        .inc_4 = incSet(b, o, &inc_4),
        .inc_5 = incSet(b, o, &inc_5),
        .inc_6 = incSet(b, o, &inc_6),
        .inc_7 = incSet(b, o, &inc_7),
        .inc_8 = incSet(b, o, &inc_8),
        .inc_9 = incSet(b, o, &inc_9),
        .inc_10 = incSet(b, o, &inc_10),
        .inc_11 = incSet(b, o, &inc_11),
        .inc_12 = incSet(b, o, &inc_12),
        .inc_13 = incSet(b, o, &inc_13),
        .inc_14 = incSet(b, o, &inc_14),
        .inc_16 = incSet(b, o, &inc_16),
        .inc_17 = incSet(b, o, &inc_17),
        .inc_18 = incSet(b, o, &inc_18),
        .inc_19 = incSet(b, o, &inc_19),
        .inc_20 = incSet(b, o, &inc_20),
        .inc_21 = incSet(b, o, &inc_21),
        .inc_22 = incSet(b, o, &inc_22),
        .inc_23 = incSet(b, o, &inc_23),
        .inc_24 = incSet(b, o, &inc_24),
        .inc_25 = incSet(b, o, &inc_25),
        .inc_26 = incSet(b, o, &inc_26),
        .inc_27 = incSet(b, o, &inc_27),
        .inc_28 = incSet(b, o, &inc_28),
        .inc_29 = incSet(b, o, &inc_29),
        .inc_30 = incSet(b, o, &inc_30),
        .inc_31 = incSet(b, o, &inc_31),
        .inc_32 = incSet(b, o, &inc_32),
        .inc_33 = incSet(b, o, &inc_33),
        .inc_34 = incSet(b, o, &inc_34),
        .inc_35 = incSet(b, o, &inc_35),
        .inc_36 = incSet(b, o, &inc_36),
        .inc_37 = incSet(b, o, &inc_37),
        .inc_38 = incSet(b, o, &inc_38),
        .inc_39 = incSet(b, o, &inc_39),
        .inc_40 = incSet(b, o, &inc_40),
        .inc_41 = incSet(b, o, &inc_41),
        .inc_42 = incSet(b, o, &inc_42),
        .inc_43 = incSet(b, o, &inc_43),
        .inc_44 = incSet(b, o, &inc_44),
        .inc_45 = incSet(b, o, &inc_45),
        .inc_46 = incSet(b, o, &inc_46),
        .inc_47 = incSet(b, o, &inc_47),
    };
    const libsigchain_static = newLib(b, o, "sigchain", .static);
    applySourceGroups(b, o, I, libsigchain_static, srcs_libsigchain_static);
    const libdex2oat_static_static = newLib(b, o, "dex2oat_static", .static);
    applySourceGroups(b, o, I, libdex2oat_static_static, srcs_libdex2oat_static_static);
    const libnativehelper_compat_libc___static = newLib(b, o, "nativehelper_compat_libc++", .static);
    applySourceGroups(b, o, I, libnativehelper_compat_libc___static, srcs_libnativehelper_compat_libc___static);
    const libcrypto_static_static = newLib(b, o, "crypto_static", .static);
    applySourceGroups(b, o, I, libcrypto_static_static, srcs_libcrypto_static_static);
    const libfdlibm_static = newLib(b, o, "fdlibm", .static);
    applySourceGroups(b, o, I, libfdlibm_static, srcs_libfdlibm_static);
    const libart_compiler_static = newLib(b, o, "art-compiler", .static);
    applySourceGroups(b, o, I, libart_compiler_static, srcs_libart_compiler_static);
    const libart_runtime_static = newLib(b, o, "art-runtime", .static);
    applySourceGroups(b, o, I, libart_runtime_static, srcs_libart_runtime_static);
    const libodrstatslog_static = newLib(b, o, "odrstatslog", .static);
    applySourceGroups(b, o, I, libodrstatslog_static, srcs_libodrstatslog_static);
    const libelffile_static = newLib(b, o, "elffile", .static);
    applySourceGroups(b, o, I, libelffile_static, srcs_libelffile_static);
    const libzstd_static = newLib(b, o, "zstd", .static);
    applySourceGroups(b, o, I, libzstd_static, srcs_libzstd_static);
    const libprocinfo_static = newLib(b, o, "procinfo", .static);
    applySourceGroups(b, o, I, libprocinfo_static, srcs_libprocinfo_static);
    const libdexfile_support_static = newLib(b, o, "dexfile_support", .static);
    applySourceGroups(b, o, I, libdexfile_support_static, srcs_libdexfile_support_static);
    const libtinyxml2_static = newLib(b, o, "tinyxml2", .static);
    applySourceGroups(b, o, I, libtinyxml2_static, srcs_libtinyxml2_static);
    const art_aconfig_flags_lib_static = newLib(b, o, "art-aconfig-flags-lib", .static);
    applySourceGroups(b, o, I, art_aconfig_flags_lib_static, srcs_art_aconfig_flags_lib_static);
    const fmtlib_static = newLib(b, o, "fmtlib", .static);
    applySourceGroups(b, o, I, fmtlib_static, srcs_fmtlib_static);
    const libssl_static = newLib(b, o, "ssl", .static);
    applySourceGroups(b, o, I, libssl_static, srcs_libssl_static);
    const libcrypto_static = newLib(b, o, "crypto", .static);
    applySourceGroups(b, o, I, libcrypto_static, srcs_libcrypto_static);
    const libnativehelper_any_vm_static = newLib(b, o, "nativehelper_any_vm", .static);
    applySourceGroups(b, o, I, libnativehelper_any_vm_static, srcs_libnativehelper_any_vm_static);
    const libartbase_static = newLib(b, o, "artbase", .static);
    applySourceGroups(b, o, I, libartbase_static, srcs_libartbase_static);
    const libartbase_testing_static = newLib(b, o, "artbase-testing", .static);
    applySourceGroups(b, o, I, libartbase_testing_static, srcs_libartbase_testing_static);
    const libziparchive_static = newLib(b, o, "ziparchive", .static);
    applySourceGroups(b, o, I, libziparchive_static, srcs_libziparchive_static);
    const libbase_static = newLib(b, o, "base", .static);
    applySourceGroups(b, o, I, libbase_static, srcs_libbase_static);
    const dalvikvm = newExe(b, o, "dalvikvm64");
    applySourceGroups(b, o, I, dalvikvm, srcs_dalvikvm);
    const dex2oat = newExe(b, o, "dex2oat64");
    applySourceGroups(b, o, I, dex2oat, srcs_dex2oat);
    const libexpat = newLib(b, o, "expat-host", .dynamic);
    applySourceGroups(b, o, I, libexpat, srcs_libexpat);
    const libjavacore = newLib(b, o, "javacore", .dynamic);
    applySourceGroups(b, o, I, libjavacore, srcs_libjavacore);
    const libopenjdk = newLib(b, o, "openjdk", .dynamic);
    applySourceGroups(b, o, I, libopenjdk, srcs_libopenjdk);
    const libandroidio = newLib(b, o, "androidio", .dynamic);
    applySourceGroups(b, o, I, libandroidio, srcs_libandroidio);
    const liblz4 = newLib(b, o, "lz4", .dynamic);
    applySourceGroups(b, o, I, liblz4, srcs_liblz4);
    const libnativebridge = newLib(b, o, "nativebridge", .dynamic);
    applySourceGroups(b, o, I, libnativebridge, srcs_libnativebridge);
    const libnativeloader = newLib(b, o, "nativeloader", .dynamic);
    applySourceGroups(b, o, I, libnativeloader, srcs_libnativeloader);
    const liblzma = newLib(b, o, "lzma", .dynamic);
    applySourceGroups(b, o, I, liblzma, srcs_liblzma);
    const libunwindstack = newLib(b, o, "unwindstack", .dynamic);
    applySourceGroups(b, o, I, libunwindstack, srcs_libunwindstack);
    const libdexfile = newLib(b, o, "dexfile", .dynamic);
    applySourceGroups(b, o, I, libdexfile, srcs_libdexfile);
    const libprofile = newLib(b, o, "profile", .dynamic);
    applySourceGroups(b, o, I, libprofile, srcs_libprofile);
    const libart_disassembler = newLib(b, o, "art-disassembler", .dynamic);
    applySourceGroups(b, o, I, libart_disassembler, srcs_libart_disassembler);
    const libart = newLib(b, o, "art", .dynamic);
    applySourceGroups(b, o, I, libart, srcs_libart);
    const libziparchive = newLib(b, o, "ziparchive", .dynamic);
    applySourceGroups(b, o, I, libziparchive, srcs_libziparchive);
    const libartpalette = newLib(b, o, "artpalette", .dynamic);
    applySourceGroups(b, o, I, libartpalette, srcs_libartpalette);
    const libbase = newLib(b, o, "base", .dynamic);
    applySourceGroups(b, o, I, libbase, srcs_libbase);
    const libartbase = newLib(b, o, "artbase", .dynamic);
    applySourceGroups(b, o, I, libartbase, srcs_libartbase);
    const libopenjdkjvm = newLib(b, o, "openjdkjvm", .dynamic);
    applySourceGroups(b, o, I, libopenjdkjvm, srcs_libopenjdkjvm);
    const libjavacrypto = newLib(b, o, "javacrypto", .dynamic);
    applySourceGroups(b, o, I, libjavacrypto, srcs_libjavacrypto);
    const libnativehelper = newLib(b, o, "nativehelper", .dynamic);
    applySourceGroups(b, o, I, libnativehelper, srcs_libnativehelper);
    const libsigchain = newLib(b, o, "sigchain", .dynamic);
    applySourceGroups(b, o, I, libsigchain, srcs_libsigchain);
    const liblog = newLib(b, o, "log", .dynamic);
    applySourceGroups(b, o, I, liblog, srcs_liblog);
    const libz = newLib(b, o, "z-host", .dynamic);
    applySourceGroups(b, o, I, libz, srcs_libz);
    const generate_boot_image = newExe(b, o, "generate-boot-image64");
    applySourceGroups(b, o, I, generate_boot_image, srcs_generate_boot_image);
    applySourceGroups(b, o, I, dalvikvm, srcs_libsigchain_static); // --whole-archive
    dalvikvm.root_module.linkLibrary(liblog);
    dalvikvm.root_module.linkLibrary(libnativehelper);
    // ldflag: -pie
    // ldflag: -Wl,--export-dynamic
    dex2oat.root_module.linkLibrary(libdex2oat_static_static);
    dex2oat.root_module.addCSourceFiles(.{ .root = o.project, .files = &.{"src/gen/rustlibs_stub.c"}, .flags = &.{"-fPIC"} });
    // ldflag: -pie
    libjavacore.root_module.linkLibrary(libnativehelper_compat_libc___static);
    libjavacore.root_module.linkLibrary(libcrypto_static_static);
    libjavacore.root_module.linkLibrary(libziparchive_static);
    libjavacore.root_module.linkLibrary(libbase);
    libjavacore.root_module.linkLibrary(liblog);
    libjavacore.root_module.linkLibrary(libnativehelper);
    libjavacore.root_module.linkLibrary(libandroidio);
    libjavacore.root_module.linkLibrary(o.icu);
    libjavacore.root_module.linkLibrary(libexpat);
    libjavacore.root_module.linkLibrary(libz);
    libjavacore.setVersionScript(o.vendor.path(b, "libcore/libjavacore.map"));
    libopenjdk.root_module.linkLibrary(libnativehelper_compat_libc___static);
    libopenjdk.root_module.linkLibrary(libcrypto_static_static);
    libopenjdk.root_module.linkLibrary(libfdlibm_static);
    libopenjdk.root_module.linkLibrary(libbase);
    libopenjdk.root_module.linkLibrary(liblog);
    libopenjdk.root_module.linkLibrary(libnativehelper);
    libopenjdk.root_module.linkLibrary(libandroidio);
    libopenjdk.root_module.linkLibrary(o.icu);
    libopenjdk.root_module.linkLibrary(libz);
    libopenjdk.root_module.linkLibrary(libopenjdkjvm);
    libandroidio.root_module.linkLibrary(liblog);
    libnativebridge.root_module.linkLibrary(liblog);
    libnativeloader.root_module.linkLibrary(libnativebridge);
    libnativeloader.root_module.linkLibrary(libbase);
    libnativeloader.root_module.linkLibrary(liblog);
    applySourceGroups(b, o, I, libunwindstack, srcs_libzstd_static); // --whole-archive
    libunwindstack.root_module.addCSourceFiles(.{ .root = o.project, .files = &.{"src/gen/rustlibs_stub.c"}, .flags = &.{"-fPIC"} });
    libunwindstack.root_module.linkLibrary(libprocinfo_static);
    libunwindstack.root_module.linkLibrary(libdexfile_support_static);
    libunwindstack.root_module.linkLibrary(libbase);
    libunwindstack.root_module.linkLibrary(liblog);
    libunwindstack.root_module.linkLibrary(liblzma);
    libunwindstack.root_module.linkLibrary(libz);
    libunwindstack.setVersionScript(o.vendor.path(b, "system/unwinding/libunwindstack/exported_x86.map"));
    libdexfile.root_module.linkLibrary(libziparchive);
    libdexfile.root_module.linkLibrary(libz);
    libdexfile.root_module.linkLibrary(libartpalette);
    libdexfile.root_module.linkLibrary(liblog);
    libdexfile.root_module.linkLibrary(libbase);
    libdexfile.root_module.linkLibrary(libartbase);
    libprofile.root_module.linkLibrary(libartpalette);
    libprofile.root_module.linkLibrary(libziparchive);
    libprofile.root_module.linkLibrary(libz);
    libprofile.root_module.linkLibrary(libbase);
    libprofile.root_module.linkLibrary(libartbase);
    libprofile.root_module.linkLibrary(libdexfile);
    libart_disassembler.root_module.linkLibrary(libbase);
    applySourceGroups(b, o, I, libart, srcs_libart_compiler_static); // --whole-archive
    applySourceGroups(b, o, I, libart, srcs_libart_runtime_static); // --whole-archive
    libart.root_module.linkLibrary(libodrstatslog_static);
    libart.root_module.linkLibrary(libelffile_static);
    libart.root_module.linkLibrary(libartpalette);
    libart.root_module.linkLibrary(libbase);
    libart.root_module.linkLibrary(liblog);
    libart.root_module.linkLibrary(liblz4);
    libart.root_module.linkLibrary(liblzma);
    libart.root_module.linkLibrary(libnativebridge);
    libart.root_module.linkLibrary(libnativeloader);
    libart.root_module.linkLibrary(libsigchain);
    libart.root_module.linkLibrary(libunwindstack);
    libart.root_module.linkLibrary(libartbase);
    libart.root_module.linkLibrary(libdexfile);
    libart.root_module.linkLibrary(libprofile);
    libart.root_module.linkLibrary(libz);
    libart.root_module.linkLibrary(libart_disassembler);
    // ldflag: -z
    libziparchive.root_module.linkLibrary(liblog);
    libziparchive.root_module.linkLibrary(libbase);
    libziparchive.root_module.linkLibrary(libz);
    libartpalette.root_module.linkLibrary(libbase);
    libartpalette.root_module.linkLibrary(liblog);
    libartpalette.setVersionScript(o.vendor.path(b, "art/libartpalette/libartpalette.map"));
    applySourceGroups(b, o, I, libbase, srcs_fmtlib_static); // --whole-archive
    libbase.root_module.linkLibrary(liblog);
    applySourceGroups(b, o, I, libartbase, srcs_libtinyxml2_static); // --whole-archive
    libartbase.root_module.linkSystemLibrary("cap", .{}); // prebuilt libcap.a -> system
    libartbase.root_module.linkLibrary(art_aconfig_flags_lib_static);
    libartbase.root_module.linkLibrary(libziparchive);
    libartbase.root_module.linkLibrary(libz);
    libartbase.root_module.linkLibrary(liblog);
    libartbase.root_module.linkLibrary(libartpalette);
    libartbase.root_module.linkLibrary(libbase);
    libopenjdkjvm.root_module.linkLibrary(libbase);
    libopenjdkjvm.root_module.linkLibrary(libart);
    libopenjdkjvm.root_module.linkLibrary(libartbase);
    libjavacrypto.root_module.linkLibrary(libssl_static);
    libjavacrypto.root_module.linkLibrary(libcrypto_static);
    libjavacrypto.root_module.linkLibrary(liblog);
    applySourceGroups(b, o, I, libnativehelper, srcs_libnativehelper_any_vm_static); // --whole-archive
    libnativehelper.root_module.linkLibrary(liblog);
    // ldflag: -Wl,-z,global
    generate_boot_image.root_module.linkLibrary(libartbase_static);
    generate_boot_image.root_module.linkLibrary(libartbase_testing_static);
    generate_boot_image.root_module.linkLibrary(libziparchive_static);
    generate_boot_image.root_module.linkLibrary(libbase_static);
    generate_boot_image.root_module.linkLibrary(liblog);
    generate_boot_image.root_module.linkLibrary(libz);
    // ldflag: -pie
    return .{
        .libsigchain_static = libsigchain_static,
        .libdex2oat_static_static = libdex2oat_static_static,
        .libnativehelper_compat_libc___static = libnativehelper_compat_libc___static,
        .libcrypto_static_static = libcrypto_static_static,
        .libfdlibm_static = libfdlibm_static,
        .libart_compiler_static = libart_compiler_static,
        .libart_runtime_static = libart_runtime_static,
        .libodrstatslog_static = libodrstatslog_static,
        .libelffile_static = libelffile_static,
        .libzstd_static = libzstd_static,
        .libprocinfo_static = libprocinfo_static,
        .libdexfile_support_static = libdexfile_support_static,
        .libtinyxml2_static = libtinyxml2_static,
        .art_aconfig_flags_lib_static = art_aconfig_flags_lib_static,
        .fmtlib_static = fmtlib_static,
        .libssl_static = libssl_static,
        .libcrypto_static = libcrypto_static,
        .libnativehelper_any_vm_static = libnativehelper_any_vm_static,
        .libartbase_static = libartbase_static,
        .libartbase_testing_static = libartbase_testing_static,
        .libziparchive_static = libziparchive_static,
        .libbase_static = libbase_static,
        .dalvikvm = dalvikvm,
        .dex2oat = dex2oat,
        .libexpat = libexpat,
        .libjavacore = libjavacore,
        .libopenjdk = libopenjdk,
        .libandroidio = libandroidio,
        .liblz4 = liblz4,
        .libnativebridge = libnativebridge,
        .libnativeloader = libnativeloader,
        .liblzma = liblzma,
        .libunwindstack = libunwindstack,
        .libdexfile = libdexfile,
        .libprofile = libprofile,
        .libart_disassembler = libart_disassembler,
        .libart = libart,
        .libziparchive = libziparchive,
        .libartpalette = libartpalette,
        .libbase = libbase,
        .libartbase = libartbase,
        .libopenjdkjvm = libopenjdkjvm,
        .libjavacrypto = libjavacrypto,
        .libnativehelper = libnativehelper,
        .libsigchain = libsigchain,
        .liblog = liblog,
        .libz = libz,
        .generate_boot_image = generate_boot_image,
    };
}

/// Install every shared library and executable into zig-out/<dest>.
pub fn installAll(b: *std.Build, a: Artifacts, step: *std.Build.Step, dest: []const u8) void {
    step.dependOn(&b.addInstallArtifact(a.dalvikvm, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.dex2oat, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libexpat, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libjavacore, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libopenjdk, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libandroidio, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.liblz4, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libnativebridge, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libnativeloader, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.liblzma, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libunwindstack, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libdexfile, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libprofile, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libart_disassembler, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libart, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libziparchive, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libartpalette, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libbase, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libartbase, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libopenjdkjvm, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libjavacrypto, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libnativehelper, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libsigchain, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.liblog, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.libz, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
    step.dependOn(&b.addInstallArtifact(a.generate_boot_image, .{ .dest_dir = .{ .override = .{ .custom = dest } } }).step);
}
