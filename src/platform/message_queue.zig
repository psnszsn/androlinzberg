//! android.os.MessageQueue natives in Zig, following AOSP's
//! core/jni/android_os_MessageQueue.cpp over a libutils-style Looper: each Java
//! MessageQueue owns an epoll set with an eventfd wake registered in it.
//! `nativePollOnce` waits on the set, drains the wake, and routes ready listener
//! descriptors through the Java `dispatchEvents(fd, events)` callback, which
//! answers the new event mask (0 = unregister). `nativeWake` signals the eventfd.
//!
//! The queue created on the process main thread is the host queue: its epoll
//! descriptor is what the Wayland owner loop polls, so a message posted from any
//! thread (or a ready listener fd) wakes the host, which then runs the bounded
//! pump (`MessageQueue.pollDueMessage$artzig`, itself a zero-timeout
//! `nativePollOnce`). AOSP's main looper blocks in the same epoll instead.
//!
//! std.Io policy: epoll has no std.Io constructor or operations, so this module
//! is a documented carve-out — epoll_create1/epoll_ctl/epoll_wait go through
//! std.os.linux, the epoll descriptor is owned by a std.Io.File, and the wake
//! eventfd is the std.Io-backed `event_fd.EventFd`.
const std = @import("std");
const linux = std.os.linux;
const art = @import("art");
const jni = art.jni;
const EventFd = @import("event_fd").EventFd;

const gpa = std.heap.c_allocator;

/// MessageQueue.OnFileDescriptorEventListener event bits (the Java contract).
const CALLBACK_EVENT_INPUT: i32 = 1 << 0;
const CALLBACK_EVENT_OUTPUT: i32 = 1 << 1;
const CALLBACK_EVENT_ERROR: i32 = 1 << 2;

const NativeQueue = struct {
    io: std.Io,
    epoll: std.Io.File,
    wake: EventFd,
    mu: std.Io.Mutex = .init,
    /// Registered listener descriptors -> the callback event mask being watched.
    fds: std.AutoHashMapUnmanaged(i32, i32) = .empty,
    polling: std.atomic.Value(bool) = .init(false),

    fn create(io: std.Io) !*NativeQueue {
        const rc = linux.epoll_create1(linux.EPOLL.CLOEXEC);
        if (linux.errno(rc) != .SUCCESS) return error.EpollCreateFailed;
        const epoll: std.Io.File = .{ .handle = @intCast(rc), .flags = .{ .nonblocking = false } };
        errdefer epoll.close(io);
        const wake = try EventFd.init(io);
        errdefer wake.close();
        const q = try gpa.create(NativeQueue);
        q.* = .{ .io = io, .epoll = epoll, .wake = wake };
        var ev = linux.epoll_event{ .events = linux.EPOLL.IN, .data = .{ .fd = wake.fd() } };
        if (linux.errno(linux.epoll_ctl(epoll.handle, linux.EPOLL.CTL_ADD, wake.fd(), &ev)) != .SUCCESS) {
            gpa.destroy(q);
            return error.EpollCtlFailed;
        }
        return q;
    }

    fn destroy(q: *NativeQueue) void {
        q.fds.deinit(gpa);
        q.wake.close();
        q.epoll.close(q.io);
        gpa.destroy(q);
    }

    /// Looper::addFd / removeFd: `events` is the callback mask; 0 unregisters.
    fn setFdEvents(q: *NativeQueue, fd: i32, events: i32) void {
        q.mu.lockUncancelable(q.io);
        defer q.mu.unlock(q.io);
        if (events == 0) {
            if (q.fds.remove(fd)) {
                _ = linux.epoll_ctl(q.epoll.handle, linux.EPOLL.CTL_DEL, fd, null);
            }
            return;
        }
        var mask: u32 = linux.EPOLL.ERR | linux.EPOLL.HUP;
        if (events & CALLBACK_EVENT_INPUT != 0) mask |= linux.EPOLL.IN;
        if (events & CALLBACK_EVENT_OUTPUT != 0) mask |= linux.EPOLL.OUT;
        var ev = linux.epoll_event{ .events = mask, .data = .{ .fd = fd } };
        const op: u32 = if (q.fds.contains(fd)) linux.EPOLL.CTL_MOD else linux.EPOLL.CTL_ADD;
        if (linux.errno(linux.epoll_ctl(q.epoll.handle, op, fd, &ev)) == .SUCCESS) {
            q.fds.put(gpa, fd, events) catch {};
        }
    }

    fn watched(q: *NativeQueue, fd: i32) ?i32 {
        q.mu.lockUncancelable(q.io);
        defer q.mu.unlock(q.io);
        return q.fds.get(fd);
    }

    const Ready = struct { fd: i32, events: i32 };

    /// One epoll wait. Drains the wake; returns the ready listener descriptors
    /// with their callback event bits (a closed/errored fd reports ERROR).
    fn wait(q: *NativeQueue, timeout_ms: i32, out: []Ready) usize {
        var events: [16]linux.epoll_event = undefined;
        q.polling.store(true, .release);
        const rc = linux.epoll_wait(q.epoll.handle, &events, events.len, timeout_ms);
        q.polling.store(false, .release);
        if (linux.errno(rc) != .SUCCESS) return 0; // EINTR etc.: no events this round
        var n: usize = 0;
        for (events[0..rc]) |ev| {
            const fd = ev.data.fd;
            if (fd == q.wake.fd()) {
                _ = q.wake.consume();
                continue;
            }
            var cb: i32 = 0;
            if (ev.events & linux.EPOLL.IN != 0) cb |= CALLBACK_EVENT_INPUT;
            if (ev.events & linux.EPOLL.OUT != 0) cb |= CALLBACK_EVENT_OUTPUT;
            if (ev.events & (linux.EPOLL.ERR | linux.EPOLL.HUP) != 0) cb |= CALLBACK_EVENT_ERROR;
            if (n < out.len) {
                out[n] = .{ .fd = fd, .events = cb };
                n += 1;
            }
        }
        return n;
    }
};

