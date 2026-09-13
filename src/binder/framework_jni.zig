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
comptime {
    _ = @import("native_properties.zig");
}
const proc_parser = @import("proc_parser.zig");

const JNIEnvPtr = [*c]jni.JNIEnv;
const alloc = std.heap.c_allocator;

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

fn readHandle(handle: i64, buf: *[prop_area.long_value_max]u8) ?[]const u8 {
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
    var zbuf: [prop_area.long_value_max + 1]u8 = undefined;
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
    var vbuf: [prop_area.long_value_max]u8 = undefined;
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
    var vbuf: [prop_area.long_value_max]u8 = undefined;
    return newString(env, readHandle(handle, &vbuf) orelse "");
}

// The three handle reads are @CriticalNative: no JNIEnv/jclass parameters.
fn nativeGetIntH(handle: jni.jlong, def: jni.jint) callconv(.c) jni.jint {
    var vbuf: [prop_area.long_value_max]u8 = undefined;
    const v = readHandle(handle, &vbuf) orelse return def;
    return parseInt(jni.jint, v, def);
}

fn nativeGetLongH(handle: jni.jlong, def: jni.jlong) callconv(.c) jni.jlong {
    var vbuf: [prop_area.long_value_max]u8 = undefined;
    const v = readHandle(handle, &vbuf) orelse return def;
    return parseInt(jni.jlong, v, def);
}

fn nativeGetBooleanH(handle: jni.jlong, def: jni.jboolean) callconv(.c) jni.jboolean {
    var vbuf: [prop_area.long_value_max]u8 = undefined;
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
    var vbuf: [prop_area.long_value_max]u8 = undefined;
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

fn dispatchChangeCallbacks() void {
    const env = ndk.jniEnv();
    if (env != null) callChangeCallbacks(env);
}

/// Called on an attached Binder worker. The runtime returns admission credit
/// only after these callbacks finish; every notification is delivered.
pub fn onPropertyChanged() void {
    if (!g_change_callbacks_armed.load(.acquire)) return;
    dispatchChangeCallbacks();
}

fn nativeAddChangeCallback(_: JNIEnvPtr, _: jni.jclass) callconv(.c) void {
    g_change_callbacks_armed.store(true, .release);
}

fn nativeReportSyspropChange(env: JNIEnvPtr, _: jni.jclass) callconv(.c) void {
    // No broker broadcast op: real sets already fan out property_changed; a
    // manual report runs this process's callbacks (the caller is attached).
    callChangeCallbacks(env);
}

// ------------------------------------------------ SharedMemory / MemoryFile
//
// AOSP backs these with ashmem (/dev/ashmem), absent on a host. memfd is the
// faithful substitute: an anonymous, sealable, fstat-sizable, dup/SCM_RIGHTS-
// shareable region. nCreate seals SHRINK|GROW so the size is fixed like an
// ashmem region; nSetProt maps a read-only request to F_SEAL_FUTURE_WRITE (the
// closest per-fd write restriction memfd offers).

// Raw Linux syscalls for the memfd region ops (no std.Io equivalent for memfd
// creation/sealing/statx). Same carve-out as ndk.zig: this code is in the
// dlopen'd libbinder_ndk that provides its own Io; fds it hands to Java are
// owned by libcore thereafter.
const linux = std.os.linux;
const MFD_ALLOW_SEALING: c_uint = 0x0002;
const MFD_CLOEXEC: c_uint = 0x0001;
const F_ADD_SEALS: i32 = 1033;
const F_SEAL_SHRINK: usize = 0x0002;
const F_SEAL_GROW: usize = 0x0004;
const F_SEAL_FUTURE_WRITE: usize = 0x0010;
const AT_EMPTY_PATH: u32 = 0x1000;
const PROT_WRITE: jni.jint = 0x2;

/// -errno for a failed syscall (usize result), else 0.
fn errnoOf(rc: usize) i32 {
    const signed: isize = @bitCast(rc);
    return if (signed < 0) @intCast(-signed) else 0;
}

var g_fd_class: jni.jclass = null;
var g_fd_ctor: jni.jmethodID = null;
var g_fd_setint: jni.jmethodID = null;
var g_fd_getint: jni.jmethodID = null;

fn newFileDescriptor(env: JNIEnvPtr, fd: i32) jni.jobject {
    const obj = env.*.*.NewObject.?(env, g_fd_class, g_fd_ctor) orelse return null;
    env.*.*.CallVoidMethod.?(env, obj, g_fd_setint, @as(jni.jint, fd));
    return obj;
}

fn fdInt(env: JNIEnvPtr, fdobj: jni.jobject) i32 {
    return env.*.*.CallIntMethod.?(env, fdobj, g_fd_getint);
}

fn throwErrno(env: JNIEnvPtr, func: [*:0]const u8, errno: c_int) void {
    // android.system.ErrnoException(String, int)
    const cls = env.*.*.FindClass.?(env, "android/system/ErrnoException") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const ctor = env.*.*.GetMethodID.?(env, cls, "<init>", "(Ljava/lang/String;I)V") orelse return;
    const msg = env.*.*.NewStringUTF.?(env, func);
    const ex = env.*.*.NewObject.?(env, cls, ctor, msg, @as(jni.jint, errno));
    _ = env.*.*.Throw.?(env, @ptrCast(ex));
}

fn sharedMemoryCreate(env: JNIEnvPtr, _: jni.jclass, name: jni.jstring, size: jni.jint) callconv(.c) jni.jobject {
    var nbuf: [prop_area.name_max]u8 = undefined;
    const nm = if (name == null) "SharedMemory" else jstringSlice(env, name, &nbuf) orelse "SharedMemory";
    var zname: [prop_area.name_max + 1]u8 = undefined;
    @memcpy(zname[0..nm.len], nm);
    zname[nm.len] = 0;
    const rc = linux.memfd_create(@ptrCast(&zname), MFD_CLOEXEC | MFD_ALLOW_SEALING);
    const cerr = errnoOf(rc);
    if (cerr != 0) {
        throwErrno(env, "SharedMemory_create", cerr);
        return null;
    }
    const fd: i32 = @intCast(rc);
    const terr = errnoOf(linux.ftruncate(fd, size));
    if (terr != 0) {
        _ = linux.close(fd);
        throwErrno(env, "SharedMemory_create", terr);
        return null;
    }
    // Fix the size (ashmem regions cannot be resized after creation).
    _ = linux.fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW);
    return newFileDescriptor(env, fd);
}

fn sharedMemoryGetSize(env: JNIEnvPtr, _: jni.jclass, fdobj: jni.jobject) callconv(.c) jni.jint {
    // statx with AT_EMPTY_PATH queries the fd itself without touching the
    // shared file position (a dup'd/SCM_RIGHTS'd memfd shares its offset).
    var stx: linux.Statx = undefined;
    const rc = linux.statx(fdInt(env, fdobj), "", AT_EMPTY_PATH, .{ .SIZE = true }, &stx);
    if (errnoOf(rc) != 0) return -1;
    return @intCast(stx.size);
}

fn sharedMemorySetProt(env: JNIEnvPtr, _: jni.jclass, fdobj: jni.jobject, prot: jni.jint) callconv(.c) jni.jint {
    // Dropping PROT_WRITE seals future writes; nothing to do when write stays.
    if (prot & PROT_WRITE == 0) {
        if (errnoOf(linux.fcntl(fdInt(env, fdobj), F_ADD_SEALS, F_SEAL_FUTURE_WRITE)) != 0) return -1;
    }
    return 0;
}

fn memoryFilePin(env: JNIEnvPtr, _: jni.jclass, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jboolean {
    // memfd pages are never purged; pin/unpin is a no-op and the region is
    // always "still present" (AOSP returns true = was purged only for ashmem).
    _ = env;
    return jni.JNI_FALSE;
}

fn memoryFileGetSize(env: JNIEnvPtr, cls: jni.jclass, fdobj: jni.jobject) callconv(.c) jni.jint {
    return sharedMemoryGetSize(env, cls, fdobj);
}

fn registerSharedMemory(env: JNIEnvPtr) !void {
    g_fd_class = ndk.findGlobalClass(env, "java/io/FileDescriptor") orelse return error.NoClass;
    g_fd_ctor = env.*.*.GetMethodID.?(env, g_fd_class, "<init>", "()V") orelse return error.NoMethod;
    g_fd_setint = env.*.*.GetMethodID.?(env, g_fd_class, "setInt$", "(I)V") orelse return error.NoMethod;
    g_fd_getint = env.*.*.GetMethodID.?(env, g_fd_class, "getInt$", "()I") orelse return error.NoMethod;

    const sm = env.*.*.FindClass.?(env, "android/os/SharedMemory") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, sm);
    const sm_methods = [_]jni.JNINativeMethod{
        method("nCreate", "(Ljava/lang/String;I)Ljava/io/FileDescriptor;", &sharedMemoryCreate),
        method("nGetSize", "(Ljava/io/FileDescriptor;)I", &sharedMemoryGetSize),
        method("nSetProt", "(Ljava/io/FileDescriptor;I)I", &sharedMemorySetProt),
    };
    if (env.*.*.RegisterNatives.?(env, sm, &sm_methods, sm_methods.len) != 0) return error.RegisterFailed;

    const mf = env.*.*.FindClass.?(env, "android/os/MemoryFile") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, mf);
    const mf_methods = [_]jni.JNINativeMethod{
        method("native_pin", "(Ljava/io/FileDescriptor;Z)Z", &memoryFilePin),
        method("native_get_size", "(Ljava/io/FileDescriptor;)I", &memoryFileGetSize),
    };
    if (env.*.*.RegisterNatives.?(env, mf, &mf_methods, mf_methods.len) != 0) return error.RegisterFailed;
}

