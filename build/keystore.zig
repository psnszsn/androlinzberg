//! Android's credential stack below LockSettingsService: keystore2, the
//! software ("nonsecure") KeyMint, secure clock and shared-secret HALs, and
//! gatekeeperd over the software gatekeeper HAL. Each is a separate session
//! daemon built from its stock main; none links the native island, whose
//! host OpenSSL would collide with the BoringSSL these daemons link statically.
const std = @import("std");
const boringssl_sources = @import("boringssl_sources.zig");
const LazyPath = std.Build.LazyPath;

/// boringssl_flags + boringssl_defaults for a static, non-FIPS libcrypto
/// (libcrypto_static): the host has no FIPS module boundary.
const boringssl_flags = [_][]const u8{
    "-DBORINGSSL_IMPLEMENTATION", "-DOPENSSL_SMALL", "-DBORINGSSL_ANDROID_SYSTEM",
    "-fvisibility=hidden",        "-Wno-unused-parameter",
};

/// libcrypto_static: BoringSSL's bcm and crypto sources with the x86_64 Linux
/// assembly.
pub fn boringssl(b: *std.Build, target: std.Build.ResolvedTarget, aosp: LazyPath) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    const root = aosp.path(b, "external/boringssl");
    mod.addIncludePath(root.path(b, "src/include"));
    mod.addCSourceFiles(.{ .root = root, .files = &boringssl_sources.crypto, .flags = &(.{"-std=c++17"} ++ boringssl_flags) });
    mod.addCSourceFiles(.{ .root = root, .files = &boringssl_sources.crypto_asm, .flags = &boringssl_flags });
    const lib = b.addLibrary(.{ .name = "bssl_crypto", .root_module = mod });
    b.step("boringssl", "Compile pinned BoringSSL libcrypto for the credential daemons").dependOn(&lib.step);
    return lib;
}

/// A stable AIDL interface version, compiled for Rust and the NDK as Soong's
/// `<name>-rust` and `<name>-ndk`.
pub const Interface = struct {
    /// The versioned aidl_interface, e.g. "android.hardware.security.keymint-V4".
    name: []const u8,
    /// The frozen snapshot (aidl_api/<package>/<version>) or source root.
    root: []const u8,
    version: ?u8 = null,
    hash: ?[]const u8 = null,
    vintf: bool = true,
    /// aidl_interface modules are structured (frozen or `unstable: true`).
    structured: bool = true,
    files: []const []const u8,
    /// Imported interfaces' modules (the include roots and glue crates).
    imports: []const []const u8 = &.{},
};

const security = "hardware/interfaces/security/";

pub const interfaces = [_]Interface{
    .{
        .name = "android.hardware.security.keymint-V4",
        .root = security ++ "keymint/aidl/aidl_api/android.hardware.security.keymint/4",
        .version = 4,
        .hash = "a05c8079586139db45b0762a528cdd9745ad15ce",
        .imports = &.{"android.hardware.security.secureclock-V1"},
        .files = &.{
            "android/hardware/security/keymint/Algorithm.aidl",          "android/hardware/security/keymint/AttestationKey.aidl",
            "android/hardware/security/keymint/BeginResult.aidl",        "android/hardware/security/keymint/BlockMode.aidl",
            "android/hardware/security/keymint/Certificate.aidl",        "android/hardware/security/keymint/Digest.aidl",
            "android/hardware/security/keymint/EcCurve.aidl",            "android/hardware/security/keymint/ErrorCode.aidl",
            "android/hardware/security/keymint/HardwareAuthenticatorType.aidl", "android/hardware/security/keymint/HardwareAuthToken.aidl",
            "android/hardware/security/keymint/IKeyMintDevice.aidl",     "android/hardware/security/keymint/IKeyMintOperation.aidl",
            "android/hardware/security/keymint/KeyCharacteristics.aidl", "android/hardware/security/keymint/KeyCreationResult.aidl",
            "android/hardware/security/keymint/KeyFormat.aidl",          "android/hardware/security/keymint/KeyMintHardwareInfo.aidl",
            "android/hardware/security/keymint/KeyOrigin.aidl",          "android/hardware/security/keymint/KeyParameter.aidl",
            "android/hardware/security/keymint/KeyParameterValue.aidl",  "android/hardware/security/keymint/KeyPurpose.aidl",
            "android/hardware/security/keymint/PaddingMode.aidl",        "android/hardware/security/keymint/SecurityLevel.aidl",
            "android/hardware/security/keymint/Tag.aidl",                "android/hardware/security/keymint/TagType.aidl",
        },
    },
    .{
        .name = "android.hardware.security.secureclock-V1",
        .root = security ++ "secureclock/aidl/aidl_api/android.hardware.security.secureclock/1",
        .version = 1,
        .hash = "cd55ca9963c6a57fa5f2f120a45c6e0c4fafb423",
        .files = &.{
            "android/hardware/security/secureclock/ISecureClock.aidl",
            "android/hardware/security/secureclock/Timestamp.aidl",
            "android/hardware/security/secureclock/TimeStampToken.aidl",
        },
    },
    .{
        .name = "android.hardware.security.sharedsecret-V1",
        .root = security ++ "sharedsecret/aidl/aidl_api/android.hardware.security.sharedsecret/1",
        .version = 1,
        .hash = "aea3745b559d2977f0676952e510fb0547a52140",
        .files = &.{
            "android/hardware/security/sharedsecret/ISharedSecret.aidl",
            "android/hardware/security/sharedsecret/SharedSecretParameters.aidl",
        },
    },
    .{
        .name = "android.hardware.security.rkp-V3",
        .root = security ++ "rkp/aidl/aidl_api/android.hardware.security.rkp/3",
        .version = 3,
        .hash = "7d14edbfab5c490efa407ba55fa80614bb48ae8e",
        .files = &.{
            "android/hardware/security/keymint/DeviceInfo.aidl",     "android/hardware/security/keymint/IRemotelyProvisionedComponent.aidl",
            "android/hardware/security/keymint/MacedPublicKey.aidl", "android/hardware/security/keymint/ProtectedData.aidl",
            "android/hardware/security/keymint/RpcHardwareInfo.aidl",
        },
    },
};

