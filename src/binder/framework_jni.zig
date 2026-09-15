//! Hosted framework native registration and broker-backed property access.
//! Original utility and Binder JNI live in libandroid_platform.so; the
//! remaining process, networking and service adaptations are registered here.
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

// Stock libutils dispatches callbacks registered by original SystemProperties JNI.
const ReportPropertiesFn = *const fn () callconv(.c) void;
var g_report_properties: std.atomic.Value(?ReportPropertiesFn) = .init(null);
pub fn onPropertyChanged() void {
    const report = g_report_properties.load(.acquire) orelse return;
    if (ndk.jniEnv() != null) report();
}

const linux = std.os.linux;

/// -errno for a failed syscall (usize result), else 0.
fn errnoOf(rc: usize) i32 {
    const signed: isize = @bitCast(rc);
    return if (signed < 0) @intCast(-signed) else 0;
}

var g_fd_getint: jni.jmethodID = null;

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

// Loaded only for ART framework registration; standalone NDK clients retain
// no dependency on the native island or VM.
var g_platform_library: std.DynLib = undefined;

// ------------------------------------------------------------- registration

fn method(name: [*:0]const u8, signature: [*:0]const u8, ptr: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(ptr)) };
}

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
    const fd_class = env.*.*.FindClass.?(env, "java/io/FileDescriptor") orelse return -1;
    g_fd_getint = env.*.*.GetMethodID.?(env, fd_class, "getInt$", "()I") orelse return -1;
    env.*.*.DeleteLocalRef.?(env, fd_class);
    registerNetworkUtils(env) catch return -1;
    @import("network_utils_internal_jni.zig").register(env) catch return -1;
    registerProcess(env) catch return -1;
    // Original Binder/Parcel/ServiceManager JNI in the native island; the
    // shared NDK runtime retains only the host bootstrap and conversion seam.
    if (ndk.registerBinderFrameworkNatives(env_opaque) != 0) return -1;
    @import("debug_jni.zig").register(env) catch return -1;
    @import("pointer_icon_jni.zig").register(env, &g_platform_library) catch return -1;
    @import("egl_interop_test.zig").register(env) catch return -1;
    @import("traffic_stats_jni.zig").register(env) catch return -1;
    const register_startup = g_platform_library.lookup(
        *const fn (JNIEnvPtr) callconv(.c) c_int,
        "azk_register_startup_jni",
    ) orelse return -1;
    if (register_startup(env) != 0) return -1;
    @import("zygote_tagging_jni.zig").register(env) catch return -1;
    @import("audio_system_jni.zig").register(env, &g_platform_library) catch return -1;
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