// ---------------------------------------------------------- NetworkUtils
// AOSP NetworkUtils.bindSocketToNetworkHandle delegates to android_setsocknetwork,
// which validates the encoded handle and asks netd to attach a fwmark to the socket.
// This host exposes one physical route and has no netd/network namespaces, so a valid
// Android handle is observationally already bound. Preserve validation and errno ABI.
const NETWORK_HANDLE_MAGIC: u64 = 0xcafed00d;

fn networkUtilsBindSocketToNetworkHandle(
    env: JNIEnvPtr,
    _: jni.jclass,
    fdobj: jni.jobject,
    network_handle: jni.jlong,
) callconv(.c) jni.jint {
    const handle: u64 = @bitCast(network_handle);
    if (handle != 0 and @as(u32, @truncate(handle)) != NETWORK_HANDLE_MAGIC) {
        return -@as(jni.jint, @backingInt(linux.E.INVAL));
    }

    // SO_TYPE is a side-effect-free validation that distinguishes a live socket
    // from closed descriptors and non-socket FileDescriptors without changing it.
    const fd = if (fdobj == null) -1 else fdInt(env, fdobj);
    var socket_type: c_int = 0;
    var socket_type_len: linux.socklen_t = @sizeOf(@TypeOf(socket_type));
    const rc = linux.getsockopt(
        fd,
        linux.SOL.SOCKET,
        linux.SO.TYPE,
        @ptrCast(&socket_type),
        &socket_type_len,
    );
    const socket_errno = linux.errno(rc);
    if (socket_errno != .SUCCESS) {
        return -@as(jni.jint, @intCast(@backingInt(socket_errno)));
    }
    return 0;
}