pub const keystore_interfaces = [_]Interface{
    .{
        .name = "android.hardware.gatekeeper-V1",
        .root = "hardware/interfaces/gatekeeper/aidl/aidl_api/android.hardware.gatekeeper/1",
        .version = 1,
        .hash = "d877e8a1608a00fd9068bcd7e69ea480a7ea17f0",
        .imports = &.{"android.hardware.security.keymint-V4"},
        .files = &.{
            "android/hardware/gatekeeper/GatekeeperEnrollResponse.aidl",
            "android/hardware/gatekeeper/GatekeeperVerifyResponse.aidl",
            "android/hardware/gatekeeper/IGatekeeper.aidl",
        },
    },
    .{
        .name = "android.security.authorization",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .imports = &.{ "android.hardware.security.keymint-V4", "android.hardware.security.secureclock-V1" },
        .files = &.{
            "android/security/authorization/AuthorizationTokens.aidl",
            "android/security/authorization/IKeystoreAuthorization.aidl",
            "android/security/authorization/ResponseCode.aidl",
        },
    },
    .{
        .name = "android.system.keystore2-V5",
        .root = "system/hardware/interfaces/keystore2/aidl/aidl_api/android.system.keystore2/5",
        .version = 5,
        .hash = "98d815116c190250e9e5a1d9182cea8126fd0e97",
        .imports = &.{ "android.hardware.security.keymint-V4" },
        .files = &.{
            "android/system/keystore2/AuthenticatorSpec.aidl",
            "android/system/keystore2/Authorization.aidl",
            "android/system/keystore2/CreateOperationResponse.aidl",
            "android/system/keystore2/Domain.aidl",
            "android/system/keystore2/EphemeralStorageKeyResponse.aidl",
            "android/system/keystore2/IKeystoreOperation.aidl",
            "android/system/keystore2/IKeystoreSecurityLevel.aidl",
            "android/system/keystore2/IKeystoreService.aidl",
            "android/system/keystore2/KeyDescriptor.aidl",
            "android/system/keystore2/KeyEntryResponse.aidl",
            "android/system/keystore2/KeyMetadata.aidl",
            "android/system/keystore2/KeyParameters.aidl",
            "android/system/keystore2/KeyPermission.aidl",
            "android/system/keystore2/OperationChallenge.aidl",
            "android/system/keystore2/ResponseCode.aidl",
        },
    },
    .{
        .name = "android.security.apc",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .files = &.{
            "android/security/apc/IConfirmationCallback.aidl",
            "android/security/apc/IProtectedConfirmation.aidl",
            "android/security/apc/ResponseCode.aidl",
        },
    },
    .{
        .name = "android.security.compat",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .imports = &.{ "android.hardware.security.keymint-V4", "android.hardware.security.secureclock-V1", "android.hardware.security.sharedsecret-V1" },
        .files = &.{
            "android/security/compat/IKeystoreCompatService.aidl",
        },
    },
    .{
        .name = "android.security.maintenance",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .imports = &.{ "android.system.keystore2-V5" },
        .files = &.{
            "android/security/maintenance/IKeystoreMaintenance.aidl",
        },
    },
    .{
        .name = "android.security.metrics",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .imports = &.{ "android.system.keystore2-V5" },
        .files = &.{
            "android/security/metrics/Algorithm.aidl",
            "android/security/metrics/AtomID.aidl",
            "android/security/metrics/CrashStats.aidl",
            "android/security/metrics/EcCurve.aidl",
            "android/security/metrics/HardwareAuthenticatorType.aidl",
            "android/security/metrics/IKeystoreMetrics.aidl",
            "android/security/metrics/KeyCreationWithAuthInfo.aidl",
            "android/security/metrics/KeyCreationWithGeneralInfo.aidl",
            "android/security/metrics/KeyCreationWithPurposeAndModesInfo.aidl",
            "android/security/metrics/KeyOperationWithGeneralInfo.aidl",
            "android/security/metrics/KeyOperationWithPurposeAndModesInfo.aidl",
            "android/security/metrics/KeyOrigin.aidl",
            "android/security/metrics/Keystore2AtomWithOverflow.aidl",
            "android/security/metrics/KeystoreAtom.aidl",
            "android/security/metrics/KeystoreAtomPayload.aidl",
            "android/security/metrics/Outcome.aidl",
            "android/security/metrics/Purpose.aidl",
            "android/security/metrics/RkpError.aidl",
            "android/security/metrics/RkpErrorStats.aidl",
            "android/security/metrics/SecurityLevel.aidl",
            "android/security/metrics/Storage.aidl",
            "android/security/metrics/StorageStats.aidl",
        },
    },
    .{
        .name = "android.security.legacykeystore",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .files = &.{
            "android/security/legacykeystore/ILegacyKeystore.aidl",
        },
    },
    .{
        .name = "android.security.postprocessor",
        .root = "system/security/keystore2/aidl",
        .vintf = false,
        .files = &.{
            "android/security/postprocessor/CertificateChain.aidl",
            "android/security/postprocessor/IKeystoreCertificatePostProcessor.aidl",
        },
    },
    .{
        .name = "android.os.permissions_aidl",
        .root = "frameworks/base/core/java",
        .vintf = false,
        .files = &.{
            "android/os/IPermissionController.aidl",
        },
    },
    .{
        .name = "android.security.rkp_aidl",
        .root = "frameworks/base/core/java",
        .vintf = false,
        .files = &.{
            "android/security/rkp/IGetKeyCallback.aidl",
            "android/security/rkp/IGetRegistrationCallback.aidl",
            "android/security/rkp/IRegistration.aidl",
            "android/security/rkp/IRemoteProvisioning.aidl",
            "android/security/rkp/IStoreUpgradedKeyCallback.aidl",
            "android/security/rkp/RemotelyProvisionedKey.aidl",
        },
    },
    .{
        .name = "apex_aidl_interface",
        .root = "system/apex/apexd/aidl",
        .vintf = false,
        .files = &.{
            "android/apex/ApexInfo.aidl",
            "android/apex/ApexInfoList.aidl",
            "android/apex/ApexSessionInfo.aidl",
            "android/apex/ApexSessionParams.aidl",
            "android/apex/CompressedApexInfo.aidl",
            "android/apex/CompressedApexInfoList.aidl",
            "android/apex/IApexService.aidl",
        },
    },
    .{
        .name = "android.security.aaid_aidl",
        .root = "frameworks/base/keystore/aaid/aidl",
        .vintf = false,
        .files = &.{
            "android/security/keystore/IKeyAttestationApplicationIdProvider.aidl",
            "android/security/keystore/KeyAttestationApplicationId.aidl",
            "android/security/keystore/KeyAttestationPackageInfo.aidl",
            "android/security/keystore/Signature.aidl",
        },
    },
    .{
        .name = "android.hardware.confirmationui-V1",
        .root = "hardware/interfaces/confirmationui/aidl/aidl_api/android.hardware.confirmationui/1",
        .version = 1,
        .hash = "4ae5a11c7bcce793ed8db5333483cc4296d3367e",
        .imports = &.{"android.hardware.security.keymint-V4"},
        .files = &.{
            "android/hardware/confirmationui/IConfirmationResultCallback.aidl",
            "android/hardware/confirmationui/IConfirmationUI.aidl",
            "android/hardware/confirmationui/TestModeCommands.aidl",
            "android/hardware/confirmationui/UIOption.aidl",
        },
    },
};

fn interface(name: []const u8) Interface {
    for (keystore_interfaces) |entry| if (std.mem.eql(u8, entry.name, name)) return entry;
    for (interfaces) |entry| if (std.mem.eql(u8, entry.name, name)) return entry;
    std.debug.panic("undeclared AIDL interface: {s}", .{name});
}

/// Soong's crate name for an AIDL Rust module: the package with `_`.
fn aidlCrateName(b: *std.Build, name: []const u8) []const u8 {
    const package = name[0 .. std.mem.indexOf(u8, name, "-V") orelse name.len];
    const crate = b.allocator.dupe(u8, package) catch @panic("oom");
    std.mem.replaceScalar(u8, crate, '.', '_');
    return crate;
}

fn addIncludeRoots(b: *std.Build, run: *std.Build.Step.Run, aosp: LazyPath, entry: Interface) void {
    run.addPrefixedDirectoryArg("-I", aosp.path(b, entry.root));
    for (entry.imports) |name| addIncludeRoots(b, run, aosp, interface(name));
}

