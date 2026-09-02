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
    if (linux.errno(rc) != .SUCCESS) {
        throwNamed(env, "java/lang/IllegalArgumentException", "Given thread does not exist");
        return SCHED_OTHER;
    }
    return @intCast(rc);
}
fn procSetThreadScheduler(env: JNIEnvPtr, _: jni.jclass, tid: jni.jint, policy: jni.jint, prio: jni.jint) callconv(.c) void {
    const param = linux.sched_param{ .priority = prio };
    const rc = linux.sched_setscheduler(tid, @bitCast(@as(u32, @intCast(policy))), &param);
    switch (linux.errno(rc)) {
        .SUCCESS, .PERM, .ACCES => {},
        else => throwNamed(env, "java/lang/IllegalArgumentException", "sched_setscheduler failed"),
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
    _ = linux.prctl(@intFromEnum(linux.PR.SET_NAME), @intFromPtr(&name), 0, 0, 0);
}
fn procSetUid(_: JNIEnvPtr, _: jni.jclass, uid: jni.jint) callconv(.c) jni.jint {
    return if (linux.errno(linux.setuid(@intCast(uid))) == .SUCCESS) 0 else -1;
}
fn procSetGid(_: JNIEnvPtr, _: jni.jclass, gid: jni.jint) callconv(.c) jni.jint {
    return if (linux.errno(linux.setgid(@intCast(gid))) == .SUCCESS) 0 else -1;
}
fn procGetUidForName(_: JNIEnvPtr, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jint {
    return -1; // no Android account database on this host
}
fn procGetGidForName(_: JNIEnvPtr, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jint {
    return -1;
}
fn sigOf(sig: jni.jint) ?linux.SIG {
    if (sig < 1 or sig > 64) return null;
    return @enumFromInt(@as(u6, @intCast(sig)));
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
    const fd_rc = linux.open(path, .{ .ACCMODE = .RDONLY, .CLOEXEC = true }, 0);
    if (linux.errno(fd_rc) != .SUCCESS) return null;
    const fd: i32 = @intCast(fd_rc);
    defer _ = linux.close(fd);
    var buf = std.ArrayListUnmanaged(u8).empty;
    var chunk: [4096]u8 = undefined;
    while (true) {
        const n = linux.read(fd, &chunk, chunk.len);
        if (linux.errno(n) != .SUCCESS or n == 0) break;
        buf.appendSlice(std.heap.c_allocator, chunk[0..n]) catch break;
    }
    return buf.toOwnedSlice(std.heap.c_allocator) catch null;
}

/// Process.readProcLines: for each requested "Field:" line, the numeric value
/// (kB values scaled to bytes, as AOSP does).
fn procReadProcLines(env: JNIEnvPtr, _: jni.jclass, path: jni.jstring, req: jni.jobjectArray, out: jni.jlongArray) callconv(.c) void {
    if (path == null or req == null or out == null) return;
    const pchars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, pchars);
    const contents = readSmallFile(pchars) orelse return;
    defer std.heap.c_allocator.free(contents);
    const count: usize = @intCast(env.*.*.GetArrayLength.?(env, req));
    var values = std.heap.c_allocator.alloc(jni.jlong, count) catch return;
    defer std.heap.c_allocator.free(values);
    env.*.*.GetLongArrayRegion.?(env, out, 0, @intCast(count), values.ptr);
    for (0..count) |i| {
        const jf: jni.jstring = @ptrCast(env.*.*.GetObjectArrayElement.?(env, req, @intCast(i)));
        if (jf == null) continue;
        const fchars = env.*.*.GetStringUTFChars.?(env, jf, null) orelse continue;
        const field = std.mem.span(fchars);
        var lines = std.mem.splitScalar(u8, contents, '\n');
        while (lines.next()) |line| {
            if (!std.mem.startsWith(u8, line, field)) continue;
            var rest = std.mem.trim(u8, line[field.len..], " \t");
            var scale: jni.jlong = 1;
            if (std.mem.endsWith(u8, rest, "kB")) {
                scale = 1024;
                rest = std.mem.trim(u8, rest[0 .. rest.len - 2], " \t");
            }
            values[i] = (std.fmt.parseInt(jni.jlong, rest, 10) catch 0) * scale;
            break;
        }
        env.*.*.ReleaseStringUTFChars.?(env, jf, fchars);
        env.*.*.DeleteLocalRef.?(env, jf);
    }
    env.*.*.SetLongArrayRegion.?(env, out, 0, @intCast(count), values.ptr);
}

/// Process.getPids: the numeric entries of a /proc-style directory, padded with
/// -1 (reusing `last` when it is large enough, as AOSP does).
fn procGetPids(env: JNIEnvPtr, _: jni.jclass, path: jni.jstring, last: jni.jintArray) callconv(.c) jni.jintArray {
    if (path == null) return last;
    const pchars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return last;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, pchars);
    const fd_rc = linux.open(pchars, .{ .ACCMODE = .RDONLY, .DIRECTORY = true, .CLOEXEC = true }, 0);
    if (linux.errno(fd_rc) != .SUCCESS) return last;
    const fd: i32 = @intCast(fd_rc);
    defer _ = linux.close(fd);
    var pids = std.ArrayListUnmanaged(jni.jint).empty;
    defer pids.deinit(std.heap.c_allocator);
    var buf: [8192]u8 = undefined;
    while (true) {
        const n = linux.getdents64(fd, &buf, buf.len);
        if (linux.errno(n) != .SUCCESS or n == 0) break;
        var off: usize = 0;
        while (off < n) {
            const reclen = std.mem.readInt(u16, buf[off + 16 ..][0..2], .little);
            const name_start = off + 19;
            const name_end = std.mem.indexOfScalarPos(u8, buf[0..n], name_start, 0) orelse (off + reclen);
            const name = buf[name_start..name_end];
            if (std.fmt.parseInt(jni.jint, name, 10)) |pid| pids.append(std.heap.c_allocator, pid) catch {} else |_| {}
            off += reclen;
        }
    }
    const have: usize = if (last != null) @intCast(env.*.*.GetArrayLength.?(env, last)) else 0;
    const arr: jni.jintArray = if (last != null and have >= pids.items.len + 1) last else env.*.*.NewIntArray.?(env, @intCast(pids.items.len + 1)) orelse return null;
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, arr));
    const tmp = std.heap.c_allocator.alloc(jni.jint, len) catch return arr;
    defer std.heap.c_allocator.free(tmp);
    @memset(tmp, -1);
    @memcpy(tmp[0..pids.items.len], pids.items);
    env.*.*.SetIntArrayRegion.?(env, arr, 0, @intCast(len), tmp.ptr);
    return arr;
}
fn procReadProcFile(_: JNIEnvPtr, _: jni.jclass, _: jni.jstring, _: jni.jintArray, _: jni.jobjectArray, _: jni.jlongArray, _: jni.jfloatArray) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE; // PROC_* format parsing (ProcessCpuTracker) is not hosted
}
fn procParseProcLine(_: JNIEnvPtr, _: jni.jclass, _: jni.jbyteArray, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray, _: jni.jlongArray, _: jni.jfloatArray) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn procGetPidsForCommands(env: JNIEnvPtr, _: jni.jclass, _: jni.jobjectArray) callconv(.c) jni.jintArray {
    return env.*.*.NewIntArray.?(env, 0);
}
fn procGetPss(_: JNIEnvPtr, _: jni.jclass, _: jni.jint) callconv(.c) jni.jlong {
    return 0;
}
fn procGetRss(env: JNIEnvPtr, _: jni.jclass, _: jni.jint) callconv(.c) jni.jlongArray {
    return env.*.*.NewLongArray.?(env, 4);
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
    registerProcess(env) catch return -1;
    registerTrace(env) catch return -1;
    registerSystemClock(env) catch return -1;
    registerClassLoaderFactory(env) catch return -1;
    // Parcel/Binder/BinderProxy/BinderInternal — the real AOSP classes over the
    // ndk Runtime/AParcel (co-located there for AParcel/Runtime access).
    if (ndk.registerBinderFrameworkNatives(env_opaque) != 0) return -1;
    return 0;
}