// Pinned NetworkUtils/NetdClient process binding ABI. The host has only its
// default routing namespace. Clearing a binding succeeds; selecting an Android
// netd routing domain fails, and getBoundNetworkForProcess reports NETID_UNSET.
fn networkUtilsBindProcess(_: JNIEnvPtr, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jboolean {
    return if (handle == 0) jni.JNI_TRUE else jni.JNI_FALSE;
}
fn networkUtilsBoundProcess(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jlong {
    return 0;
}
fn networkUtilsBindDns(_: JNIEnvPtr, _: jni.jclass, net_id: jni.jint) callconv(.c) jni.jboolean {
    return if (net_id == 0) jni.JNI_TRUE else jni.JNI_FALSE;
}

fn registerNetworkUtils(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "android/net/NetworkUtils") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("bindProcessToNetworkHandle", "(J)Z", &networkUtilsBindProcess),
        method("getBoundNetworkHandleForProcess", "()J", &networkUtilsBoundProcess),
        method("bindProcessToNetworkForHostResolution", "(I)Z", &networkUtilsBindDns),
        method(
            "bindSocketToNetworkHandle",
            "(Ljava/io/FileDescriptor;J)I",
            &networkUtilsBindSocketToNetworkHandle,
        ),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ------------------------------------------------------------------ Process
// android.os.Process natives, following AOSP core/jni/android_util_Process.cpp
// over the Linux primitives a desktop process has: priorities and signals are
// real; cgroup/cpuset/freezer groups, which need init-created hierarchies and
// root, are accepted as no-ops with the default group reported; /proc parsing
// is implemented for the members the framework reads. Raw std.os.linux
// syscalls: the same dlopen'd-library carve-out as the rest of this file.

const PRIO_PROCESS: usize = 0;
const THREAD_GROUP_DEFAULT: jni.jint = -1;
const SCHED_OTHER: jni.jint = 0;

fn throwNamed(env: JNIEnvPtr, class_name: [*:0]const u8, msg: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, class_name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, msg);
}

/// setpriority(2) on one thread. Lowering works for any user; raising needs a
/// capability this host does not grant, so EPERM/EACCES are ignored (the
/// request is honored as far as the kernel allows), and a missing thread is
/// the IllegalArgumentException AOSP throws.
fn setPrio(env: JNIEnvPtr, tid: jni.jint, prio: jni.jint) void {
    const rc = linux.syscall3(.setpriority, PRIO_PROCESS, @as(usize, @bitCast(@as(isize, tid))), @as(usize, @bitCast(@as(isize, prio))));
    switch (linux.errno(rc)) {
        .SUCCESS, .PERM, .ACCES => {},
        .SRCH => throwNamed(env, "java/lang/IllegalArgumentException", "Given thread does not exist"),
        else => {},
    }
}
fn procSetThreadPriorityTid(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint, prio: jni.jint) callconv(.c) void {
    setPrio(env, tid, prio);
}
fn procSetThreadPriority(env: JNIEnvPtr, _: jni.jclass, prio: jni.jint) callconv(.c) void {
    setPrio(env, @intCast(linux.gettid()), prio);
}
fn procGetThreadPriority(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint) callconv(.c) jni.jint {
    // The raw syscall returns 20 - nice so that failure is unambiguous.
    const rc = linux.syscall2(.getpriority, PRIO_PROCESS, @as(usize, @bitCast(@as(isize, tid))));
    if (linux.errno(rc) != .SUCCESS) {
        throwNamed(env, "java/lang/IllegalArgumentException", "Given thread does not exist");
        return 0;
    }
    return 20 - @as(jni.jint, @intCast(rc));
}
fn procSetCanSelfBackground(_: JNIEnvPtr, _: jni.jclass, _: jni.jboolean) callconv(.c) void {}
fn procSetThreadGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) void {}
fn procSetProcessGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) void {}
fn procSetProcessFrozen(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jboolean) callconv(.c) void {}
fn procGetProcessGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
    return THREAD_GROUP_DEFAULT;
}
fn procCreateProcessGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
    return 0;
}
fn procKillProcessGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
    return 0;
}
fn procSendSignalToProcessGroup(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn procFreezeCgroupUid(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jboolean) callconv(.c) void {}
fn procRemoveAllProcessGroups(_: JNIEnvPtr, _: jni.jclass) callconv(.c) void {}
fn procGetExclusiveCores(env: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jintArray {
    return env.*.*.NewIntArray.?(env, 0);
}
fn procGetSchedAffinity(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint) callconv(.c) jni.jlongArray {
    var set: [16]usize = @splat(0);
    const rc = linux.syscall3(.sched_getaffinity, @as(usize, @bitCast(@as(isize, tid))), @sizeOf(@TypeOf(set)), @intFromPtr(&set));
    if (linux.errno(rc) != .SUCCESS) return env.*.*.NewLongArray.?(env, 0);
    const words: jni.jint = @intCast((rc + 7) / 8);
    const arr = env.*.*.NewLongArray.?(env, words) orelse return null;
    var out: [16]jni.jlong = undefined;
    for (0..@intCast(words)) |i| out[i] = @bitCast(@as(u64, set[i]));
    env.*.*.SetLongArrayRegion.?(env, arr, 0, words, &out);
    return arr;
}
fn procGetThreadScheduler(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint) callconv(.c) jni.jint {
    const rc = linux.sched_getscheduler(tid);
    const err = linux.errno(rc);
    if (err != .SUCCESS) {
        throwSchedulerError(env, err);
        return SCHED_OTHER;
    }
    return @intCast(rc);
}
fn procSetThreadScheduler(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint, policy: jni.jint, prio: jni.jint) callconv(.c) void {
    const param = linux.sched_param{ .priority = prio };
    const rc = linux.sched_setscheduler(tid, @bitCast(policy), &param);
    const err = linux.errno(rc);
    if (err != .SUCCESS) throwSchedulerError(env, err);
}

// Pinned android_util_Process.cpp signalExceptionForPriorityError: a denied
// scheduling request must never look like a successful priority transition.
fn throwSchedulerError(env: JNIEnvPtr, err: linux.E) void {
    switch (err) {
        .PERM, .ACCES => throwNamed(env, "java/lang/SecurityException", "No permission to modify thread scheduler"),
        .INVAL, .SRCH => throwNamed(env, "java/lang/IllegalArgumentException", "Invalid scheduler argument or missing thread"),
        else => throwNamed(env, "java/lang/RuntimeException", "Unknown thread scheduler error"),
    }
}
fn procSetArgV0Native(env: JNIEnvPtr, _: jni.jclass, text: jni.jstring) callconv(.c) void {
    if (text == null) return;
    const chars = env.*.*.GetStringUTFChars.?(env, text, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, text, chars);
    // set_process_name: the comm of the calling thread, truncated to 15 bytes.
    var name: [16]u8 = @splat(0);
    const span = std.mem.span(chars);
    const n = @min(span.len, 15);
    @memcpy(name[0..n], span[0..n]);
    _ = linux.prctl(@backingInt(linux.PR.SET_NAME), @intFromPtr(&name), 0, 0, 0);
}
fn procSetUid(_: JNIEnvPtr, _: jni.jclass, uid: jni.jint) callconv(.c) jni.jint {
    // Process.setUid: immutable authenticated identity in a trusted hosted
    // VM; actual user-namespace children use the original kernel operation.
    if (ndk.hostedUidForPid(linux.getpid())) |assigned| {
        if (assigned != linux.getuid() or assigned != linux.geteuid())
            return if (@as(u32, @bitCast(uid)) == assigned) 0 else -1;
    }
    return if (linux.errno(linux.setuid(@bitCast(uid))) == .SUCCESS) 0 else -1;
}
fn procSetGid(_: JNIEnvPtr, _: jni.jclass, gid: jni.jint) callconv(.c) jni.jint {
    // Process.setGid: immutable authenticated identity in a trusted hosted
    // VM; actual user-namespace children use the original kernel operation.
    if (ndk.hostedUidForPid(linux.getpid())) |assigned| {
        if (assigned != linux.getgid() or assigned != linux.getegid())
            return if (@as(u32, @bitCast(gid)) == assigned) 0 else -1;
    }
    return if (linux.errno(linux.setgid(@bitCast(gid))) == .SUCCESS) 0 else -1;
}
fn procGetUidForName(_: JNIEnvPtr, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jint {
    return -1; // no Android account database on this host
}
fn procGetGidForName(_: JNIEnvPtr, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jint {
    return -1;
}
fn sigOf(sig: jni.jint) ?linux.SIG {
    if (sig < 1 or sig > 64) return null;
    return @fromBackingInt(@intCast(@as(u6, @intCast(sig))));
}
fn procSendSignal(_: JNIEnvPtr, _: jni.jclass, pid: jni.jint, sig: jni.jint) callconv(.c) void {
    const s = sigOf(sig) orelse return;
    if (pid > 0) _ = linux.kill(pid, s);
}
fn signalFailure(env: JNIEnvPtr, rc: usize) void {
    switch (linux.errno(rc)) {
        .SUCCESS => {},
        .SRCH => throwNamed(env, "java/util/NoSuchElementException", "No such process"),
        .PERM => throwNamed(env, "java/lang/SecurityException", "Operation not permitted"),
        else => throwNamed(env, "java/lang/IllegalArgumentException", "Invalid signal or pid"),
    }
}
fn procSendSignalThrows(env: JNIEnvPtr, _: jni.jclass, pid: jni.jint, sig: jni.jint) callconv(.c) void {
    const s = sigOf(sig) orelse {
        throwNamed(env, "java/lang/IllegalArgumentException", "Invalid signal");
        return;
    };
    signalFailure(env, linux.kill(pid, s));
}
fn procSendTgSignalThrows(env: JNIEnvPtr, _: jni.jclass, pid: jni.jint, tgid: jni.jint, sig: jni.jint) callconv(.c) void {
    const s = sigOf(sig) orelse {
        throwNamed(env, "java/lang/IllegalArgumentException", "Invalid signal");
        return;
    };
    signalFailure(env, linux.tgkill(tgid, pid, s));
}
fn procGetElapsedCpuTime(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jlong {
    var ts: linux.timespec = undefined;
    _ = linux.clock_gettime(.PROCESS_CPUTIME_ID, &ts);
    return @as(jni.jlong, ts.sec) * 1000 + @divTrunc(@as(jni.jlong, ts.nsec), 1_000_000);
}
fn procGetFreeMemory(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jlong {
    var si: linux.Sysinfo = undefined;
    if (linux.errno(linux.sysinfo(&si)) != .SUCCESS) return -1;
    return @intCast(@as(u64, si.freeram) * @as(u64, si.mem_unit));
}
fn procGetTotalMemory(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jlong {
    var si: linux.Sysinfo = undefined;
    if (linux.errno(linux.sysinfo(&si)) != .SUCCESS) return -1;
    return @intCast(@as(u64, si.totalram) * @as(u64, si.mem_unit));
}

/// Reads a whole (small) file such as /proc/<pid>/status into an owned buffer.
fn readSmallFile(path: [*:0]const u8) ?[]u8 {
    const io = ndk.frameworkIo();
    const file = std.Io.Dir.cwd().openFile(io, std.mem.span(path), .{}) catch return null;
    defer file.close(io);
    var buf = std.ArrayListUnmanaged(u8).empty;
    defer buf.deinit(alloc);
    var chunk: [4096]u8 = undefined;
    while (true) {
        const n = file.readStreaming(io, &.{&chunk}) catch |err| switch (err) {
            error.EndOfStream => break,
            else => return null,
        };
        if (n == 0) break;
        if (buf.items.len + n >= 64 << 20) return null;
        buf.appendSlice(alloc, chunk[0..n]) catch return null;
    }
    return buf.toOwnedSlice(alloc) catch null;
}

/// Pinned Process.readProcLines returns numeric prefixes without unit scaling,
/// clears requested outputs only after open succeeds, and reads at most 4095 bytes.
fn procReadProcLines(env: JNIEnvPtr, _: jni.jclass, path: jni.jstring, req: jni.jobjectArray, out: jni.jlongArray) callconv(.c) void {
    if (path == null or req == null or out == null) {
        throwNamed(env, "java/lang/NullPointerException", "proc path, fields and outputs are required");
        return;
    }
    const count: usize = @intCast(env.*.*.GetArrayLength.?(env, req));
    if (count > env.*.*.GetArrayLength.?(env, out)) {
        throwNamed(env, "java/lang/IllegalArgumentException", "Array lengths differ");
        return;
    }
    var fields = std.ArrayList([]u8).empty;
    defer {
        for (fields.items) |field| alloc.free(field);
        fields.deinit(alloc);
    }
    for (0..count) |i| {
        const field = env.*.*.GetObjectArrayElement.?(env, req, @intCast(i)) orelse {
            throwNamed(env, "java/lang/NullPointerException", "Element in reqFields");
            return;
        };
        defer env.*.*.DeleteLocalRef.?(env, field);
        const chars = env.*.*.GetStringUTFChars.?(env, field, null) orelse return;
        defer env.*.*.ReleaseStringUTFChars.?(env, field, chars);
        const copy = alloc.dupe(u8, std.mem.span(chars)) catch {
            throwNamed(env, "java/lang/OutOfMemoryError", "proc fields");
            return;
        };
        fields.append(alloc, copy) catch {
            alloc.free(copy);
            throwNamed(env, "java/lang/OutOfMemoryError", "proc fields");
            return;
        };
    }
    const path_chars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, path_chars);
    const io = ndk.frameworkIo();
    const file = std.Io.Dir.cwd().openFile(io, std.mem.span(path_chars), .{}) catch return;
    defer file.close(io);
    const values = alloc.alloc(i64, count) catch {
        throwNamed(env, "java/lang/OutOfMemoryError", "proc values");
        return;
    };
    defer alloc.free(values);
    @memset(values, 0);
    var buffer: [4095]u8 = undefined;
    const n = file.readStreaming(io, &.{&buffer}) catch 0;
    const bytes = buffer[0..n];
    var lines = std.mem.splitScalar(u8, bytes[0 .. std.mem.indexOfScalar(u8, bytes, 0) orelse n], '\n');
    var found: usize = 0;
    while (found < count) {
        const line = lines.next() orelse break;
        for (fields.items, 0..) |field, i| {
            if (!std.mem.startsWith(u8, line, field)) continue;
            const rest = std.mem.trimStart(u8, line[field.len..], " \t");
            var digits: usize = 0;
            while (digits < rest.len and std.ascii.isDigit(rest[digits])) digits += 1;
            values[i] = proc_parser.long(rest[0..digits]);
            found += 1;
            break;
        }
    }
    // procfs presents namespace IDs in the reader's namespace. Stock Process
    // expects the Android identity of the target: translate known live session
    // children at this native boundary, retaining Linux data for other PIDs.
    const filename = std.mem.span(path_chars);
    if (filename.len > 13 and std.mem.startsWith(u8, filename, "/proc/") and std.mem.endsWith(u8, filename, "/status")) {
        const middle = filename[6 .. filename.len - 7];
        const pid = if (std.mem.eql(u8, middle, "self")) std.os.linux.getpid() else std.fmt.parseInt(i32, middle, 10) catch 0;
        for (fields.items) |field| {
            if (!std.mem.eql(u8, field, "Uid:") and !std.mem.eql(u8, field, "Gid:")) continue;
            if (ndk.hostedUidForPid(pid)) |uid| {
                for (fields.items, 0..) |candidate, i| {
                    if (std.mem.eql(u8, candidate, "Uid:") or std.mem.eql(u8, candidate, "Gid:"))
                        values[i] = uid;
                }
            }
            break;
        }
    }
    env.*.*.SetLongArrayRegion.?(env, out, 0, @intCast(count), values.ptr);
}

/// Process.getPids: the numeric entries of a /proc-style directory, padded with
/// -1 (reusing `last` when it is large enough, as AOSP does).
// Pinned android_os_Process_getPids: sorted numeric entries, geometric array
// growth, reuse when capacity suffices, and -1 padding. std.Io owns the directory.
fn procGetPids(env: JNIEnvPtr, _: jni.jclass, path: jni.jstring, last: jni.jintArray) callconv(.c) jni.jintArray {
    if (path == null) {
        throwNamed(env, "java/lang/NullPointerException", "directory is required");
        return null;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, chars);
    const io = ndk.frameworkIo();
    var dir = std.Io.Dir.cwd().openDir(io, std.mem.span(chars), .{ .iterate = true }) catch return null;
    defer dir.close(io);
    var pids: std.ArrayList(jni.jint) = .empty;
    defer pids.deinit(alloc);
    var iterator = dir.iterate();
    while (iterator.next(io) catch null) |entry| {
        if (entry.name.len == 0) continue;
        for (entry.name) |c| {
            if (!std.ascii.isDigit(c)) break;
        } else {
            // strtol's saturated long is narrowed to int by the pinned native.
            pids.append(alloc, @truncate(proc_parser.long(entry.name))) catch {
                throwNamed(env, "java/lang/OutOfMemoryError", "process IDs");
                return null;
            };
        }
    }
    std.mem.sort(jni.jint, pids.items, {}, std.sort.asc(jni.jint));
    const previous: usize = if (last != null) @intCast(env.*.*.GetArrayLength.?(env, last)) else 0;
    var capacity = previous;
    while (capacity < pids.items.len) capacity = if (capacity == 0) 10 else capacity * 2;
    const result = if (capacity == previous) last else env.*.*.NewIntArray.?(env, @intCast(capacity));
    if (result == null) return null;
    const values = env.*.*.GetIntArrayElements.?(env, result, null) orelse return null;
    defer env.*.*.ReleaseIntArrayElements.?(env, result, values, 0);
    @memcpy(values[0..pids.items.len], pids.items);
    @memset(values[pids.items.len..capacity], -1);
    return result;
}
// Follows pinned android_util_Process.cpp parseProcLineArray. The stock native
// file cannot be reused with its bionic process-group dependencies; std.Io owns
// host proc files, and the shared parser preserves partial writes and indices.
fn parseProcBuffer(env: JNIEnvPtr, bytes: []u8, format: jni.jintArray, strings: jni.jobjectArray, longs: jni.jlongArray, floats: jni.jfloatArray) jni.jboolean {
    const nf: usize = @intCast(env.*.*.GetArrayLength.?(env, format));
    const ns: usize = if (strings != null) @intCast(env.*.*.GetArrayLength.?(env, strings)) else 0;
    const nl: usize = if (longs != null) @intCast(env.*.*.GetArrayLength.?(env, longs)) else 0;
    const nr: usize = if (floats != null) @intCast(env.*.*.GetArrayLength.?(env, floats)) else 0;
    const modes = env.*.*.GetIntArrayElements.?(env, format, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseIntArrayElements.?(env, format, modes, jni.JNI_ABORT);
    var parser: proc_parser.Parser = .{ .bytes = bytes };
    var di: usize = 0;
    for (modes[0..nf]) |mode| {
        const field = parser.next(mode) orelse return jni.JNI_FALSE;
        if (mode & 0x7000 == 0) continue;
        if (mode & 0x4000 != 0 and di < nr) {
            const value = proc_parser.float(field.bytes);
            env.*.*.SetFloatArrayRegion.?(env, floats, @intCast(di), 1, &value);
        }
        if (mode & 0x2000 != 0 and di < nl) {
            const value: i64 = if (mode & 0x800 != 0)
                (if (field.bytes.len == 0) @as(i64, 0) else @as(i8, @bitCast(field.bytes[0])))
            else
                proc_parser.long(field.bytes);
            env.*.*.SetLongArrayRegion.?(env, longs, @intCast(di), 1, &value);
        }
        if (mode & 0x1000 != 0 and di < ns) {
            for (field.bytes) |*byte| if (byte.* < 0x20 or byte.* > 0x7e) {
                byte.* = '?';
            };
            const text = alloc.dupeSentinel(u8, field.bytes, 0) catch {
                throwNamed(env, "java/lang/OutOfMemoryError", "proc field");
                return jni.JNI_FALSE;
            };
            defer alloc.free(text);
            const str = env.*.*.NewStringUTF.?(env, text.ptr) orelse return jni.JNI_FALSE;
            defer env.*.*.DeleteLocalRef.?(env, str);
            env.*.*.SetObjectArrayElement.?(env, strings, @intCast(di), str);
        }
        if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
        di += 1;
    }
    return jni.JNI_TRUE;
}
fn procReadProcFile(env: JNIEnvPtr, _: jni.jclass, path: jni.jstring, format: jni.jintArray, strings: jni.jobjectArray, longs: jni.jlongArray, floats: jni.jfloatArray) callconv(.c) jni.jboolean {
    if (path == null or format == null) {
        throwNamed(env, "java/lang/NullPointerException", "file and format are required");
        return jni.JNI_FALSE;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, chars);
    const bytes = readSmallFile(chars) orelse return jni.JNI_FALSE;
    defer alloc.free(bytes);
    return parseProcBuffer(env, bytes, format, strings, longs, floats);
}
fn procParseProcLine(env: JNIEnvPtr, _: jni.jclass, buffer: jni.jbyteArray, start: jni.jint, end: jni.jint, format: jni.jintArray, strings: jni.jobjectArray, longs: jni.jlongArray, floats: jni.jfloatArray) callconv(.c) jni.jboolean {
    if (buffer == null or format == null) {
        throwNamed(env, "java/lang/NullPointerException", "buffer and format are required");
        return jni.JNI_FALSE;
    }
    if (start < 0 or end < start or end > env.*.*.GetArrayLength.?(env, buffer)) {
        throwNamed(env, "java/lang/IndexOutOfBoundsException", "invalid proc byte range");
        return jni.JNI_FALSE;
    }
    const bytes = env.*.*.GetByteArrayElements.?(env, buffer, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseByteArrayElements.?(env, buffer, bytes, 0);
    return parseProcBuffer(env, @as([*]u8, @ptrCast(bytes))[@intCast(start)..@intCast(end)], format, strings, longs, floats);
}
// Pinned android_util_Process.cpp: argv[0] ends at the first NUL or space;
// match whole names, include each PID once, and reject null command elements.
fn procGetPidsForCommands(env: JNIEnvPtr, _: jni.jclass, names: jni.jobjectArray) callconv(.c) jni.jintArray {
    if (names == null) {
        throwNamed(env, "java/lang/NullPointerException", "commandNames");
        return null;
    }
    var commands: std.ArrayList([]u8) = .empty;
    defer {
        for (commands.items) |command| alloc.free(command);
        commands.deinit(alloc);
    }
    const count = env.*.*.GetArrayLength.?(env, names);
    var index: jni.jint = 0;
    while (index < count) : (index += 1) {
        const name = env.*.*.GetObjectArrayElement.?(env, names, index) orelse {
            throwNamed(env, "java/lang/NullPointerException", "Element in commandNames");
            return null;
        };
        defer env.*.*.DeleteLocalRef.?(env, name);
        const chars = env.*.*.GetStringUTFChars.?(env, name, null) orelse return null;
        defer env.*.*.ReleaseStringUTFChars.?(env, name, chars);
        const copy = alloc.dupe(u8, std.mem.span(chars)) catch {
            throwNamed(env, "java/lang/OutOfMemoryError", "command name");
            return null;
        };
        commands.append(alloc, copy) catch {
            alloc.free(copy);
            throwNamed(env, "java/lang/OutOfMemoryError", "commands");
            return null;
        };
    }
    const io = ndk.frameworkIo();
    var dir = std.Io.Dir.cwd().openDir(io, "/proc", .{ .iterate = true }) catch return null;
    defer dir.close(io);
    var pids: std.ArrayList(jni.jint) = .empty;
    defer pids.deinit(alloc);
    var iterator = dir.iterate();
    while (iterator.next(io) catch null) |entry| {
        const pid = std.fmt.parseInt(jni.jint, entry.name, 10) catch continue;
        if (pid <= 0) continue;
        var path: [64]u8 = undefined;
        const filename = std.fmt.bufPrintSentinel(&path, "/proc/{d}/cmdline", .{pid}, 0) catch continue;
        const file = std.Io.Dir.cwd().openFile(io, filename, .{}) catch continue;
        defer file.close(io);
        var bytes: [4095]u8 = undefined;
        const len = file.readStreaming(io, &.{&bytes}) catch |err| switch (err) {
            error.EndOfStream => 0,
            else => continue,
        };
        const command = bytes[0 .. std.mem.indexOfAny(u8, bytes[0..len], "\x00 ") orelse len];
        for (commands.items) |wanted| {
            if (std.mem.eql(u8, wanted, command)) {
                pids.append(alloc, pid) catch {
                    throwNamed(env, "java/lang/OutOfMemoryError", "process IDs");
                    return null;
                };
                break;
            }
        }
    }
    const result = env.*.*.NewIntArray.?(env, @intCast(pids.items.len)) orelse return null;
    if (pids.items.len != 0) env.*.*.SetIntArrayRegion.?(env, result, 0, @intCast(pids.items.len), pids.items.ptr);
    return result;
}
// ProcMemInfo::SmapsOrRollupPss; the host has procfs, without libmeminfo's
// Android daemon dependencies. RSS remains kB; only PSS is returned in bytes.
fn procGetPss(_: JNIEnvPtr, _: jni.jclass, pid: jni.jint) callconv(.c) jni.jlong {
    const io = ndk.frameworkIo();
    const rollup = std.Io.Dir.cwd().openFile(io, "/proc/self/smaps_rollup", .{}) catch null;
    if (rollup) |file| file.close(io);
    var path: [64]u8 = undefined;
    const filename = std.fmt.bufPrintSentinel(&path, "/proc/{d}/{s}", .{ pid, if (rollup != null) "smaps_rollup" else "smaps" }, 0) catch return -1;
    const bytes = readSmallFile(filename) orelse return -1;
    defer alloc.free(bytes);
    var total: i64 = 0;
    var lines = std.mem.splitScalar(u8, bytes, '\n');
    while (lines.next()) |line| {
        if (std.mem.startsWith(u8, line, "Pss:")) total +%= proc_parser.long(line[4..]);
    }
    return total *% 1024;
}
fn procGetRss(env: JNIEnvPtr, _: jni.jclass, pid: jni.jint) callconv(.c) jni.jlongArray {
    var rss: [5]jni.jlong = @splat(0);
    var path: [64]u8 = undefined;
    const filename = std.fmt.bufPrintSentinel(&path, "/proc/{d}/status", .{pid}, 0) catch unreachable;
    if (readSmallFile(filename)) |bytes| {
        defer alloc.free(bytes);
        var lines = std.mem.splitScalar(u8, bytes, '\n');
        while (lines.next()) |line| {
            for ([_][]const u8{ "VmRSS:", "RssFile:", "RssAnon:", "VmSwap:", "RssShmem:" }, 0..) |key, i| {
                if (std.mem.startsWith(u8, line, key)) rss[i] = proc_parser.long(line[key.len..]);
            }
        }
    }
    const result = env.*.*.NewLongArray.?(env, 5) orelse return null;
    env.*.*.SetLongArrayRegion.?(env, result, 0, 5, &rss);
    return result;
}
fn procPidFdOpen(env: JNIEnvPtr, _: jni.jclass, pid: jni.jint, flags: jni.jint) callconv(.c) jni.jint {
    const rc = linux.pidfd_open(pid, @intCast(flags));
    if (linux.errno(rc) != .SUCCESS) {
        throwErrno(env, "pidfd_open", @intCast(errnoOf(rc)));
        return -1;
    }
    return @intCast(rc);
}

fn registerProcess(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "android/os/Process") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("getElapsedCpuTime", "()J", &procGetElapsedCpuTime),
        method("getUidForName", "(Ljava/lang/String;)I", &procGetUidForName),
        method("getGidForName", "(Ljava/lang/String;)I", &procGetGidForName),
        method("setThreadPriority", "(II)V", &procSetThreadPriorityTid),
        method("setThreadPriority", "(I)V", &procSetThreadPriority),
        method("setCanSelfBackground", "(Z)V", &procSetCanSelfBackground),
        method("setThreadGroup", "(II)V", &procSetThreadGroup),
        method("setThreadGroupAndCpuset", "(II)V", &procSetThreadGroup),
        method("setProcessGroup", "(II)V", &procSetProcessGroup),
        method("setProcessFrozen", "(IIZ)V", &procSetProcessFrozen),
        method("getProcessGroup", "(I)I", &procGetProcessGroup),
        method("createProcessGroup", "(II)I", &procCreateProcessGroup),
        method("getExclusiveCores", "()[I", &procGetExclusiveCores),
        method("getSchedAffinity", "(I)[J", &procGetSchedAffinity),
        method("getThreadPriority", "(I)I", &procGetThreadPriority),
        method("getThreadScheduler", "(I)I", &procGetThreadScheduler),
        method("setThreadScheduler", "(III)V", &procSetThreadScheduler),
        method("setArgV0Native", "(Ljava/lang/String;)V", &procSetArgV0Native),
        method("setUid", "(I)I", &procSetUid),
        method("setGid", "(I)I", &procSetGid),
        method("sendSignal", "(II)V", &procSendSignal),
        method("sendSignalThrows", "(II)V", &procSendSignalThrows),
        method("sendTgSignalThrows", "(III)V", &procSendTgSignalThrows),
        method("sendSignalQuiet", "(II)V", &procSendSignal),
        method("getFreeMemory", "()J", &procGetFreeMemory),
        method("getTotalMemory", "()J", &procGetTotalMemory),
        method("readProcLines", "(Ljava/lang/String;[Ljava/lang/String;[J)V", &procReadProcLines),
        method("getPids", "(Ljava/lang/String;[I)[I", &procGetPids),
        method("readProcFile", "(Ljava/lang/String;[I[Ljava/lang/String;[J[F)Z", &procReadProcFile),
        method("parseProcLine", "([BII[I[Ljava/lang/String;[J[F)Z", &procParseProcLine),
        method("getPidsForCommands", "([Ljava/lang/String;)[I", &procGetPidsForCommands),
        method("getPss", "(I)J", &procGetPss),
        method("getRss", "(I)[J", &procGetRss),
        method("killProcessGroup", "(II)I", &procKillProcessGroup),
        method("sendSignalToProcessGroup", "(III)Z", &procSendSignalToProcessGroup),
        method("freezeCgroupUid", "(IZ)V", &procFreezeCgroupUid),
        method("removeAllProcessGroups", "()V", &procRemoveAllProcessGroups),
        method("nativePidFdOpen", "(II)I", &procPidFdOpen),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ------------------------------------------------------------- SystemClock
// android.os.SystemClock natives (core/jni/android_os_SystemClock.cpp ->
// libutils SystemClock.cpp): all @CriticalNative, so no env/jclass. Pure
// clock_gettime syscalls; no descriptor is owned, so std.Io has nothing to hold.

fn clockNanos(id: linux.CLOCK) i64 {
    var ts: linux.timespec = undefined;
    if (linux.errno(linux.clock_gettime(id, &ts)) != .SUCCESS) return 0;
    return @as(i64, ts.sec) * std.time.ns_per_s + @as(i64, ts.nsec);
}
/// libutils elapsedRealtimeNano: CLOCK_BOOTTIME, falling back to MONOTONIC.
fn bootNanos() i64 {
    var ts: linux.timespec = undefined;
    if (linux.errno(linux.clock_gettime(.BOOTTIME, &ts)) == .SUCCESS)
        return @as(i64, ts.sec) * std.time.ns_per_s + @as(i64, ts.nsec);
    return clockNanos(.MONOTONIC);
}
fn clkUptimeMillis() callconv(.c) jni.jlong {
    return @divTrunc(clockNanos(.MONOTONIC), std.time.ns_per_ms);
}
fn clkUptimeNanos() callconv(.c) jni.jlong {
    return clockNanos(.MONOTONIC);
}
fn clkElapsedRealtime() callconv(.c) jni.jlong {
    return @divTrunc(bootNanos(), std.time.ns_per_ms);
}
fn clkElapsedRealtimeNanos() callconv(.c) jni.jlong {
    return bootNanos();
}
fn clkThreadTimeMillis() callconv(.c) jni.jlong {
    return @divTrunc(clockNanos(.THREAD_CPUTIME_ID), std.time.ns_per_ms);
}
fn clkThreadTimeMicro() callconv(.c) jni.jlong {
    return @divTrunc(clockNanos(.THREAD_CPUTIME_ID), std.time.ns_per_us);
}
fn clkCurrentTimeMicro() callconv(.c) jni.jlong {
    return @divTrunc(clockNanos(.REALTIME), std.time.ns_per_us);
}

fn registerSystemClock(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "android/os/SystemClock") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("uptimeMillis", "()J", &clkUptimeMillis),
        method("uptimeNanos", "()J", &clkUptimeNanos),
        method("elapsedRealtime", "()J", &clkElapsedRealtime),
        method("elapsedRealtimeNanos", "()J", &clkElapsedRealtimeNanos),
        method("currentThreadTimeMillis", "()J", &clkThreadTimeMillis),
        method("currentThreadTimeMicro", "()J", &clkThreadTimeMicro),
        method("currentTimeMicro", "()J", &clkCurrentTimeMicro),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ------------------------------------------------------------- Trace
// android.os.Trace natives in the Ravenwood shape (Trace.java
// nativeIsTagEnabled$ravenwood & co.): no tag is ever enabled, so every public
// entry short-circuits before reaching the emitters. atrace/Perfetto have no
// host sink today; this is the seam for one.

fn traceIsTagEnabled(_: jni.jlong) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn traceSetBool(_: JNIEnvPtr, _: jni.jclass, _: jni.jboolean) callconv(.c) void {}
fn traceCounter(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring, _: jni.jlong) callconv(.c) void {}
fn traceBegin(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring) callconv(.c) void {}
fn traceEnd(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong) callconv(.c) void {}
fn traceAsync(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring, _: jni.jint) callconv(.c) void {}
fn traceAsyncTrackBegin(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring, _: jni.jstring, _: jni.jint) callconv(.c) void {}
fn traceAsyncTrackEnd(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring, _: jni.jint) callconv(.c) void {}
fn traceInstantForTrack(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jstring, _: jni.jstring) callconv(.c) void {}

fn registerTrace(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "android/os/Trace") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const S = "Ljava/lang/String;";
    const methods = [_]jni.JNINativeMethod{
        method("nativeIsTagEnabled", "(J)Z", &traceIsTagEnabled), // @CriticalNative
        method("nativeSetAppTracingAllowed", "(Z)V", &traceSetBool),
        method("nativeSetTracingEnabled", "(Z)V", &traceSetBool),
        method("nativeTraceCounter", "(J" ++ S ++ "J)V", &traceCounter),
        method("nativeTraceBegin", "(J" ++ S ++ ")V", &traceBegin),
        method("nativeTraceEnd", "(J)V", &traceEnd),
        method("nativeAsyncTraceBegin", "(J" ++ S ++ "I)V", &traceAsync),
        method("nativeAsyncTraceEnd", "(J" ++ S ++ "I)V", &traceAsync),
        method("nativeAsyncTraceForTrackBegin", "(J" ++ S ++ S ++ "I)V", &traceAsyncTrackBegin),
        method("nativeAsyncTraceForTrackEnd", "(J" ++ S ++ "I)V", &traceAsyncTrackEnd),
        method("nativeInstant", "(J" ++ S ++ ")V", &traceBegin),
        method("nativeInstantForTrack", "(J" ++ S ++ S ++ ")V", &traceInstantForTrack),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ---------------------------------------------------------- Perfetto tracing
// AOSP android.tracing.perfetto delegates to libperfetto_client_experimental.
// The hosted application process has no Perfetto producer socket or tracing
// service, so the native seam registers data sources but leaves them inactive.
// Returning false from iterateBegin preserves DataSource.trace's normal
// disabled fast path; stock Java clients therefore keep their lifecycle and
// reentrancy behavior without manufacturing trace packets that have no sink.

fn perfettoProducerInit(_: JNIEnvPtr, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) void {}
fn perfettoDataSourceCreate(_: JNIEnvPtr, _: jni.jclass, _: jni.jobject, _: jni.jstring) callconv(.c) jni.jlong {
    return 0;
}
fn perfettoDataSourceRegister(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jboolean, _: jni.jboolean) callconv(.c) void {}
fn perfettoDataSourceLongVoid(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong) callconv(.c) void {}
fn perfettoDataSourceFinalizer(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jlong {
    return 0;
}
fn perfettoDataSourceGetInstance(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jint) callconv(.c) jni.jobject {
    return null;
}
fn perfettoDataSourceReleaseInstance(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jint) callconv(.c) void {}
fn perfettoDataSourceIterate(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn perfettoDataSourceIndex(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jint {
    return 0;
}
fn perfettoDataSourceWrite(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jobjectArray) callconv(.c) void {}
fn perfettoStateGet(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jobject {
    return null;
}
fn perfettoStateSet(_: JNIEnvPtr, _: jni.jclass, _: jni.jlong, _: jni.jobject) callconv(.c) void {}

fn registerPerfetto(env: JNIEnvPtr) !void {
    const producer = env.*.*.FindClass.?(env, "android/tracing/perfetto/Producer") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, producer);
    const producer_methods = [_]jni.JNINativeMethod{
        method("nativePerfettoProducerInit", "(II)V", &perfettoProducerInit),
    };
    if (env.*.*.RegisterNatives.?(env, producer, &producer_methods, producer_methods.len) != 0) return error.RegisterFailed;

    const data_source = env.*.*.FindClass.?(env, "android/tracing/perfetto/DataSource") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, data_source);
    const data_source_methods = [_]jni.JNINativeMethod{
        method("nativeRegisterDataSource", "(JIZZ)V", &perfettoDataSourceRegister),
        method("nativeCreate", "(Landroid/tracing/perfetto/DataSource;Ljava/lang/String;)J", &perfettoDataSourceCreate),
        method("nativeFlushAll", "(J)V", &perfettoDataSourceLongVoid),
        method("nativeGetFinalizer", "()J", &perfettoDataSourceFinalizer),
        method("nativeGetPerfettoInstanceLocked", "(JI)Landroid/tracing/perfetto/DataSourceInstance;", &perfettoDataSourceGetInstance),
        method("nativeReleasePerfettoInstanceLocked", "(JI)V", &perfettoDataSourceReleaseInstance),
        method("nativePerfettoDsTraceIterateBegin", "(J)Z", &perfettoDataSourceIterate),
        method("nativePerfettoDsTraceIterateNext", "(J)Z", &perfettoDataSourceIterate),
        method("nativePerfettoDsTraceIterateBreak", "(J)V", &perfettoDataSourceLongVoid),
        method("nativeGetPerfettoDsInstanceIndex", "(J)I", &perfettoDataSourceIndex),
        method("nativeWritePackets", "(J[[B)V", &perfettoDataSourceWrite),
    };
    if (env.*.*.RegisterNatives.?(env, data_source, &data_source_methods, data_source_methods.len) != 0) return error.RegisterFailed;

    const tracing_context = env.*.*.FindClass.?(env, "android/tracing/perfetto/TracingContext") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, tracing_context);
    const tracing_context_methods = [_]jni.JNINativeMethod{
        method("nativeGetCustomTls", "(J)Ljava/lang/Object;", &perfettoStateGet),
        method("nativeSetCustomTls", "(JLjava/lang/Object;)V", &perfettoStateSet),
        method("nativeGetIncrementalState", "(J)Ljava/lang/Object;", &perfettoStateGet),
        method("nativeSetIncrementalState", "(JLjava/lang/Object;)V", &perfettoStateSet),
    };
    if (env.*.*.RegisterNatives.?(env, tracing_context, &tracing_context_methods, tracing_context_methods.len) != 0) return error.RegisterFailed;
}

// ---------------------------------------------------- ClassLoaderFactory
// AOSP com_android_internal_os_ClassLoaderFactory.cpp asks libnativeloader for
// a linker namespace per app class loader. The bionic bridge on this host has
// no namespaces: every loader searches its librarySearchPath directly, so the
// native returns null ("no error"), the value AOSP callers treat as success.
fn classLoaderCreateNamespace(
    _: JNIEnvPtr,
    _: jni.jclass,
    _: jni.jobject,
    _: jni.jint,
    _: jni.jstring,
    _: jni.jstring,
    _: jni.jboolean,
    _: jni.jstring,
    _: jni.jstring,
) callconv(.c) jni.jstring {
    return null;
}

fn registerClassLoaderFactory(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "com/android/internal/os/ClassLoaderFactory") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const S = "Ljava/lang/String;";
    const methods = [_]jni.JNINativeMethod{
        method(
            "createClassloaderNamespace",
            "(Ljava/lang/ClassLoader;I" ++ S ++ S ++ "Z" ++ S ++ S ++ ")" ++ S,
            &classLoaderCreateNamespace,
        ),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ----------------------------------------------------- binary XML utilities
// AOSP CharsetUtils uses JNI's modified-UTF codec to read and write directly
// at Runtime.addressOf() pointers. The caller owns and bounds the backing byte
// array; the native preserves the Android contract that one trailing byte is
// reserved for the temporary NUL terminator.

fn charsetToModifiedUtf8(
    env: JNIEnvPtr,
    _: jni.jclass,
    src: jni.jstring,
    src_len: jni.jint,
    dest: jni.jlong,
    dest_off: jni.jint,
    dest_len: jni.jint,
) callconv(.c) jni.jint {
    const encoded_len = env.*.*.GetStringUTFLength.?(env, src);
    if (dest_off < 0 or dest_len < 0 or encoded_len < 0 or
        @as(i64, dest_off) + @as(i64, encoded_len) >= @as(i64, dest_len))
    {
        return -encoded_len;
    }
    const address: usize = @intCast(@as(u64, @bitCast(dest)));
    const bytes: [*]u8 = @ptrFromInt(address);
    env.*.*.GetStringUTFRegion.?(env, src, 0, src_len, @ptrCast(bytes + @as(usize, @intCast(dest_off))));
    return encoded_len;
}

fn charsetFromModifiedUtf8(
    env: JNIEnvPtr,
    _: jni.jclass,
    src: jni.jlong,
    src_off: jni.jint,
    src_len: jni.jint,
) callconv(.c) jni.jstring {
    const address: usize = @intCast(@as(u64, @bitCast(src)));
    const bytes: [*]u8 = @ptrFromInt(address);
    const end: usize = @intCast(src_off + src_len);
    const saved = bytes[end];
    bytes[end] = 0;
    defer bytes[end] = saved;
    return env.*.*.NewStringUTF.?(env, @ptrCast(bytes + @as(usize, @intCast(src_off))));
}

fn registerCharsetUtils(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "android/util/CharsetUtils") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("toModifiedUtf8Bytes", "(Ljava/lang/String;IJII)I", &charsetToModifiedUtf8),
        method("fromModifiedUtf8Bytes", "(JII)Ljava/lang/String;", &charsetFromModifiedUtf8),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// The Android implementation shells out to /system/bin/idmap2. There is no
// Android system partition or idmap daemon on the host, which is exactly its
// documented failure branch: return null and let OverlayConfig skip immutable
// framework overlay idmaps.
fn overlayCreateIdmap(
    _: JNIEnvPtr,
    _: jni.jclass,
    _: jni.jstring,
    _: jni.jobjectArray,
    _: jni.jobjectArray,
    _: jni.jboolean,
) callconv(.c) jni.jobjectArray {
    return null;
}

fn registerOverlayConfig(env: JNIEnvPtr) !void {
    const cls = env.*.*.FindClass.?(env, "com/android/internal/content/om/OverlayConfig") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("createIdmap", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Z)[Ljava/lang/String;", &overlayCreateIdmap),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

// ---------------------------------------------------------- android.util.Log
// Host sink for the verbatim AOSP Log class. Android normally writes through
// logd; the island's AOSP liblog writes the same record to stderr, while the
// diagnostic callback preserves the structured issue journal until that test
// seam moves out of android.*.
const LogIsLoggableFn = *const fn (priority: c_int, tag: [*c]const u8, default_priority: c_int) callconv(.c) c_int;
const LogBufWriteFn = *const fn (buffer_id: c_int, priority: c_int, tag: [*c]const u8, message: [*c]const u8) callconv(.c) c_int;
const LogBinaryWriteFn = *const fn (tag: i32, payload: ?*const anyopaque, len: usize) callconv(.c) c_int;
const LogTypedWriteFn = *const fn (tag: i32, value_type: u8, payload: ?*const anyopaque, len: usize) callconv(.c) c_int;
const LogStringWriteFn = *const fn (tag: i32, payload: [*:0]const u8) callconv(.c) c_int;
const LogSecurityEnabledFn = *const fn () callconv(.c) c_int;

const android_log_info: c_int = 4;
const log_id_max: jni.jint = 8;
const logger_entry_max_payload: jni.jint = 4068;

var g_log_diagnostics: jni.jclass = null;
var g_record_log: jni.jmethodID = null;
var g_platform_library: std.DynLib = undefined;
var g_log_is_loggable: LogIsLoggableFn = undefined;
var g_log_buf_write: LogBufWriteFn = undefined;
var g_log_binary_write: LogBinaryWriteFn = undefined;
var g_log_typed_write: LogTypedWriteFn = undefined;
var g_log_string_write: LogStringWriteFn = undefined;
var g_log_security_enabled: LogSecurityEnabledFn = undefined;
var g_log_security_write: LogBinaryWriteFn = undefined;

fn logIsLoggable(env: JNIEnvPtr, _: jni.jclass, tag_object: jni.jstring, level: jni.jint) callconv(.c) jni.jboolean {
    if (tag_object == null) return jni.JNI_FALSE;
    const tag = env.*.*.GetStringUTFChars.?(env, tag_object, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseStringUTFChars.?(env, tag_object, tag);
    return if (g_log_is_loggable(level, tag, android_log_info) != 0) jni.JNI_TRUE else jni.JNI_FALSE;
}

fn logPrintln(
    env: JNIEnvPtr,
    _: jni.jclass,
    buffer_id: jni.jint,
    priority: jni.jint,
    tag_object: jni.jstring,
    message_object: jni.jstring,
) callconv(.c) jni.jint {
    if (message_object == null) {
        throwNamed(env, "java/lang/NullPointerException", "println needs a message");
        return -1;
    }
    if (buffer_id < 0 or buffer_id >= log_id_max) {
        throwNamed(env, "java/lang/NullPointerException", "bad bufID");
        return -1;
    }

    const tag = if (tag_object == null)
        null
    else
        env.*.*.GetStringUTFChars.?(env, tag_object, null) orelse return -1;
    defer if (tag != null) env.*.*.ReleaseStringUTFChars.?(env, tag_object, tag);

    const message = env.*.*.GetStringUTFChars.?(env, message_object, null) orelse return -1;
    defer env.*.*.ReleaseStringUTFChars.?(env, message_object, message);

    env.*.*.CallStaticVoidMethod.?(env, g_log_diagnostics, g_record_log, priority, tag_object, message_object);
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) env.*.*.ExceptionClear.?(env);
    return g_log_buf_write(buffer_id, priority, tag, message);
}

fn logLoggerEntryMaxPayload(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jint {
    return logger_entry_max_payload;
}

// AOSP android_util_EventLog writes the standard binary event payload through liblog. The
// hosted runtime has no logd reader, so reads are a valid empty nonblocking snapshot; writes
// still use the platform liblog sink and retain Android's scalar/list encoding and return value.
const event_type_int: u8 = 0;
const event_type_long: u8 = 1;
const event_type_float: u8 = 4;

fn eventWriteInt(_: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value_arg: jni.jint) callconv(.c) jni.jint {
    var value = value_arg;
    return g_log_typed_write(tag, event_type_int, &value, @sizeOf(jni.jint));
}

fn eventWriteLong(_: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value_arg: jni.jlong) callconv(.c) jni.jint {
    var value = value_arg;
    return g_log_typed_write(tag, event_type_long, &value, @sizeOf(jni.jlong));
}

fn eventWriteFloat(_: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value_arg: jni.jfloat) callconv(.c) jni.jint {
    var value = value_arg;
    return g_log_typed_write(tag, event_type_float, &value, @sizeOf(jni.jfloat));
}

fn eventWriteString(env: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value: jni.jstring) callconv(.c) jni.jint {
    if (value == null) return g_log_string_write(tag, "NULL");
    const chars = env.*.*.GetStringUTFChars.?(env, value, null) orelse return -1;
    defer env.*.*.ReleaseStringUTFChars.?(env, value, chars);
    return g_log_string_write(tag, @ptrCast(chars));
}

fn eventWriteArray(env: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value: jni.jobjectArray) callconv(.c) jni.jint {
    if (value == null) return g_log_string_write(tag, "[NULL]");
    // Reuse the pinned EventLog.Event encoder so nested payload validation, byte order, the
    // 255-element limit, and IllegalArgumentException behavior stay in the stock Java class.
    const event_cls = env.*.*.FindClass.?(env, "android/util/EventLog$Event") orelse return -1;
    defer env.*.*.DeleteLocalRef.?(env, event_cls);
    const encode = env.*.*.GetStaticMethodID.?(
        env,
        event_cls,
        "encodeObject",
        "(Ljava/lang/Object;)[B",
    ) orelse return -1;
    const bytes_object = env.*.*.CallStaticObjectMethod.?(env, event_cls, encode, value);
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE or bytes_object == null) return -1;
    const bytes: jni.jbyteArray = @ptrCast(bytes_object);
    defer env.*.*.DeleteLocalRef.?(env, bytes);
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, bytes));
    const payload = env.*.*.GetByteArrayElements.?(env, bytes, null) orelse return -1;
    defer env.*.*.ReleaseByteArrayElements.?(env, bytes, payload, 2); // JNI_ABORT
    return g_log_binary_write(tag, payload, len);
}

fn eventRead(env: JNIEnvPtr, _: jni.jclass, tags: jni.jintArray, output: jni.jobject) callconv(.c) void {
    if (tags == null or output == null) {
        throwNamed(env, "java/lang/NullPointerException", "tags and output must be non-null");
    }
}

fn eventReadOnWrapping(env: JNIEnvPtr, cls: jni.jclass, tags: jni.jintArray, _: jni.jlong, output: jni.jobject) callconv(.c) void {
    eventRead(env, cls, tags, output);
}

fn securityLoggingEnabled(_: JNIEnvPtr, _: jni.jclass) callconv(.c) jni.jboolean {
    return if (g_log_security_enabled() != 0) jni.JNI_TRUE else jni.JNI_FALSE;
}

fn securityWriteArray(env: JNIEnvPtr, _: jni.jclass, tag: jni.jint, value: jni.jobjectArray) callconv(.c) jni.jint {
    if (value == null) return g_log_security_write(tag, &[_]u8{ 2, 6, 0, 0, 0, '[', 'N', 'U', 'L', 'L', ']' }, 11);
    const event_cls = env.*.*.FindClass.?(env, "android/util/EventLog$Event") orelse return -1;
    defer env.*.*.DeleteLocalRef.?(env, event_cls);
    const encode = env.*.*.GetStaticMethodID.?(env, event_cls, "encodeObject", "(Ljava/lang/Object;)[B") orelse return -1;
    const bytes_object = env.*.*.CallStaticObjectMethod.?(env, event_cls, encode, value);
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE or bytes_object == null) return -1;
    const bytes: jni.jbyteArray = @ptrCast(bytes_object);
    defer env.*.*.DeleteLocalRef.?(env, bytes);
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, bytes));
    const payload = env.*.*.GetByteArrayElements.?(env, bytes, null) orelse return -1;
    defer env.*.*.ReleaseByteArrayElements.?(env, bytes, payload, 2); // JNI_ABORT
    return g_log_security_write(tag, payload, len);
}

fn securityRead(env: JNIEnvPtr, _: jni.jclass, output: jni.jobject) callconv(.c) void {
    if (output == null) throwNamed(env, "java/lang/NullPointerException", "output must be non-null");
}

fn securityReadSince(env: JNIEnvPtr, cls: jni.jclass, _: jni.jlong, output: jni.jobject) callconv(.c) void {
    securityRead(env, cls, output);
}

fn registerSecurityLog(env: JNIEnvPtr) !void {
    g_log_security_enabled = g_platform_library.lookup(LogSecurityEnabledFn, "__android_log_security") orelse
        return error.NoMethod;
    g_log_security_write = g_platform_library.lookup(LogBinaryWriteFn, "__android_log_security_bwrite") orelse
        return error.NoMethod;

    const cls = env.*.*.FindClass.?(env, "android/app/admin/SecurityLog") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("isLoggingEnabled", "()Z", &securityLoggingEnabled),
        method("writeEvent", "(I[Ljava/lang/Object;)I", &securityWriteArray),
        method("readEvents", "(Ljava/util/Collection;)V", &securityRead),
        method("readEventsSince", "(JLjava/util/Collection;)V", &securityReadSince),
        method("readPreviousEvents", "(Ljava/util/Collection;)V", &securityRead),
        method("readEventsOnWrapping", "(JLjava/util/Collection;)V", &securityReadSince),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

fn registerEventLog(env: JNIEnvPtr) !void {
    g_log_binary_write = g_platform_library.lookup(LogBinaryWriteFn, "__android_log_bwrite") orelse
        return error.NoMethod;
    g_log_typed_write = g_platform_library.lookup(LogTypedWriteFn, "__android_log_btwrite") orelse
        return error.NoMethod;
    g_log_string_write = g_platform_library.lookup(LogStringWriteFn, "__android_log_bswrite") orelse
        return error.NoMethod;

    const cls = env.*.*.FindClass.?(env, "android/util/EventLog") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("writeEvent", "(II)I", &eventWriteInt),
        method("writeEvent", "(IJ)I", &eventWriteLong),
        method("writeEvent", "(IF)I", &eventWriteFloat),
        method("writeEvent", "(ILjava/lang/String;)I", &eventWriteString),
        method("writeEvent", "(I[Ljava/lang/Object;)I", &eventWriteArray),
        method("readEvents", "([ILjava/util/Collection;)V", &eventRead),
        method("readEventsOnWrapping", "([IJLjava/util/Collection;)V", &eventReadOnWrapping),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

fn registerLog(env: JNIEnvPtr) !void {
    // libbinder_ndk is also used by standalone native clients. Resolve liblog
    // only when an ART process registers framework natives; the launcher has
    // already loaded the platform island by this point.
    g_platform_library = try std.DynLib.open("libandroid_platform.so");
    g_log_is_loggable = g_platform_library.lookup(LogIsLoggableFn, "__android_log_is_loggable") orelse
        return error.NoMethod;
    g_log_buf_write = g_platform_library.lookup(LogBufWriteFn, "__android_log_buf_write") orelse
        return error.NoMethod;

    const diagnostics = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, diagnostics);
    g_log_diagnostics = @ptrCast(env.*.*.NewGlobalRef.?(env, diagnostics) orelse return error.NoClass);
    g_record_log = env.*.*.GetStaticMethodID.?(
        env,
        g_log_diagnostics,
        "recordLog",
        "(ILjava/lang/String;Ljava/lang/String;)V",
    ) orelse return error.NoMethod;

    const cls = env.*.*.FindClass.?(env, "android/util/Log") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("isLoggable", "(Ljava/lang/String;I)Z", &logIsLoggable),
        method("println_native", "(IILjava/lang/String;Ljava/lang/String;)I", &logPrintln),
        method("logger_entry_max_payload_native", "()I", &logLoggerEntryMaxPayload),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
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
    registerSharedMemory(env) catch return -1;
    registerNetworkUtils(env) catch return -1;
    @import("network_utils_internal_jni.zig").register(env) catch return -1;
    registerProcess(env) catch return -1;
    registerTrace(env) catch return -1;
    registerPerfetto(env) catch return -1;
    registerSystemClock(env) catch return -1;
    registerClassLoaderFactory(env) catch return -1;
    registerCharsetUtils(env) catch return -1;
    registerOverlayConfig(env) catch return -1;
    registerLog(env) catch return -1;
    registerEventLog(env) catch return -1;
    registerSecurityLog(env) catch return -1;
    // Original Binder/Parcel/ServiceManager JNI in the native island; the
    // shared NDK runtime retains only the host bootstrap and conversion seam.
    if (ndk.registerBinderFrameworkNatives(env_opaque) != 0) return -1;
    @import("debug_jni.zig").register(env) catch return -1;
    @import("pointer_icon_jni.zig").register(env, &g_platform_library) catch return -1;
    @import("application_shared_memory_jni.zig").register(env) catch return -1;
    @import("memory_int_array_jni.zig").register(env) catch return -1;
    @import("vintf_jni.zig").register(env) catch return -1;
    @import("performance_hint_jni.zig").register(env) catch return -1;
    @import("traffic_stats_jni.zig").register(env) catch return -1;
    @import("media_crypto_jni.zig").register(env) catch return -1;
    @import("media_metadata_retriever_jni.zig").register(env) catch return -1;
    @import("media_player_jni.zig").register(env) catch return -1;
    @import("sensor_manager_jni.zig").register(env) catch return -1;
    @import("media_drm_jni.zig").register(env) catch return -1;
    @import("legacy_camera_jni.zig").register(env) catch return -1;
    @import("media_codec_list_jni.zig").register(env) catch return -1;
    @import("activity_jni.zig").register(env) catch return -1;
    @import("audio_system_jni.zig").register(env, &g_platform_library) catch return -1;
    @import("audio_track_jni.zig").register(env) catch return -1;
    @import("media_codec_jni.zig").register(env) catch return -1;
    @import("media_ndk.zig").register(env, &g_platform_library) catch return -1;
    @import("aaudio_ndk.zig").register(env, &g_platform_library) catch return -1;
    @import("runtime_init_jni.zig").register(env) catch return -1;
    @import("egl_jni.zig").register(env, &g_platform_library) catch return -1;
    @import("egl_extensions_jni.zig").register(env) catch return -1;
    @import("legacy_egl_jni.zig").register(env, &g_platform_library) catch return -1;
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