/// Generates the crate root of an AIDL Rust module, as aidl_interface does:
/// one `aidl --lang=rust` file per input, joined by aidl_rust_glue.py.
fn rustInterface(b: *std.Build, aosp: LazyPath, aidl_tool: *std.Build.Step.Compile, rust: *Rust, entry: Interface) void {
    const tree = b.addWriteFiles();
    var files: std.ArrayList(LazyPath) = .empty;
    for (entry.files) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArg("--lang=rust");
        if (entry.structured) run.addArg("--structured");
        if (entry.version) |version| run.addArgs(&.{ b.fmt("--version={d}", .{version}), b.fmt("--hash={s}", .{entry.hash.?}) });
        if (entry.vintf) run.addArg("--stability=vintf");
        addIncludeRoots(b, run, aosp, entry);
        run.addArg("-o");
        const output = run.addOutputDirectoryArg("rust-aidl");
        run.addFileArg(aosp.path(b, b.fmt("{s}/{s}", .{ entry.root, file })));
        const rs = b.fmt("{s}.rs", .{file[0 .. file.len - ".aidl".len]});
        files.append(b.allocator, tree.addCopyFile(output.path(b, rs), rs)) catch @panic("oom");
    }
    const glue = b.addSystemCommand(&.{"python3"});
    glue.addFileArg(aosp.path(b, "system/tools/aidl/build/aidl_rust_glue.py"));
    const lib = glue.addOutputFileArg("lib.rs");
    glue.addDirectoryArg(tree.getDirectory());
    for (files.items) |file| glue.addFileArg(file);
    for (entry.imports) |name| glue.addArgs(&.{ "-I", aidlCrateName(b, name) });
    rust.addGenerated(b.fmt("{s}-rust", .{entry.name}), lib);
}

/// An AIDL NDK module's generated headers and sources.
pub const Ndk = struct { headers: LazyPath, sources: LazyPath, files: []const []const u8 };

/// `aidl --lang=ndk` for each input, as aidl_interface's `-ndk` module.
pub fn ndkInterface(b: *std.Build, aosp: LazyPath, aidl_tool: *std.Build.Step.Compile, entry: Interface) Ndk {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    var files: std.ArrayList([]const u8) = .empty;
    for (entry.files) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArg("--lang=ndk");
        if (entry.structured) run.addArg("--structured");
        if (entry.version) |version| run.addArgs(&.{ b.fmt("--version={d}", .{version}), b.fmt("--hash={s}", .{entry.hash.?}) });
        if (entry.vintf) run.addArg("--stability=vintf");
        addIncludeRoots(b, run, aosp, entry);
        run.addArg("-h");
        _ = headers.addCopyDirectory(run.addOutputDirectoryArg("headers"), "", .{});
        run.addArg("-o");
        _ = sources.addCopyDirectory(run.addOutputDirectoryArg("sources"), "", .{});
        run.addFileArg(aosp.path(b, b.fmt("{s}/{s}", .{ entry.root, file })));
        files.append(b.allocator, b.fmt("{s}.cpp", .{file[0 .. file.len - ".aidl".len]})) catch @panic("oom");
    }
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory(), .files = files.items };
}

const Rust = @import("rust.zig").Builder;

/// One Soong rust_bindgen module through tools/rust-bindgen.py: its flags
/// come from the module's pinned Android.bp declaration.
fn bindgen(b: *std.Build, aosp: LazyPath, o: struct {
    module: []const u8,
    blueprint: []const u8,
    wrapper: []const u8,
    language: []const u8 = "c",
    stem: []const u8 = "bindings",
    includes: []const LazyPath = &.{},
    defines: []const []const u8 = &.{},
    bindgen_args: []const []const u8 = &.{},
}) LazyPath {
    const run = b.addSystemCommand(&.{"python3"});
    run.addFileArg(b.path("tools/rust-bindgen.py"));
    run.addArgs(&.{ "--zig", b.graph.zig_exe, "--zig-lib" });
    run.addDirectoryArg(.zig_lib);
    run.addArgs(&.{ "--kind", "declared", "--module", o.module, "--language", o.language, "--stem", o.stem });
    for (o.bindgen_args) |arg| run.addArg(b.fmt("--bindgen-arg={s}", .{arg}));
    run.addArg("--wrapper");
    run.addFileArg(aosp.path(b, o.wrapper));
    run.addArg("--flags");
    run.addFileArg(aosp.path(b, o.blueprint));
    run.addArg("--output");
    const output = run.addOutputDirectoryArg(o.module);
    run.addArgs(&.{ "--", "-D_GNU_SOURCE", "-D__ANDROID_HOST__" });
    run.addArgs(o.defines);
    for (o.includes) |include| {
        run.addArg("-I");
        run.addDirectoryArg(include);
    }
    return output;
}

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    aosp: LazyPath,
    aidl_tool: *std.Build.Step.Compile,
    /// The build's shared AOSP Rust builder (input.zig), so libbinder_rs and
    /// common crates compile once.
    rust: *Rust,
    binder: *std.Build.Step.Compile,
    ndk: *std.Build.Step.Compile,
    broker: *std.Build.Step.Compile,
    /// The host HIDL runtime and its generated base headers, for the HIDL
    /// fallbacks the stock daemons still link.
    hidl: *std.Build.Step.Compile,
    hidl_headers: []const LazyPath,
    /// The pinned protoc, for keystore2's rust_protobuf dependency.
    protoc: *std.Build.Step.Compile,
};

pub const Daemons = struct {
    /// android.hardware.security.keymint-service.nonsecure
    keymint: LazyPath,
    /// android.hardware.gatekeeper-service.nonsecure
    gatekeeper_hal: LazyPath,
    gatekeeperd: LazyPath,
    keystore2: LazyPath,
};

/// android_log-sys links Android's liblog by name. The hosted liblog is part of
/// libbinder.so, whose soname a `liblog.so` copy keeps as the dependency.
fn liblog(b: *std.Build, o: Options) LazyPath {
    const dir = b.addWriteFiles();
    _ = dir.addCopyFile(o.binder.getEmittedBin(), "liblog.so");
    return dir.getDirectory();
}

/// Installed in libexec/androlinzberg beside the system processes.
const daemon_rpaths = [_][]const u8{ "$ORIGIN/../../lib/art", "$ORIGIN/../../lib" };

/// Bionic headers the property bindings parse on a glibc host.
const bionic_header_defines = [_][]const u8{ "-D__BIONIC_AVAILABILITY_GUARD(x)=1", "-D__INTRODUCED_IN(x)=", "-D__unused=__attribute__((__unused__))" };

