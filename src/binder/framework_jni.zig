//! Zig JNI backing for the real (reused) AOSP android.os classes — the
//! framework-natives registrar of libbinder_ndk.so. Phase 1: the
//! android.os.SystemProperties natives over the session property area
//! (src/binder/prop_area.zig) and the broker's set_property/property_changed
//! protocol. The launcher calls `azk_register_binder_jni` in every JVM boot
//! path before framework Java runs; `System.load` picks the same registrar up
//! through JNI_OnLoad-captured state (ndk.ensureJavaVm is idempotent).
const std = @import("std");
const art = @import("art");
const jni = art.jni;
const ndk = @import("ndk.zig");
const prop_area = @import("prop_area.zig");

const JNIEnvPtr = [*c]jni.JNIEnv;
const alloc = std.heap.c_allocator;

// ------------------------------------------------------------- property area

var g_props: prop_area.Reader = undefined;
/// 0 = uninitialized, 1 = initializing, 2 = ready, 3 = unavailable.
var g_props_state: std.atomic.Value(u8) = .init(0);

fn props() ?*const prop_area.Reader {
    switch (g_props_state.load(.acquire)) {
        2 => return &g_props,
        3 => return null,
        else => {},
    }
    if (g_props_state.cmpxchgStrong(0, 1, .acq_rel, .acquire) == null) {
        const path = ndk.getenv("AZK_PROP_AREA") orelse {
            g_props_state.store(3, .release);
            return null;
        };
        g_props = prop_area.Reader.open(ndk.frameworkIo(), path) catch {
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

fn readHandle(handle: i64, buf: *[prop_area.value_max]u8) ?[]const u8 {
    if (handle <= 0) return null;
    const r = props() orelse return null;
    return r.getAt(@intCast(handle), buf);
}

/// AOSP GetBoolProperty value table.
fn parseBool(v: []const u8, def: bool) bool {
    const truthy = [_][]const u8{ "1", "y", "yes", "on", "true" };
    const falsy = [_][]const u8{ "0", "n", "no", "off", "false" };
    for (truthy) |t| if (std.mem.eql(u8, v, t)) return true;
    for (falsy) |f| if (std.mem.eql(u8, v, f)) return false;
    return def;
}

/// Full-string parse, base 0 (decimal/0x/0o) like libbase ParseInt; any
/// failure yields the default.
fn parseInt(comptime T: type, v: []const u8, def: T) T {
    if (v.len == 0) return def;
    return std.fmt.parseInt(T, v, 0) catch def;
}

// ------------------------------------------------------------- JNI utilities

fn jstringSlice(env: JNIEnvPtr, s: jni.jstring, buf: []u8) ?[]const u8 {
    if (s == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, s, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, s, chars);
    const text = std.mem.span(@as([*:0]const u8, @ptrCast(chars)));
    if (text.len > buf.len) return null;
    @memcpy(buf[0..text.len], text);
    return buf[0..text.len];
}

fn newString(env: JNIEnvPtr, text: []const u8) jni.jstring {
    var zbuf: [prop_area.value_max + 1]u8 = undefined;
    @memcpy(zbuf[0..text.len], text);
    zbuf[text.len] = 0;
    return env.*.*.NewStringUTF.?(env, @ptrCast(&zbuf));
}

// ------------------------------------------------ SystemProperties natives

fn findByJstring(env: JNIEnvPtr, key: jni.jstring) i64 {
    var kbuf: [prop_area.name_max]u8 = undefined;
    const name = jstringSlice(env, key, &kbuf) orelse return 0;
    const r = props() orelse return 0;
    return @intCast(r.find(name) orelse return 0);
}

/// native_get(String, String): AOSP returns def only for a missing/empty
/// value with a non-null default; otherwise the (possibly empty) value.
fn nativeGetSS(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring, def: jni.jstring) callconv(.c) jni.jstring {
    var vbuf: [prop_area.value_max]u8 = undefined;
    const value = readHandle(findByJstring(env, key), &vbuf) orelse "";
    if (value.len == 0 and def != null) return def;
    return newString(env, value);
}

fn nativeGetIntS(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring, def: jni.jint) callconv(.c) jni.jint {
    return nativeGetIntH(findByJstring(env, key), def);
}

fn nativeGetLongS(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring, def: jni.jlong) callconv(.c) jni.jlong {
    return nativeGetLongH(findByJstring(env, key), def);
}

fn nativeGetBooleanS(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring, def: jni.jboolean) callconv(.c) jni.jboolean {
    return nativeGetBooleanH(findByJstring(env, key), def);
}

fn nativeFind(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring) callconv(.c) jni.jlong {
    return findByJstring(env, key);
}

fn nativeGetH(env: JNIEnvPtr, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jstring {
    var vbuf: [prop_area.value_max]u8 = undefined;
    return newString(env, readHandle(handle, &vbuf) orelse "");
}

// The three handle reads are @CriticalNative: no JNIEnv/jclass parameters.
fn nativeGetIntH(handle: jni.jlong, def: jni.jint) callconv(.c) jni.jint {
    var vbuf: [prop_area.value_max]u8 = undefined;
    const v = readHandle(handle, &vbuf) orelse return def;
    return parseInt(jni.jint, v, def);
}

fn nativeGetLongH(handle: jni.jlong, def: jni.jlong) callconv(.c) jni.jlong {
    var vbuf: [prop_area.value_max]u8 = undefined;
    const v = readHandle(handle, &vbuf) orelse return def;
    return parseInt(jni.jlong, v, def);
}

fn nativeGetBooleanH(handle: jni.jlong, def: jni.jboolean) callconv(.c) jni.jboolean {
    var vbuf: [prop_area.value_max]u8 = undefined;
    const v = readHandle(handle, &vbuf) orelse return def;
    return if (parseBool(v, def != 0)) 1 else 0;
}

fn throwRuntime(env: JNIEnvPtr, message: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, "java/lang/RuntimeException") orelse return;
    _ = env.*.*.ThrowNew.?(env, cls, message);
    env.*.*.DeleteLocalRef.?(env, cls);
}

fn nativeSet(env: JNIEnvPtr, _: jni.jclass, key: jni.jstring, value: jni.jstring) callconv(.c) void {
    var kbuf: [prop_area.name_max]u8 = undefined;
    var vbuf: [prop_area.value_max]u8 = undefined;
    const name = jstringSlice(env, key, &kbuf) orelse {
        throwRuntime(env, "SystemProperties.set: bad key");
        return;
    };
    // A null value clears (AOSP treats null as "").
    const text = if (value == null) "" else jstringSlice(env, value, &vbuf) orelse {
        throwRuntime(env, "SystemProperties.set: value too long");
        return;
    };
    switch (ndk.setSystemProperty(name, text)) {
        .ok => {},
        .denied => throwRuntime(env, "SystemProperties.set: denied by the broker property policy"),
        .failed => throwRuntime(env, "SystemProperties.set: broker unavailable"),
    }
}

// ------------------------------------------------------- change callbacks

var g_sysprops_class: jni.jclass = null;
var g_call_change_callbacks: jni.jmethodID = null;
var g_change_callbacks_armed: std.atomic.Value(bool) = .init(false);

fn callChangeCallbacks(env: JNIEnvPtr) void {
    if (g_sysprops_class == null or g_call_change_callbacks == null) return;
    env.*.*.CallStaticVoidMethod.?(env, g_sysprops_class, g_call_change_callbacks);
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn changeCallbackThread() void {
    const env = ndk.jniEnv();
    if (env == null) return;
    callChangeCallbacks(env);
}

/// Broker property_changed push (ndk.zig read loop). Runs the Java callbacks
/// on a fresh thread: they may transact, and the read loop must stay free to
/// carry the reply.
pub fn onPropertyChanged() void {
    if (!g_change_callbacks_armed.load(.acquire)) return;
    const t = std.Thread.spawn(.{}, changeCallbackThread, .{}) catch return;
    t.detach();
}

fn nativeAddChangeCallback(_: JNIEnvPtr, _: jni.jclass) callconv(.c) void {
    g_change_callbacks_armed.store(true, .release);
}

fn nativeReportSyspropChange(env: JNIEnvPtr, _: jni.jclass) callconv(.c) void {
    // No broker broadcast op: real sets already fan out property_changed; a
    // manual report runs this process's callbacks (the caller is attached).
    callChangeCallbacks(env);
}

// ------------------------------------------------------------- registration

fn method(name: [*:0]const u8, signature: [*:0]const u8, ptr: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(ptr)) };
}

fn registerSystemProperties(env: JNIEnvPtr) !void {
    const cls = ndk.findGlobalClass(env, "android/os/SystemProperties") orelse return error.NoClass;
    g_sysprops_class = cls;
    g_call_change_callbacks = env.*.*.GetStaticMethodID.?(env, cls, "callChangeCallbacks", "()V") orelse
        return error.NoMethod;
    const methods = [_]jni.JNINativeMethod{
        method("native_get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", &nativeGetSS),
        method("native_get_int", "(Ljava/lang/String;I)I", &nativeGetIntS),
        method("native_get_long", "(Ljava/lang/String;J)J", &nativeGetLongS),
        method("native_get_boolean", "(Ljava/lang/String;Z)Z", &nativeGetBooleanS),
        method("native_find", "(Ljava/lang/String;)J", &nativeFind),
        method("native_get", "(J)Ljava/lang/String;", &nativeGetH),
        method("native_get_int", "(JI)I", &nativeGetIntH),
        method("native_get_long", "(JJ)J", &nativeGetLongH),
        method("native_get_boolean", "(JZ)Z", &nativeGetBooleanH),
        method("native_set", "(Ljava/lang/String;Ljava/lang/String;)V", &nativeSet),
        method("native_add_change_callback", "()V", &nativeAddChangeCallback),
        method("native_report_sysprop_change", "()V", &nativeReportSyspropChange),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

/// Register the framework natives backed by this library. Idempotent per
/// process; returns 0 on success.
export fn azk_register_binder_jni(env_opaque: ?*anyopaque) callconv(.c) c_int {
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return -1));
    ndk.ensureJavaVm(env);
    registerSystemProperties(env) catch return -1;
    return 0;
}