var g_io: ?std.Io = null;
var g_host: ?*NativeQueue = null;
var g_dispatch_events: jni.jmethodID = null;

fn queueOf(ptr: jni.jlong) *NativeQueue {
    return @ptrFromInt(@as(usize, @intCast(ptr)));
}

fn isMainThread() bool {
    return linux.gettid() == linux.getpid();
}

fn nativeInit(env: [*c]jni.JNIEnv, _: jni.jclass) callconv(.c) jni.jlong {
    const io = g_io orelse return 0;
    const q = NativeQueue.create(io) catch {
        const cls = env.*.*.FindClass.?(env, "java/lang/RuntimeException");
        if (cls != null) _ = env.*.*.ThrowNew.?(env, cls, "Unable to allocate native queue");
        return 0;
    };
    // The main thread's queue (Looper.prepareMainLooper) is the one the host
    // loop drives; the first queue created there claims that role.
    if (g_host == null and isMainThread()) g_host = q;
    return @intCast(@intFromPtr(q));
}

fn nativeDestroy(_: [*c]jni.JNIEnv, _: jni.jclass, ptr: jni.jlong) callconv(.c) void {
    if (ptr == 0) return;
    const q = queueOf(ptr);
    if (g_host == q) g_host = null;
    q.destroy();
}

fn nativePollOnce(env: [*c]jni.JNIEnv, this: jni.jobject, ptr: jni.jlong, timeout_ms: jni.jint) callconv(.c) void {
    if (ptr == 0) return;
    const q = queueOf(ptr);
    var ready: [16]NativeQueue.Ready = undefined;
    const n = q.wait(timeout_ms, &ready);
    for (ready[0..n]) |r| {
        const old = q.watched(r.fd) orelse continue; // unregistered meanwhile
        // dispatchEvents(fd, events) answers the events to keep watching.
        const new_events = env.*.*.CallIntMethod.?(env, this, g_dispatch_events, r.fd, r.events);
        // A throwing listener: stop dispatching and let the exception propagate
        // out of nativePollOnce, as android_os_MessageQueue rethrows it.
        if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) return;
        if (new_events == 0) {
            q.setFdEvents(r.fd, 0);
        } else if (new_events != old) {
            q.setFdEvents(r.fd, new_events);
        }
    }
}