pub fn build(b: *std.Build, o: Options) Daemons {
    const aosp = o.aosp;
    const rust = o.rust;
    const crypto = boringssl(b, o.target, aosp);
    for (interfaces) |entry| rustInterface(b, aosp, o.aidl_tool, rust, entry);

    // libbssl_sys: bindgen over BoringSSL's wrapper into OUT_DIR/bindgen.rs,
    // with the static-inline wrappers compiled as libbssl_sys_raw_staticfns.
    const bssl_raw = bindgen(b, aosp, .{
        .module = "libbssl_sys_raw",
        .blueprint = "external/boringssl/Android.bp",
        .wrapper = "external/boringssl/src/rust/bssl-sys/wrapper.h",
        .stem = "bindgen",
        .includes = &.{aosp.path(b, "external/boringssl/src/include")},
    });
    rust.addOutDir("libbssl_sys", bssl_raw);
    const staticfns = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .pic = true });
    staticfns.addIncludePath(aosp.path(b, "external/boringssl/src/include"));
    // Soong's include_dirs ["."]: the wrappers include the header by the path
    // bindgen was given, relative to the tree root.
    staticfns.addIncludePath(b.path(""));
    staticfns.addCSourceFile(.{ .file = bssl_raw.path(b, "staticfns.c"), .flags = &.{} });
    const bssl_staticfns = b.addLibrary(.{ .name = "bssl_sys_raw_staticfns", .root_module = staticfns });

    // librustutils' property and cutils bindings over the bionic and
    // libcutils headers.
    const property_headers = b.addWriteFiles();
    _ = property_headers.addCopyFile(aosp.path(b, "bionic/libc/include/sys/system_properties.h"), "sys/system_properties.h");
    rust.addGenerated("libsystem_properties_bindgen_sys", bindgen(b, aosp, .{
        .module = "libsystem_properties_bindgen",
        .blueprint = "system/librustutils/Android.bp",
        .wrapper = "system/librustutils/bindgen/system_properties.h",
        .includes = &.{property_headers.getDirectory()},
        .defines = &bionic_header_defines,
    }).path(b, "bindings.rs"));
    rust.addGenerated("libcutils_bindgen", bindgen(b, aosp, .{
        .module = "libcutils_bindgen",
        .blueprint = "system/librustutils/Android.bp",
        .wrapper = "system/librustutils/bindgen/cutils.h",
        .includes = &.{aosp.path(b, "system/core/libcutils/include")},
    }).path(b, "bindings.rs"));

    const properties = b.addLibrary(.{ .name = "credential_bionic_properties", .root_module = b.createModule(.{
        .root_source_file = b.path("src/native/credential/bionic_properties.zig"),
        .target = o.target,
        .optimize = .ReleaseFast,
        .pic = true,
    }) });
    const linker = rustLinker(b);
    const log_dir = liblog(b, o);
    const keymint = rust.buildBinary("android.hardware.security.keymint-service.nonsecure", .{
        .linker = linker,
        .rpaths = &daemon_rpaths,
        .library_dirs = &.{log_dir},
        .native = &.{ crypto.getEmittedBin(), bssl_staticfns.getEmittedBin(), properties.getEmittedBin(), o.binder.getEmittedBin(), o.ndk.getEmittedBin() },
    });
    b.step("keymint-service", "Build the software KeyMint, secure clock and shared secret HAL service").dependOn(&keymint.compile.step);
    keymintTest(b, o, keymint.file);
    const gatekeepers = gatekeeper(b, o, crypto);
    const keystore2 = keystore2Daemon(b, o, crypto, gatekeepers.keymaster, bssl_staticfns, properties);
    keystore2Test(b, o, keymint.file, keystore2);
    return .{ .keymint = keymint.file, .gatekeeper_hal = gatekeepers.hal, .gatekeeperd = gatekeepers.daemon, .keystore2 = keystore2 };
}

/// The KeyMint daemon with keystore2's view of its three HALs.
fn keymintTest(b: *std.Build, o: Options, service: LazyPath) void {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    const flags: []const []const u8 = &.{ "-std=c++20", "-D__ANDROID_HOST__", "-DBINDER_STABILITY_SUPPORT", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    for ([_][]const u8{ "android.hardware.security.keymint-V4", "android.hardware.security.secureclock-V1", "android.hardware.security.sharedsecret-V1" }) |name| {
        const ndk = ndkInterface(b, o.aosp, o.aidl_tool, interface(name));
        mod.addIncludePath(ndk.headers);
        mod.addCSourceFiles(.{ .root = ndk.sources, .files = ndk.files, .flags = flags });
    }
    for (ndk_includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addCSourceFile(.{ .file = b.path("tests/native/KeyMintServiceTest.cpp"), .flags = flags });
    mod.linkLibrary(o.binder);
    mod.linkLibrary(o.ndk);
    const exe = b.addExecutable(.{ .name = "keymint-service-test", .root_module = mod });
    const run = @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), o.binder, o.ndk, o.broker, "keymint-service", "Run the software KeyMint HAL daemon and verify keystore2's shared-secret, clock and key operations", .standalone, null, &.{}, &.{
        "android.hardware.security.keymint.IKeyMintDevice/default",
        "android.hardware.security.keymint.IRemotelyProvisionedComponent/default",
        "android.hardware.security.secureclock.ISecureClock/default",
        "android.hardware.security.sharedsecret.ISharedSecret/default",
    });
    run.addArg("--daemon");
    run.addFileArg(service);
}

/// NDK Binder headers for generated AIDL clients.
const ndk_includes = [_][]const u8{
    "frameworks/native/libs/binder/ndk/include_cpp",
    "frameworks/native/libs/binder/ndk/include_ndk",
    "frameworks/native/libs/binder/ndk/include_platform",
    "system/logging/liblog/include",
    "system/libbase/include",
};

/// C++ flags for the stock daemons' sources on the glibc host.
fn cppFlags(b: *std.Build) []const []const u8 {
    return b.allocator.dupe([]const u8, &.{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DNDEBUG", "-D__INTRODUCED_IN(x)=", "-DBINDER_STABILITY_SUPPORT", "-DARTZIG_SHARED_PROPERTIES", "-include", b.pathResolve(&.{"src/native/credential/bionic_compat.h"}), "-Wno-deprecated-declarations", "-Wno-nontrivial-memcall", "-Wno-unused-parameter", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) }) catch @panic("oom");
}

/// system/keymaster's portable C++ (libkeymaster_portable, libcppcose_rkp,
/// lib_android_keymaster_keymint_utils) and libcppbor.
const keymaster_portable = [_][]const u8{
    "android_keymaster/android_keymaster.cpp",       "android_keymaster/android_keymaster_messages.cpp",
    "android_keymaster/android_keymaster_utils.cpp", "android_keymaster/authorization_set.cpp",
    "android_keymaster/keymaster_enforcement.cpp",   "android_keymaster/keymaster_tags.cpp",
    "android_keymaster/logger.cpp",                  "android_keymaster/operation.cpp",
    "android_keymaster/operation_table.cpp",         "android_keymaster/pure_soft_secure_key_storage.cpp",
    "android_keymaster/remote_provisioning_utils.cpp", "android_keymaster/serializable.cpp",
    "key_blob_utils/auth_encrypted_key_blob.cpp",    "key_blob_utils/integrity_assured_key_blob.cpp",
    "key_blob_utils/ocb_utils.cpp",                  "key_blob_utils/software_keyblobs.cpp",
    "km_openssl/aes_key.cpp",                        "km_openssl/aes_operation.cpp",
    "km_openssl/asymmetric_key.cpp",                 "km_openssl/asymmetric_key_factory.cpp",
    "km_openssl/attestation_record.cpp",             "km_openssl/attestation_utils.cpp",
    "km_openssl/block_cipher_operation.cpp",         "km_openssl/certificate_utils.cpp",
    "km_openssl/ckdf.cpp",                           "km_openssl/curve25519_key.cpp",
    "km_openssl/ec_key.cpp",                         "km_openssl/ec_key_factory.cpp",
    "km_openssl/ecdh_operation.cpp",                 "km_openssl/ecdsa_operation.cpp",
    "km_openssl/ecies_kem.cpp",                      "km_openssl/hkdf.cpp",
    "km_openssl/hmac.cpp",                           "km_openssl/hmac_key.cpp",
    "km_openssl/hmac_operation.cpp",                 "km_openssl/iso18033kdf.cpp",
    "km_openssl/kdf.cpp",                            "km_openssl/nist_curve_key_exchange.cpp",
    "km_openssl/openssl_err.cpp",                    "km_openssl/openssl_utils.cpp",
    "km_openssl/rsa_key.cpp",                        "km_openssl/rsa_key_factory.cpp",
    "km_openssl/rsa_operation.cpp",                  "km_openssl/software_random_source.cpp",
    "km_openssl/symmetric_key.cpp",                  "km_openssl/triple_des_key.cpp",
    "km_openssl/triple_des_operation.cpp",           "km_openssl/wrapped_key.cpp",
    "cppcose/cppcose.cpp",                           "ng/KeyMintUtils.cpp",
};

const keymaster_includes = [_][]const u8{
    "system/keymaster/include",        "system/keymaster/ng/include", "system/libcppbor/include/cppbor",
    "hardware/libhardware/include",    "system/gatekeeper/include",   "external/scrypt/lib/crypto",
    "external/boringssl/src/include",
};

/// libkeymaster_portable and its static closure (BoringSSL, scrypt, cppbor).
fn keymasterLibrary(b: *std.Build, o: Options, crypto: *std.Build.Step.Compile, headers: []const Ndk) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    for (keymaster_includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for (ndk_includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for (headers) |ndk| mod.addIncludePath(ndk.headers);
    const flags = cppFlags(b);
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/keymaster"), .files = &keymaster_portable, .flags = flags });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "system/keymaster/key_blob_utils/ocb.c"), .flags = &.{"-D_GNU_SOURCE"} });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/libcppbor/src"), .files = &.{ "cppbor.cpp", "cppbor_parse.cpp" }, .flags = flags });
    // libscrypt_sources for x86_64.
    mod.addIncludePath(o.aosp.path(b, "external/scrypt"));
    mod.addIncludePath(o.aosp.path(b, "external/scrypt/lib/util"));
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "external/scrypt/lib/crypto/crypto_scrypt-sse.c"), .flags = &.{ "-DHAVE_CONFIG_H", "-DUSE_OPENSSL_PBKDF2", "-Wno-unused-variable" } });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/gatekeeper"), .files = &.{ "gatekeeper_messages.cpp", "gatekeeper.cpp" }, .flags = flags });
    mod.linkLibrary(crypto);
    return b.addLibrary(.{ .name = "keymaster_portable", .root_module = mod });
}

