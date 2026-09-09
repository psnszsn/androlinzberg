//! Cross-language system-test peer: a standalone process that links
//! `libbinder_ndk` (the NDK C ABI) and, connecting to the shared session broker
//! through the environment it inherits (AZK_BINDER_SOCKET/COOKIE/PACKAGE, as any
//! spawned client would), looks up a service a *Java* peer registered and calls
//! it. This exercises the real remote path — native peer → broker → JVM peer →
//! onTransact → reply — that the single-process `ndk-test` cannot reach, and the
//! interface-descriptor token round-trips between the native prepareTransaction
//! and the Java stub's enforceInterface.
//!
//! Exits 0 when the Java service answers correctly.
const std = @import("std");

const AIBinder = opaque {};
const AIBinder_Class = opaque {};
const AParcel = opaque {};

extern fn AIBinder_Class_define(
    desc: [*:0]const u8,
    onCreate: ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque,
    onDestroy: ?*const fn (?*anyopaque) callconv(.c) void,
    onTransact: ?*const fn (*AIBinder, u32, *const AParcel, *AParcel) callconv(.c) i32,
) callconv(.c) *AIBinder_Class;
extern fn AIBinder_new(clazz: *const AIBinder_Class, args: ?*anyopaque) callconv(.c) *AIBinder;
extern fn AIBinder_Class_disableInterfaceTokenHeader(clazz: *AIBinder_Class) callconv(.c) void;
extern fn AServiceManager_addService(binder: *AIBinder, instance: [*:0]const u8) callconv(.c) i32;
extern fn AIBinder_associateClass(binder: *AIBinder, clazz: *const AIBinder_Class) callconv(.c) bool;
extern fn AIBinder_prepareTransaction(binder: *AIBinder, in: *?*AParcel) callconv(.c) i32;
extern fn AIBinder_transact(binder: *AIBinder, code: u32, in: *?*AParcel, out: *?*AParcel, flags: u32) callconv(.c) i32;
extern fn AIBinder_decStrong(binder: *AIBinder) callconv(.c) void;
extern fn AIBinder_isRemote(binder: *const AIBinder) callconv(.c) bool;
extern fn AServiceManager_waitForService(instance: [*:0]const u8) callconv(.c) ?*AIBinder;
extern fn AIBinder_ping(binder: ?*AIBinder) callconv(.c) i32;
extern fn AParcel_writeInt32(parcel: *AParcel, value: i32) callconv(.c) i32;
extern fn AParcel_readInt32(parcel: *const AParcel, value: *i32) callconv(.c) i32;
extern fn AParcel_writeString(parcel: *AParcel, string: ?[*]const u8, length: i32) callconv(.c) i32;
extern fn AParcel_readString(parcel: *const AParcel, data: ?*anyopaque, allocator: *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool) callconv(.c) i32;
extern fn AParcel_delete(parcel: *AParcel) callconv(.c) void;

// Contract shared with the Java host (SystemTestHostB.NdkProbeService).
const SERVICE = "dev.artzig.test.b.ndkprobe";
const DESCRIPTOR = "dev.artzig.test.b.INdkProbe";
const TX_ADD: u32 = 1; // add(a, b) -> a + b
const TX_ECHO: u32 = 2; // echo(String) -> String
const TX_FIRE: u32 = 3; // fireOneway(n): host sends n ordered oneway calls to SINK
const TX_UNKNOWN: u32 = 99; // a code the Java stub does not handle
const STATUS_UNKNOWN_TRANSACTION: i32 = -74; // -EBADMSG (binder_status_t)

// A native service this process hosts, so that oneway calls from the Java host
// exercise libbinder_ndk's own per-target serial queue (the incoming-dispatch
// path a single-process self-call never reaches).
const SINK = "dev.artzig.ndkclient.sink";
const ONEWAY_N: u32 = 8;
var oneway_order: [64]u32 = undefined;
var oneway_count = std.atomic.Value(u32).init(0);

fn sinkOnTransact(_: *AIBinder, code: u32, _: *const AParcel, _: *AParcel) callconv(.c) i32 {
    // Oneway calls carry their sequence number as the transaction code; record
    // arrival order. The serial worker runs these one at a time, in order.
    const i = oneway_count.load(.monotonic);
    if (i < oneway_order.len) oneway_order[i] = code;
    oneway_count.store(i + 1, .release);
    return 0;
}

var read_buf: [256]u8 = undefined;
var read_len: usize = 0;
fn readAlloc(_: ?*anyopaque, length: i32, buffer: ?*?[*]u8) callconv(.c) bool {
    if (length < 0) return true;
    read_len = @intCast(@max(length - 1, 0)); // NDK length includes the NUL
    if (buffer) |b| b.* = &read_buf;
    return true;
}

