//! Hosted framework native registration and broker-backed property access.
//! Original utility and Binder JNI live in libandroid_platform.so; the
//! remaining host/test entry points are registered here.
const std = @import("std");
const art = @import("art");
const jni = art.jni;
const ndk = @import("ndk.zig");
const prop_area = @import("prop_area.zig");
comptime {
    _ = @import("native_properties.zig");
}

const JNIEnvPtr = [*c]jni.JNIEnv;

// ------------------------------------------------------------- property area

var g_props: prop_area.Reader = undefined;
/// 0 = uninitialized, 1 = initializing, 2 = ready, 3 = unavailable.
var g_props_state: std.atomic.Value(u8) = .init(0);

pub fn props() ?*const prop_area.Reader {
    switch (g_props_state.load(.acquire)) {
        2 => return &g_props,
        3 => return null,
        else => {},
    }
    if (g_props_state.cmpxchgStrong(0, 1, .acq_rel, .acquire) == null) {
        const path = ndk.getenv("AZK_PROP_AREA") orelse {
            std.Io.File.stderr().writeStreamingAll(ndk.frameworkIo(), "azk: SystemProperties has no AZK_PROP_AREA\n") catch {};
            g_props_state.store(3, .release);
            return null;
        };
        g_props = prop_area.Reader.open(ndk.frameworkIo(), path) catch |err| {
            var buffer: [1024]u8 = undefined;
            const message = std.fmt.bufPrint(&buffer, "azk: SystemProperties cannot map {s}: {t}\n", .{ path, err }) catch "azk: SystemProperties cannot map its property area\n";
            std.Io.File.stderr().writeStreamingAll(ndk.frameworkIo(), message) catch {};
            g_props_state.store(3, .release);
            return null;
        };
        g_props_state.store(2, .release);
        return &g_props;
    }
    while (true) switch (g_props_state.load(.acquire)) {
        2 => return &g_props,
        3 => return null,
        else => std.atomic.spinLoopHint(),
    };
}

// Native libbase clients share the same property area as SystemProperties.
// The callback borrows one consistent snapshot for the duration of this call.
pub export fn azk_property_read(name: [*:0]const u8, callback: *const fn (?*anyopaque, [*]const u8, usize) callconv(.c) void, context: ?*anyopaque) callconv(.c) bool {
    const reader = props() orelse return false;
    var buffer: [prop_area.long_value_max]u8 = undefined;
    const value = reader.get(std.mem.span(name), &buffer) orelse return false;
    callback(context, value.ptr, value.len);
    return true;
}

pub export fn azk_property_write(name: [*:0]const u8, value: [*:0]const u8) callconv(.c) bool {
    const key = std.mem.span(name);
    const bytes = std.mem.span(value);
    if (key.len == 0 or key.len >= prop_area.name_max or bytes.len >= prop_area.long_value_max) return false;
    return ndk.setSystemProperty(key, bytes) == .ok;
}

// Stock libutils dispatches callbacks registered by original SystemProperties JNI.
const ReportPropertiesFn = *const fn () callconv(.c) void;
var g_report_properties: std.atomic.Value(?ReportPropertiesFn) = .init(null);
pub fn onPropertyChanged() void {
    @import("native_properties.zig").notifyChanged();
    const report = g_report_properties.load(.acquire) orelse return;
    if (ndk.jniEnv() != null) report();
}

// Loaded only for ART framework registration; standalone NDK clients retain
// no dependency on the native island or VM.
var g_platform_library: std.DynLib = undefined;

// ------------------------------------------------------------- registration

/// Register the framework natives backed by this library. Idempotent per
/// process; returns 0 on success.
export fn azk_register_binder_jni(env_opaque: ?*anyopaque) callconv(.c) c_int {
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return -1));
    ndk.ensureJavaVm(env);
    g_platform_library = std.DynLib.open("libandroid_platform.so") catch return -1;
    const register_utilities = g_platform_library.lookup(
        *const fn (JNIEnvPtr) callconv(.c) c_int,
        "azk_register_utility_jni",
    ) orelse return -1;
    if (register_utilities(env) != 0) return -1;
    g_report_properties.store(g_platform_library.lookup(ReportPropertiesFn, "do_report_sysprop_change") orelse return -1, .release);
    // Original Binder/Parcel/ServiceManager JNI in the native island; the
    // shared NDK runtime retains only the host bootstrap and conversion seam.
    if (ndk.registerBinderFrameworkNatives(env_opaque) != 0) return -1;
    @import("egl_interop_test.zig").register(env) catch return -1;
    const register_startup = g_platform_library.lookup(
        *const fn (JNIEnvPtr) callconv(.c) c_int,
        "azk_register_startup_jni",
    ) orelse return -1;
    if (register_startup(env) != 0) return -1;
    @import("zygote_tagging_jni.zig").register(env) catch return -1;
    @import("ndk_codec_test.zig").register(env, &g_platform_library) catch return -1;
    const register_runtime_init = g_platform_library.lookup(
        *const fn (JNIEnvPtr) callconv(.c) c_int,
        "azk_register_runtime_init",
    ) orelse return -1;
    if (register_runtime_init(env) != 0) return -1;
    return 0;
}

// Shared libbase's POSIX strerror ABI on glibc. Keep it with the common native
// runtime so standalone libgui peers do not depend on the JVM/HWUI island.
extern "c" fn __xpg_strerror_r(c_int, [*]u8, usize) c_int;
pub export fn posix_strerror_r(number: c_int, buffer: [*]u8, length: usize) callconv(.c) c_int {
    return __xpg_strerror_r(number, buffer, length);
}

// libcutils' legacy property ABI uses a caller-provided PROP_VALUE_MAX buffer.
// Typed libbase reads use azk_property_read and retain long read-only values.
pub export fn __system_property_get(name: [*:0]const u8, output: [*]u8) callconv(.c) c_int {
    output[0] = 0;
    const reader = props() orelse return 0;
    var buffer: [prop_area.long_value_max]u8 = undefined;
    const value = reader.get(std.mem.span(name), &buffer) orelse return 0;
    const count = @min(value.len, prop_area.value_max - 1);
    @memcpy(output[0..count], value[0..count]);
    output[count] = 0;
    return @intCast(count);
}
pub export fn __system_property_set(name: [*:0]const u8, value: [*:0]const u8) callconv(.c) c_int {
    return if (azk_property_write(name, value)) 0 else -1;
}