const Gatekeepers = struct { hal: LazyPath, daemon: LazyPath, keymaster: *std.Build.Step.Compile };

/// The software gatekeeper HAL and stock gatekeeperd.
fn gatekeeper(b: *std.Build, o: Options, crypto: *std.Build.Step.Compile) Gatekeepers {
    const aosp = o.aosp;
    const flags = cppFlags(b);
    const keymint_ndk = ndkInterface(b, aosp, o.aidl_tool, interface("android.hardware.security.keymint-V4"));
    const secureclock_ndk = ndkInterface(b, aosp, o.aidl_tool, interface("android.hardware.security.secureclock-V1"));
    const sharedsecret_ndk = ndkInterface(b, aosp, o.aidl_tool, interface("android.hardware.security.sharedsecret-V1"));
    const gatekeeper_ndk = ndkInterface(b, aosp, o.aidl_tool, interface("android.hardware.gatekeeper-V1"));
    const authorization_ndk = ndkInterface(b, aosp, o.aidl_tool, interface("android.security.authorization"));
    const keymaster = keymasterLibrary(b, o, crypto, &.{ keymint_ndk, secureclock_ndk });

    const hal = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    for (keymaster_includes) |path| hal.addIncludePath(aosp.path(b, path));
    for (ndk_includes) |path| hal.addIncludePath(aosp.path(b, path));
    for ([_]Ndk{ keymint_ndk, secureclock_ndk, sharedsecret_ndk, gatekeeper_ndk }) |ndk| {
        hal.addIncludePath(ndk.headers);
        hal.addCSourceFiles(.{ .root = ndk.sources, .files = ndk.files, .flags = flags });
    }
    hal.addIncludePath(aosp.path(b, "system/core/libcutils/include"));
    hal.addCSourceFiles(.{ .root = aosp.path(b, "hardware/interfaces/gatekeeper/aidl/software"), .files = &.{ "GateKeeper.cpp", "SharedSecret.cpp", "service.cpp" }, .flags = std.mem.concat(b.allocator, []const u8, &.{ flags, &.{"-fvisibility=hidden"} }) catch @panic("oom") });
    hal.linkLibrary(keymaster);
    hal.linkLibrary(o.hidl); // libbase logging
    hal.linkLibrary(o.binder);
    hal.linkLibrary(o.ndk);
    for (daemon_rpaths) |rpath| hal.addRPathSpecial(rpath);
    const hal_exe = b.addExecutable(.{ .name = "android.hardware.gatekeeper-service.nonsecure", .root_module = hal });

    // gatekeeperd: IGateKeeperService (cpp backend) over the HAL, reporting
    // auth tokens to keystore2's authorization service.
    const service_aidl = b.addRunArtifact(o.aidl_tool);
    service_aidl.addArg("--lang=cpp");
    service_aidl.addPrefixedDirectoryArg("-I", aosp.path(b, "system/core/gatekeeperd/binder"));
    service_aidl.addArg("-h");
    const service_headers = service_aidl.addOutputDirectoryArg("headers");
    service_aidl.addArg("-o");
    const service_sources = service_aidl.addOutputDirectoryArg("sources");
    service_aidl.addFileArg(aosp.path(b, "system/core/gatekeeperd/binder/android/service/gatekeeper/IGateKeeperService.aidl"));
    const hidl_headers = hidlCpp(b, o, "c++-headers", "android.hardware.gatekeeper@1.0");
    const hidl_sources = hidlCpp(b, o, "c++-sources", "android.hardware.gatekeeper@1.0");

    const daemon = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    for (keymaster_includes) |path| daemon.addIncludePath(aosp.path(b, path));
    daemon.addIncludePath(b.path("src/native/binder/include"));
    for (@import("binder_cpp.zig").includes) |path| daemon.addIncludePath(aosp.path(b, path));
    for (ndk_includes) |path| daemon.addIncludePath(aosp.path(b, path));
    @import("hidl.zig").configure(b, daemon, aosp, o.hidl_headers);
    daemon.addIncludePath(hidl_headers);
    daemon.addIncludePath(service_headers);
    daemon.addIncludePath(aosp.path(b, "system/core/gatekeeperd/include"));
    daemon.addIncludePath(aosp.path(b, "system/gsid/include"));
    for ([_]Ndk{ keymint_ndk, secureclock_ndk, gatekeeper_ndk, authorization_ndk }) |ndk| {
        daemon.addIncludePath(ndk.headers);
        daemon.addCSourceFiles(.{ .root = ndk.sources, .files = ndk.files, .flags = flags });
    }
    daemon.addCSourceFile(.{ .file = service_sources.path(b, "android/service/gatekeeper/IGateKeeperService.cpp"), .flags = flags });
    daemon.addCSourceFiles(.{ .root = hidl_sources, .files = &.{ "android/hardware/gatekeeper/1.0/GatekeeperAll.cpp", "android/hardware/gatekeeper/1.0/types.cpp" }, .flags = flags });
    daemon.addCSourceFiles(.{ .root = aosp.path(b, "system/core/gatekeeperd"), .files = &.{ "gatekeeperd.cpp", "main.cpp", "GateKeeperResponse.cpp" }, .flags = flags });
    daemon.linkLibrary(keymaster);
    daemon.linkLibrary(o.hidl);
    daemon.linkLibrary(o.binder);
    daemon.linkLibrary(o.ndk);
    for (daemon_rpaths) |rpath| daemon.addRPathSpecial(rpath);
    const daemon_exe = b.addExecutable(.{ .name = "gatekeeperd", .root_module = daemon });
    const step = b.step("gatekeeper", "Build the software gatekeeper HAL and stock gatekeeperd");
    step.dependOn(&hal_exe.step);
    step.dependOn(&daemon_exe.step);
    return .{ .hal = hal_exe.getEmittedBin(), .daemon = daemon_exe.getEmittedBin(), .keymaster = keymaster };
}