pub fn main() !void {
    var failures: u32 = 0;
    const check = struct {
        fn f(cond: bool, name: []const u8, fails: *u32) void {
            if (cond) {
                std.debug.print("ndk-system-client: ok: {s}\n", .{name});
            } else {
                fails.* += 1;
                std.debug.print("ndk-system-client: FAIL: {s}\n", .{name});
            }
        }
    }.f;

    // The Java host registers this service during its startup; wait for it.
    const svc = AServiceManager_waitForService(SERVICE) orelse {
        std.debug.print("ndk-system-client: FAIL: waitForService returned null\n", .{});
        return error.NdkSystemClientFailed;
    };
    defer AIBinder_decStrong(svc);
    check(AIBinder_isRemote(svc), "Java-registered service resolves to a remote proxy", &failures);
    check(AIBinder_ping(svc) == 0, "remote NDK ping reaches the Java Binder control transaction", &failures);

    // Associate the interface class so prepareTransaction writes the descriptor
    // token the Java stub's enforceInterface checks.
    const class = AIBinder_Class_define(DESCRIPTOR, null, null, dummyOnTransact);
    _ = AIBinder_associateClass(svc, class);

    // add(20, 22) -> 42, across the broker into the Java onTransact.
    {
        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(svc, &in);
        _ = AParcel_writeInt32(in.?, 20);
        _ = AParcel_writeInt32(in.?, 22);
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(svc, TX_ADD, &in, &out, 0);
        check(rc == 0, "remote add transaction succeeded", &failures);
        var result: i32 = 0;
        if (out) |o| {
            _ = AParcel_readInt32(o, &result);
            AParcel_delete(o);
        }
        check(result == 42, "Java service returned add(20,22) = 42", &failures);
    }
    // echo("héllo") round-trips a UTF-8/UTF-16 string through the Java Parcel.
    {
        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(svc, &in);
        const s = "héllo";
        _ = AParcel_writeString(in.?, s, @intCast(s.len));
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(svc, TX_ECHO, &in, &out, 0);
        check(rc == 0, "remote echo transaction succeeded", &failures);
        read_len = 0;
        if (out) |o| {
            _ = AParcel_readString(o, null, readAlloc);
            AParcel_delete(o);
        }
        check(std.mem.eql(u8, read_buf[0..read_len], s), "Java service echoed the string", &failures);
    }

    // Exact status propagation (protocol v4): an unknown transaction code the
    // Java stub does not handle comes back as STATUS_UNKNOWN_TRANSACTION, so a
    // generated proxy would fall back to its default implementation.
    {
        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(svc, &in);
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(svc, TX_UNKNOWN, &in, &out, 0);
        check(rc == STATUS_UNKNOWN_TRANSACTION, "unknown code returns STATUS_UNKNOWN_TRANSACTION", &failures);
        if (out) |o| AParcel_delete(o);
    }

    // Oneway serialization: host our own native sink, ask the Java host to fire
    // N ordered oneway calls at it, and verify libbinder_ndk delivered them to
    // onTransact in submission order (via the per-target serial worker).
    {
        const sink_class = AIBinder_Class_define(SINK, null, null, sinkOnTransact);
        AIBinder_Class_disableInterfaceTokenHeader(sink_class); // raw calls, no token
        const sink = AIBinder_new(sink_class, null);
        check(AServiceManager_addService(sink, SINK) == 0, "native sink registered", &failures);

        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(svc, &in);
        _ = AParcel_writeInt32(in.?, @intCast(ONEWAY_N));
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(svc, TX_FIRE, &in, &out, 0);
        check(rc == 0, "host acknowledged fireOneway", &failures);
        if (out) |o| AParcel_delete(o);

        // Wait for all N oneway calls to arrive (they are asynchronous).
        var spins: usize = 0;
        while (oneway_count.load(.acquire) < ONEWAY_N and spins < 500_000_000) : (spins += 1) {
            std.atomic.spinLoopHint();
        }
        const got = oneway_count.load(.acquire);
        check(got == ONEWAY_N, "all oneway calls arrived", &failures);
        var ordered = got == ONEWAY_N;
        for (0..@min(got, ONEWAY_N)) |i| {
            if (oneway_order[i] != @as(u32, @intCast(i + 1))) ordered = false;
        }
        check(ordered, "oneway calls delivered in submission order", &failures);
        AIBinder_decStrong(sink);
    }

    if (failures != 0) {
        std.debug.print("ndk-system-client: {d} failures\n", .{failures});
        return error.NdkSystemClientFailed;
    }
    std.debug.print("ndk-system-client: all checks passed\n", .{});
}

fn dummyOnTransact(_: *AIBinder, _: u32, _: *const AParcel, _: *AParcel) callconv(.c) i32 {
    return -1; // never invoked: this proxy is remote-only
}