fn nativeWake(_: [*c]jni.JNIEnv, _: jni.jclass, ptr: jni.jlong) callconv(.c) void {
    if (ptr != 0) queueOf(ptr).wake.signal();
}

fn nativeIsPolling(_: [*c]jni.JNIEnv, _: jni.jclass, ptr: jni.jlong) callconv(.c) jni.jboolean {
    if (ptr == 0) return jni.JNI_FALSE;
    return if (queueOf(ptr).polling.load(.acquire)) jni.JNI_TRUE else jni.JNI_FALSE;
}

fn nativeSetFileDescriptorEvents(_: [*c]jni.JNIEnv, _: jni.jclass, ptr: jni.jlong, fd: jni.jint, events: jni.jint) callconv(.c) void {
    if (ptr != 0) queueOf(ptr).setFdEvents(fd, events);
}

pub fn register(io: std.Io, env: [*c]jni.JNIEnv) !void {
    g_io = io;
    const cls = env.*.*.FindClass.?(env, "android/os/MessageQueue") orelse return error.NoClass;
    g_dispatch_events = env.*.*.GetMethodID.?(env, cls, "dispatchEvents", "(II)I") orelse return error.NoMethod;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeInit", .signature = "()J", .fnPtr = @ptrCast(@constCast(&nativeInit)) },
        .{ .name = "nativeDestroy", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeDestroy)) },
        .{ .name = "nativePollOnce", .signature = "(JI)V", .fnPtr = @ptrCast(@constCast(&nativePollOnce)) },
        .{ .name = "nativeWake", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeWake)) },
        .{ .name = "nativeIsPolling", .signature = "(J)Z", .fnPtr = @ptrCast(@constCast(&nativeIsPolling)) },
        .{ .name = "nativeSetFileDescriptorEvents", .signature = "(JII)V", .fnPtr = @ptrCast(@constCast(&nativeSetFileDescriptorEvents)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

/// The host queue's pollable descriptor (readable when a wake or a listener fd
/// is pending), or null before the main looper exists.
pub fn hostQueueFd() ?std.posix.fd_t {
    return if (g_host) |q| q.epoll.handle else null;
}

/// Drains the host queue's wake counter (the pump's zero-timeout poll also does).
pub fn consumeHostWake() u64 {
    return if (g_host) |q| q.wake.consume() else 0;
}

pub fn deinit() void {
    g_host = null;
}

test "a wake makes the queue's epoll descriptor readable and is drained by wait" {
    const q = try NativeQueue.create(std.testing.io);
    defer q.destroy();
    q.wake.signal();
    var fds = [_]std.posix.pollfd{.{ .fd = q.epoll.handle, .events = std.posix.POLL.IN, .revents = 0 }};
    try std.testing.expectEqual(@as(usize, 1), try std.posix.poll(&fds, 1000));
    var ready: [4]NativeQueue.Ready = undefined;
    try std.testing.expectEqual(@as(usize, 0), q.wait(0, &ready)); // only the wake, drained
    try std.testing.expectEqual(@as(usize, 0), q.wait(0, &ready)); // nothing pending now
}

test "a registered listener descriptor reports INPUT when readable and can be unregistered" {
    const q = try NativeQueue.create(std.testing.io);
    defer q.destroy();
    var pipe_fds: [2]i32 = undefined;
    try std.testing.expectEqual(@as(usize, 0), linux.pipe2(&pipe_fds, .{ .CLOEXEC = true }));
    defer _ = linux.close(pipe_fds[0]);
    defer _ = linux.close(pipe_fds[1]);
    q.setFdEvents(pipe_fds[0], CALLBACK_EVENT_INPUT);
    var ready: [4]NativeQueue.Ready = undefined;
    try std.testing.expectEqual(@as(usize, 0), q.wait(0, &ready));
    _ = linux.write(pipe_fds[1], "x", 1);
    try std.testing.expectEqual(@as(usize, 1), q.wait(1000, &ready));
    try std.testing.expectEqual(pipe_fds[0], ready[0].fd);
    try std.testing.expectEqual(CALLBACK_EVENT_INPUT, ready[0].events);
    q.setFdEvents(pipe_fds[0], 0);
    try std.testing.expectEqual(@as(usize, 0), q.wait(0, &ready));
}