/// hidl-gen for one package (Soong's hidl_interface C++ outputs).
fn hidlCpp(b: *std.Build, o: Options, comptime language: []const u8, fqname: []const u8) LazyPath {
    const run = b.addRunArtifact(o.rust.runner);
    run.addArg("--library-dir");
    run.addDirectoryArg(o.aosp.path(b, "prebuilts/build-tools/linux-x86/lib64"));
    run.addFileArg(o.aosp.path(b, "prebuilts/build-tools/linux-x86/bin/hidl-gen"));
    run.addArgs(&.{ "-L" ++ language, "-R" });
    run.addPrefixedDirectoryArg("-randroid.hardware:", o.aosp.path(b, "hardware/interfaces"));
    run.addPrefixedDirectoryArg("-randroid.hidl:", o.aosp.path(b, "system/libhidl/transport"));
    run.addArg("-o");
    const output = run.addOutputDirectoryArg(b.fmt("hidl-{s}", .{language}));
    run.addArg(fqname);
    return output;
}

/// Generated crates keystore2 links: its interfaces, the Rust aconfig flag
/// libraries and aconfig_storage's protos, and its bindgen modules.
fn keystore2Crates(b: *std.Build, o: Options) void {
    const aosp = o.aosp;
    const rust = o.rust;
    for ([_][]const u8{
        "android.system.keystore2-V5",  "android.security.apc",            "android.security.authorization",
        "android.security.compat",      "android.security.maintenance",    "android.security.metrics",
        "android.security.legacykeystore", "android.security.postprocessor", "android.os.permissions_aidl",
        "android.security.rkp_aidl",    "apex_aidl_interface",
    }) |name| rustInterface(b, aosp, o.aidl_tool, rust, interface(name));

    // libprotobuf's copy_protobuf_build_out: the checked-in build.rs output.
    rust.addOutDir("libprotobuf", aosp.path(b, "external/rust/android-crates-io/crates/protobuf/out"));
    // rust_protobuf libaconfig_storage_protos: protoc with protoc-gen-rust,
    // and Soong's mod_<stem>.rs naming each generated module.
    const linker = rustLinker(b);
    const plugin = rust.buildBinary("protoc-gen-rust", .{ .linker = linker, .native = &.{} });
    const protoc = b.addRunArtifact(o.protoc);
    protoc.addPrefixedFileArg("--plugin=protoc-gen-rust=", plugin.file);
    const proto_out = protoc.addPrefixedOutputDirectoryArg("--rust_out=", "aconfig_storage_protos");
    protoc.addPrefixedDirectoryArg("-I", aosp.path(b, "build/make/tools/aconfig/aconfig_storage_file/protos"));
    protoc.addFileArg(aosp.path(b, "build/make/tools/aconfig/aconfig_storage_file/protos/aconfig_storage_metadata.proto"));
    const protos = b.addWriteFiles();
    _ = protos.addCopyFile(proto_out.path(b, "aconfig_storage_metadata.rs"), "aconfig_storage_metadata.rs");
    rust.addGenerated("libaconfig_storage_protos", protos.add("mod_aconfig_storage_protos.rs", "// @Soong generated Source\npub mod aconfig_storage_metadata;"));

    // rust_aconfig_library: `aconfig create-rust-lib` over each package's cache.
    for ([_]struct { []const u8, []const u8 }{
        .{ "libkeystore2_flags_rust", "android.security.keystore2" },
        .{ "libaconfig_android_hardware_biometrics_rust", "android.hardware.biometrics" },
        .{ "libandroid_security_flags_rust", "android.security" },
    }) |entry| {
        const declaration = for (@import("generator_sources.zig").aconfig) |item| {
            if (std.mem.eql(u8, item.package, entry[1])) break item;
        } else unreachable;
        const cache = b.addRunArtifact(rust.runner);
        cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
        cache.addArgs(&.{ "create-cache", "--package", declaration.package, "--container", declaration.container, "--default-permission", "READ_ONLY", "--allow-read-write", "false" });
        for (declaration.declarations) |file| {
            cache.addArg("--declarations");
            cache.addFileArg(aosp.path(b, file));
        }
        for (declaration.values) |file| {
            cache.addArg("--values");
            cache.addFileArg(aosp.path(b, file));
        }
        cache.addArg("--cache");
        const flags_cache = cache.addOutputFileArg("flags.pb");
        const library = b.addRunArtifact(rust.runner);
        library.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
        library.addArgs(&.{ "create-rust-lib", "--cache" });
        library.addFileArg(flags_cache);
        library.addArgs(&.{ "--mode", "production", "--out" });
        rust.addGenerated(entry[0], library.addOutputDirectoryArg("rust").path(b, "src/lib.rs"));
    }

    const keystore2 = "system/security/keystore2/";
    rust.addGenerated("libkeystore2_aaid_bindgen", bindgen(b, aosp, .{
        .module = "libkeystore2_aaid_bindgen",
        .blueprint = keystore2 ++ "aaid/Android.bp",
        .wrapper = keystore2 ++ "aaid/aaid.hpp",
        .language = "c++",
    }).path(b, "bindings.rs"));
    rust.addGenerated("libkeystore2_apc_compat_bindgen", bindgen(b, aosp, .{
        .module = "libkeystore2_apc_compat_bindgen",
        .blueprint = keystore2 ++ "apc_compat/Android.bp",
        .wrapper = keystore2 ++ "apc_compat/apc_compat.hpp",
        .language = "c++",
    }).path(b, "bindings.rs"));
    rust.addGenerated("libkeystore2_crypto_bindgen", bindgen(b, aosp, .{
        .module = "libkeystore2_crypto_bindgen",
        .blueprint = keystore2 ++ "src/crypto/Android.bp",
        .wrapper = keystore2 ++ "src/crypto/crypto.hpp",
        .language = "c++",
        .includes = &.{aosp.path(b, "external/boringssl/src/include")},
    }).path(b, "bindings.rs"));
    rust.addGenerated("liblog_event_list_bindgen", bindgen(b, aosp, .{
        .module = "liblog_event_list_bindgen",
        .blueprint = "system/logging/liblog/Android.bp",
        .wrapper = "system/logging/liblog/rust/liblog_wrapper.h",
        .includes = &.{aosp.path(b, "system/logging/liblog/include")},
    }).path(b, "bindings.rs"));
    rust.addGenerated("libselinux_bindgen", bindgen(b, aosp, .{
        .module = "libselinux_bindgen",
        .blueprint = "external/selinux/libselinux/Android.bp",
        .wrapper = "external/selinux/libselinux/rust/selinux.h",
        .includes = &.{aosp.path(b, "external/selinux/libselinux/include")},
    }).path(b, "bindings.rs"));
    // libsqlite3_bindgen's custom builder (android/build.rs) as bindgen flags.
    rust.addOutDir("liblibsqlite3_sys", bindgen(b, aosp, .{
        .module = "libsqlite3_bindgen",
        .blueprint = "external/rust/android-crates-io/crates/libsqlite3-sys/bindgen_modules.bp.fragment",
        .wrapper = "external/rust/android-crates-io/crates/libsqlite3-sys/wrapper.h",
        .stem = "bindgen",
        .includes = &.{aosp.path(b, sqlite_root)},
        .bindgen_args = &.{
            "--default-macro-constant-type=signed",  "--blocklist-function=sqlite3_vmprintf",
            "--blocklist-function=sqlite3_vsnprintf", "--blocklist-function=sqlite3_str_vappendf",
            "--blocklist-type=va_list",              "--blocklist-type=__builtin_va_list",
            "--blocklist-type=__gnuc_va_list",       "--blocklist-type=__va_list_tag",
            "--blocklist-item=__GNUC_VA_LIST",
        },
    }));
}

/// libsqlite's conditions_default source: sqlite-default links to this release.
const sqlite_root = "external/sqlite/dist/sqlite-autoconf-3440300";

fn rustLinker(b: *std.Build) *std.Build.Step.Compile {
    return b.addExecutable(.{ .name = "rust-linker", .root_module = b.createModule(.{
        .root_source_file = b.path("tools/rust-linker.zig"),
        .target = b.graph.host,
        .optimize = .ReleaseSafe,
    }) });
}

/// keystore2's C++ below its Rust: the crypto, attestation-id, confirmation
/// and HAL-name helpers, km_compat with its HIDL Keymaster and software
/// KeyMint closure, and SQLite.
/// Returns the static archives in link order (Zig doesn't carry archive inputs
/// through a static library).
fn keystore2Native(b: *std.Build, o: Options) []const LazyPath {
    const aosp = o.aosp;
    const flags = cppFlags(b);
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    for (keymaster_includes) |path| mod.addIncludePath(aosp.path(b, path));
    mod.addIncludePath(b.path("src/native/binder/include"));
    for (@import("binder_cpp.zig").includes) |path| mod.addIncludePath(aosp.path(b, path));
    for (ndk_includes) |path| mod.addIncludePath(aosp.path(b, path));
    @import("hidl.zig").configure(b, mod, aosp, o.hidl_headers);
    for ([_][]const u8{
        "android.hardware.security.keymint-V4",  "android.hardware.security.secureclock-V1", "android.hardware.security.sharedsecret-V1",
        "android.hardware.security.rkp-V3",      "android.security.compat",                  "android.system.keystore2-V5",
        "android.hardware.confirmationui-V1",
    }) |name| {
        const ndk = ndkInterface(b, aosp, o.aidl_tool, interface(name));
        mod.addIncludePath(ndk.headers);
        mod.addCSourceFiles(.{ .root = ndk.sources, .files = ndk.files, .flags = flags });
    }
    for ([_]struct { []const u8, []const []const u8 }{
        .{ "android.hardware.keymaster@3.0", &.{ "android/hardware/keymaster/3.0/KeymasterDeviceAll.cpp", "android/hardware/keymaster/3.0/types.cpp" } },
        .{ "android.hardware.keymaster@4.0", &.{ "android/hardware/keymaster/4.0/KeymasterDeviceAll.cpp", "android/hardware/keymaster/4.0/types.cpp" } },
        .{ "android.hardware.keymaster@4.1", &.{ "android/hardware/keymaster/4.1/KeymasterDeviceAll.cpp", "android/hardware/keymaster/4.1/types.cpp" } },
        .{ "android.hardware.confirmationui@1.0", &.{ "android/hardware/confirmationui/1.0/ConfirmationUIAll.cpp", "android/hardware/confirmationui/1.0/ConfirmationResultCallbackAll.cpp", "android/hardware/confirmationui/1.0/types.cpp" } },
    }) |package| {
        mod.addIncludePath(hidlCpp(b, o, "c++-headers", package[0]));
        mod.addCSourceFiles(.{ .root = hidlCpp(b, o, "c++-sources", package[0]), .files = package[1], .flags = flags });
    }
    // android.security.aaid_aidl-cpp
    const aaid = interface("android.security.aaid_aidl");
    const aaid_headers = b.addWriteFiles();
    const aaid_sources = b.addWriteFiles();
    for (aaid.files) |file| {
        const run = b.addRunArtifact(o.aidl_tool);
        run.addArgs(&.{ "--lang=cpp", "--structured" });
        run.addPrefixedDirectoryArg("-I", aosp.path(b, aaid.root));
        run.addArg("-h");
        _ = aaid_headers.addCopyDirectory(run.addOutputDirectoryArg("headers"), "", .{});
        run.addArg("-o");
        _ = aaid_sources.addCopyDirectory(run.addOutputDirectoryArg("sources"), "", .{});
        run.addFileArg(aosp.path(b, b.fmt("{s}/{s}", .{ aaid.root, file })));
        mod.addCSourceFile(.{ .file = aaid_sources.getDirectory().path(b, b.fmt("{s}.cpp", .{file[0 .. file.len - ".aidl".len]})), .flags = flags });
    }
    mod.addIncludePath(aaid_headers.getDirectory());
    const keystore2 = "system/security/keystore2/";
    mod.addIncludePath(aosp.path(b, "system/security/keystore/include"));
    mod.addIncludePath(aosp.path(b, keystore2 ++ "src/crypto/include"));
    mod.addCSourceFile(.{ .file = aosp.path(b, "system/security/keystore/keystore_attestation_id.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, keystore2 ++ "aaid/aaid.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, keystore2 ++ "apc_compat/apc_compat.cpp"), .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, keystore2 ++ "src/crypto"), .files = &.{ "certificate_utils.cpp", "crypto.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, keystore2 ++ "src/km_compat"), .files = &.{ "km_compat.cpp", "km_compat_service.cpp" }, .flags = flags });
    // libkeystore2_hal_names_cpp with its cxxbridge output and the cxx runtime.
    const bridge = @import("command.zig").create(b);
    bridge.addFileArg(o.rust.build("cxxbridge").file);
    bridge.addFileArg(aosp.path(b, keystore2 ++ "src/hal_instance_names/lib.rs"));
    mod.addIncludePath(aosp.path(b, "external/rust/cxx/include"));
    const cxx_headers = b.addWriteFiles();
    _ = cxx_headers.addCopyFile(aosp.path(b, "external/rust/cxx/include/cxx.h"), "rust/cxx.h");
    mod.addIncludePath(cxx_headers.getDirectory());
    const bridge_source = b.addWriteFiles().addCopyFile(bridge.run.captureStdOut(.{}), "hal_names_cxx_generated.cc");
    mod.addCSourceFile(.{ .file = bridge_source, .flags = flags });
    mod.addIncludePath(aosp.path(b, keystore2 ++ "src/hal_instance_names"));
    mod.addCSourceFile(.{ .file = aosp.path(b, keystore2 ++ "src/hal_instance_names/hal_names.cpp"), .flags = flags });
    // libcxxbridge05, as Soong builds it without exceptions.
    mod.addCSourceFile(.{ .file = aosp.path(b, "external/rust/cxx/src/cxx.cc"), .flags = std.mem.concat(b.allocator, []const u8, &.{ flags, &.{"-DRUST_CXX_NO_EXCEPTIONS"} }) catch @panic("oom") });
    // libkeymaster4support, libkeymaster4_1support, libkeymint_support,
    // libkeymint, libpuresoftkeymasterdevice and libsoft_attestation_cert.
    mod.addIncludePath(aosp.path(b, "hardware/interfaces/keymaster/4.0/support/include"));
    mod.addIncludePath(aosp.path(b, "hardware/interfaces/keymaster/4.1/support/include"));
    mod.addIncludePath(aosp.path(b, "hardware/interfaces/security/keymint/support/include"));
    const support = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    support.include_dirs = mod.include_dirs.clone(b.allocator) catch @panic("oom");
    support.addCSourceFiles(.{ .root = aosp.path(b, "hardware/interfaces/keymaster/4.0/support"), .files = &.{ "attestation_record.cpp", "authorization_set.cpp", "key_param_output.cpp", "keymaster_utils.cpp" }, .flags = flags });
    const support41 = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    support41.include_dirs = mod.include_dirs.clone(b.allocator) catch @panic("oom");
    support41.addCSourceFiles(.{ .root = aosp.path(b, "hardware/interfaces/keymaster/4.1/support"), .files = &.{ "attestation_record.cpp", "Keymaster.cpp", "Keymaster3.cpp", "Keymaster4.cpp" }, .flags = flags });
    const keymint_support = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    keymint_support.include_dirs = mod.include_dirs.clone(b.allocator) catch @panic("oom");
    keymint_support.addCSourceFiles(.{ .root = aosp.path(b, "hardware/interfaces/security/keymint/support"), .files = &.{ "attestation_record.cpp", "authorization_set.cpp", "keymint_utils.cpp", "key_param_output.cpp" }, .flags = std.mem.concat(b.allocator, []const u8, &.{ flags, &.{"-DKEYMINT_HAL_V4"} }) catch @panic("oom") });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/keymaster"), .files = &.{
        "android_keymaster/keymaster_configuration.cpp",        "legacy_support/keymaster_passthrough_engine.cpp",
        "legacy_support/keymaster_passthrough_key.cpp",         "legacy_support/keymaster_passthrough_operation.cpp",
        "ng/AndroidKeyMintDevice.cpp",                          "ng/AndroidKeyMintOperation.cpp",
        "ng/AndroidRemotelyProvisionedComponentDevice.cpp",     "ng/AndroidSharedSecret.cpp",
        "ng/AndroidSecureClock.cpp",                            "contexts/soft_attestation_context.cpp",
        "contexts/pure_soft_keymaster_context.cpp",             "contexts/pure_soft_remote_provisioning_context.cpp",
        "contexts/soft_keymaster_logger.cpp",                   "km_openssl/soft_keymaster_enforcement.cpp",
        "contexts/soft_attestation_cert.cpp",
    }, .flags = flags });
    // libsqlite (sqlite-defaults) without the Android ICU extensions keystore2
    // does not use.
    mod.addCSourceFile(.{ .file = aosp.path(b, sqlite_root ++ "/sqlite3.c"), .flags = &.{
        "-DNDEBUG=1",                          "-DHAVE_USLEEP=1",                   "-DSQLITE_HAVE_ISNAN",
        "-DSQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576", "-DSQLITE_THREADSAFE=2",     "-DSQLITE_TEMP_STORE=3",
        "-DSQLITE_POWERSAFE_OVERWRITE=1",      "-DSQLITE_DEFAULT_FILE_FORMAT=4",    "-DSQLITE_DEFAULT_AUTOVACUUM=1",
        "-DSQLITE_ENABLE_DBSTAT_VTAB",         "-DSQLITE_ENABLE_MEMORY_MANAGEMENT=1", "-DSQLITE_ENABLE_FTS3",
        "-DSQLITE_ENABLE_FTS3_BACKWARDS",      "-DSQLITE_ENABLE_FTS4",              "-DSQLITE_OMIT_BUILTIN_TEST",
        "-DSQLITE_OMIT_COMPILEOPTION_DIAGS",   "-DSQLITE_OMIT_LOAD_EXTENSION",      "-DSQLITE_DEFAULT_FILE_PERMISSIONS=0600",
        "-DSQLITE_SECURE_DELETE",              "-DSQLITE_ENABLE_BATCH_ATOMIC_WRITE", "-DSQLITE_DEFAULT_LEGACY_ALTER_TABLE",
        "-DSQLITE_ALLOW_ROWID_IN_VIEW",        "-DSQLITE_ENABLE_BYTECODE_VTAB",     "-Wno-unused-parameter",
        "-DUSE_PREAD64",                       "-DHAVE_MALLOC_H=1",
    } });
    return b.allocator.dupe(LazyPath, &.{
        b.addLibrary(.{ .name = "keystore2_native", .root_module = mod }).getEmittedBin(),
        b.addLibrary(.{ .name = "keymaster4_1_support", .root_module = support41 }).getEmittedBin(),
        b.addLibrary(.{ .name = "keymaster4_support", .root_module = support }).getEmittedBin(),
        b.addLibrary(.{ .name = "keymint_support", .root_module = keymint_support }).getEmittedBin(),
    }) catch @panic("oom");
}

/// keystore2 with its database under the session's /data/misc/keystore.
fn keystore2Daemon(b: *std.Build, o: Options, crypto: *std.Build.Step.Compile, keymaster: *std.Build.Step.Compile, staticfns: *std.Build.Step.Compile, properties: *std.Build.Step.Compile) LazyPath {
    keystore2Crates(b, o);
    const native = keystore2Native(b, o);
    const selinux = b.addObject(.{ .name = "keystore_selinux", .root_module = b.createModule(.{
        .root_source_file = b.path("src/native/credential/keystore_selinux.zig"),
        .target = o.target,
        .optimize = .ReleaseFast,
        .pic = true,
        .link_libc = true,
    }) });
    // Static archives in dependency order for the final link.
    const keystore2 = o.rust.buildBinary("keystore2", .{ .linker = rustLinker(b), .rpaths = &daemon_rpaths, .library_dirs = &.{liblog(b, o)}, .native = std.mem.concat(b.allocator, LazyPath, &.{
        &.{ selinux.getEmittedBin(), properties.getEmittedBin() },
        native,
        &.{ keymaster.getEmittedBin(), staticfns.getEmittedBin(), crypto.getEmittedBin(), o.hidl.getEmittedBin(), o.binder.getEmittedBin(), o.ndk.getEmittedBin() },
    }) catch @panic("oom") });
    b.step("keystore2", "Build stock keystore2 with its native closure").dependOn(&keystore2.compile.step);
    return keystore2.file;
}

/// keystore2 over the KeyMint daemon, checked by an AndroidKeyStore-style
/// client of IKeystoreService.
fn keystore2Test(b: *std.Build, o: Options, keymint: LazyPath, keystore2: LazyPath) void {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    const flags: []const []const u8 = &.{ "-std=c++20", "-D__ANDROID_HOST__", "-DBINDER_STABILITY_SUPPORT", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    for ([_][]const u8{ "android.hardware.security.keymint-V4", "android.hardware.security.secureclock-V1", "android.system.keystore2-V5" }) |name| {
        const ndk = ndkInterface(b, o.aosp, o.aidl_tool, interface(name));
        mod.addIncludePath(ndk.headers);
        mod.addCSourceFiles(.{ .root = ndk.sources, .files = ndk.files, .flags = flags });
    }
    for (ndk_includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addCSourceFile(.{ .file = b.path("tests/native/Keystore2ServiceTest.cpp"), .flags = flags });
    mod.linkLibrary(o.binder);
    mod.linkLibrary(o.ndk);
    const exe = b.addExecutable(.{ .name = "keystore2-service-test", .root_module = mod });
    const run = @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), o.binder, o.ndk, o.broker, "keystore2-service", "Run stock keystore2 over the software KeyMint and verify app key generation, use and deletion", .standalone, null, &.{}, &.{
        "android.hardware.security.keymint.IKeyMintDevice/default",
        "android.hardware.security.keymint.IRemotelyProvisionedComponent/default",
        "android.hardware.security.secureclock.ISecureClock/default",
        "android.hardware.security.sharedsecret.ISharedSecret/default",
        "android.system.keystore2.IKeystoreService/default",
    });
    run.addArg("--daemon");
    run.addFileArg(keymint);
    run.addArg("--daemon-with-dir");
    run.addFileArg(keystore2);
}
