//! JNI for verbatim AOSP android.os.Debug. Follows android_os_Debug.cpp;
//! procfs/sysfs and descriptor ownership use std.Io, heap numbers use the host
//! allocator, and absent Android memtrack/debuggerd facilities retain failures.
const std = @import("std");
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const memory = @import("process_memory.zig");
const Env = [*c]jni.JNIEnv;
const alloc = std.heap.c_allocator;
const Io = std.Io;

const MallInfo = extern struct {
    arena: usize,
    ordblks: usize,
    smblks: usize,
    hblks: usize,
    hblkhd: usize,
    usmblks: usize,
    fsmblks: usize,
    uordblks: usize,
    fordblks: usize,
    keepcost: usize,
};
extern "c" fn mallinfo2() MallInfo;
// malloc_info owns a memory-only report stream, never an OS descriptor. The
// completed report is written to the caller's descriptor exclusively with Io.
extern "c" fn open_memstream(*?[*]u8, *usize) ?*anyopaque;
extern "c" fn malloc_info(c_int, *anyopaque) c_int;
extern "c" fn fclose(*anyopaque) c_int;

var fields: [3][9]jni.jfieldID = undefined;
var other_stats: jni.jfieldID = null;
var swapped_pss: jni.jfieldID = null;
var fd_descriptor: jni.jfieldID = null;

fn throwNamed(env: Env, name: [*:0]const u8, message: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn logFailure(env: Env, message: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, "android/util/Log") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method_id = env.*.*.GetStaticMethodID.?(env, cls, "e", "(Ljava/lang/String;Ljava/lang/String;)I") orelse return;
    const tag = env.*.*.NewStringUTF.?(env, "Debug") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, tag);
    const text = env.*.*.NewStringUTF.?(env, message) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, text);
    const args = [_]jni.jvalue{ .{ .l = tag }, .{ .l = text } };
    _ = env.*.*.CallStaticIntMethodA.?(env, cls, method_id, &args);
}
fn unsupported(env: Env, member: [*:0]const u8, detail: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method_id = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "android.os.Debug") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    const name = env.*.*.NewStringUTF.?(env, member) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, name);
    const message = env.*.*.NewStringUTF.?(env, detail) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, message);
    const args = [_]jni.jvalue{ .{ .l = owner }, .{ .l = name }, .{ .l = message } };
    const exception = env.*.*.CallStaticObjectMethodA.?(env, cls, method_id, &args) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.Throw.?(env, @ptrCast(exception));
}
fn long(value: u64) jni.jlong {
    return @bitCast(value);
}
fn int(value: u64) jni.jint {
    return @bitCast(@as(u32, @truncate(value)));
}
fn heapSize(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    const info = mallinfo2();
    // Bionic usmblks is total allocated space; glibc splits arenas and mmaps.
    return long(info.arena +% info.hblkhd);
}
fn heapAllocated(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    const info = mallinfo2();
    return long(info.uordblks +% info.hblkhd);
}
fn heapFree(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return long(mallinfo2().fordblks);
}

fn memoryInfoPid(env: Env, _: jni.jclass, pid: jni.jint, object: jni.jobject) callconv(.c) jni.jboolean {
    if (object == null) {
        throwNamed(env, "java/lang/NullPointerException", "memoryInfo == null");
        return jni.JNI_FALSE;
    }
    const info = memory.readMemoryInfo(ndk.frameworkIo(), alloc, pid) catch return jni.JNI_FALSE;
    for (info.heaps[0..3], 0..) |heap, i| {
        inline for (@typeInfo(memory.Stats).@"struct".field_names, 0..) |name, j| {
            env.*.*.SetIntField.?(env, object, fields[i][j], int(@field(heap, name)));
        }
    }
    env.*.*.SetBooleanField.?(env, object, swapped_pss, if (info.has_swap_pss) jni.JNI_TRUE else jni.JNI_FALSE);
    const other: jni.jintArray = @ptrCast(env.*.*.GetObjectField.?(env, object, other_stats));
    if (other == null) return jni.JNI_FALSE;
    defer env.*.*.DeleteLocalRef.?(env, other);
    var values: [(memory.heap_count - 3) * 9]jni.jint = undefined;
    for (info.heaps[3..], 0..) |heap, i| {
        inline for (@typeInfo(memory.Stats).@"struct".field_names, 0..) |name, j| values[i * 9 + j] = int(@field(heap, name));
    }
    env.*.*.SetIntArrayRegion.?(env, other, 0, values.len, &values);
    return if (env.*.*.ExceptionCheck.?(env) != 0) jni.JNI_FALSE else jni.JNI_TRUE;
}
fn memoryInfoSelf(env: Env, cls: jni.jclass, object: jni.jobject) callconv(.c) void {
    _ = memoryInfoPid(env, cls, std.os.linux.getpid(), object);
}
fn copyLongPrefix(env: Env, out: jni.jlongArray, values: []const jni.jlong) void {
    if (out == null) return;
    const count: usize = @min(@as(usize, @intCast(env.*.*.GetArrayLength.?(env, out))), values.len);
    if (count != 0) env.*.*.SetLongArrayRegion.?(env, out, 0, @intCast(count), values.ptr);
}
fn pssPid(env: Env, _: jni.jclass, pid: jni.jint, out: jni.jlongArray, memtrack: jni.jlongArray) callconv(.c) jni.jlong {
    const stats = memory.readPss(ndk.frameworkIo(), alloc, pid) catch return 0;
    copyLongPrefix(env, out, &.{ long(stats.uss()), long(stats.swapped_out_pss), long(stats.rss) });
    // No memtrack HAL: mapped graphics remain in smaps, external categories are absent.
    copyLongPrefix(env, memtrack, &.{ 0, 0, 0, 0 });
    return long(stats.pss +% stats.swapped_out_pss);
}
fn pssSelf(env: Env, cls: jni.jclass) callconv(.c) jni.jlong {
    return pssPid(env, cls, std.os.linux.getpid(), null, null);
}
fn rssPid(env: Env, _: jni.jclass, pid: jni.jint, memtrack: jni.jlongArray) callconv(.c) jni.jlong {
    const rss = memory.readRss(ndk.frameworkIo(), alloc, pid) catch return 0;
    copyLongPrefix(env, memtrack, &.{ 0, 0, 0, 0 });
    return long(rss);
}
fn rssSelf(env: Env, cls: jni.jclass) callconv(.c) jni.jlong {
    return rssPid(env, cls, std.os.linux.getpid(), null);
}
fn memInfo(env: Env, _: jni.jclass, out: jni.jlongArray) callconv(.c) void {
    if (out == null) return throwNamed(env, "java/lang/NullPointerException", "out == null");
    if (env.*.*.GetArrayLength.?(env, out) < memory.meminfo_tags.len) return throwNamed(env, "java/lang/RuntimeException", "outLen < MEMINFO_COUNT");
    const values = memory.readMemInfo(ndk.frameworkIo(), alloc) catch return throwNamed(env, "java/lang/RuntimeException", "SysMemInfo read failed");
    var out_values: [memory.meminfo_tags.len]jni.jlong = undefined;
    for (values, &out_values) |value, *out_value| out_value.* = long(value);
    copyLongPrefix(env, out, &out_values);
}
fn scalar(path: []const u8) jni.jlong {
    return long(memory.readScalar(ndk.frameworkIo(), alloc, path) catch return -1);
}
fn zramFree(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    const text = memory.readText(ndk.frameworkIo(), alloc, "/proc/meminfo") catch return 0;
    defer alloc.free(text);
    return long(memory.parseTaggedNumber(text, "SwapFree") catch 0);
}
fn ionHeaps(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return scalar("/sys/kernel/ion/total_heaps_kb");
}
fn ionPools(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return scalar("/sys/kernel/ion/total_pools_kb");
}
fn dmaPools(env: Env, cls: jni.jclass) callconv(.c) jni.jlong {
    const result = scalar("/sys/kernel/dma_heap/total_pools_kb");
    return if (result < 0) ionPools(env, cls) else result;
}
fn dmaExported(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return long(memory.readDmaBufExported(ndk.frameworkIo(), alloc, false) catch return -1);
}
fn dmaHeaps(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return long(memory.readDmaBufExported(ndk.frameworkIo(), alloc, true) catch return -1);
}
fn dmaMapped(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return long(memory.readDmaBufMapped(ndk.frameworkIo(), alloc) catch return 0);
}
fn cmaUsage(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return long(memory.readCmaUsage(ndk.frameworkIo(), alloc) catch return -1);
}
fn unavailableGpuMetric(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return -1;
} // No memtrack HAL or Android gpu_mem BPF map.
fn binderStat(tag: []const u8) jni.jint {
    const text = memory.readText(ndk.frameworkIo(), alloc, "/proc/binder/stats") catch return -1;
    defer alloc.free(text);
    var process_buffer: [64]u8 = undefined;
    const process = std.fmt.bufPrint(&process_buffer, "proc {d}", .{std.os.linux.getpid()}) catch unreachable;
    var lines = std.mem.splitScalar(u8, text, '\n');
    while (lines.next()) |line| if (std.mem.eql(u8, line, process)) break;
    while (lines.next()) |line| {
        if (std.mem.startsWith(u8, line, "proc ")) break;
        if (std.mem.startsWith(u8, line, tag)) return int(memory.firstNumber(line[tag.len..]) catch return -1);
    }
    return -1;
}
fn binderSent(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return binderStat("  bcTRANSACTION: ");
}
fn binderReceived(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return binderStat("  brTRANSACTION: ");
}
fn binderLocals(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return ndk.debugBinderCounts().local;
}
fn binderProxies(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return ndk.debugBinderCounts().proxy;
}
fn binderDeaths(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return ndk.debugBinderCounts().death;
}

fn duplicateOutput(env: Env, descriptor: jni.jobject) ?Io.File {
    if (descriptor == null) {
        throwNamed(env, "java/lang/NullPointerException", "fd == null");
        return null;
    }
    const fd = env.*.*.GetIntField.?(env, descriptor, fd_descriptor);
    if (fd < 0) {
        throwNamed(env, "java/lang/RuntimeException", "Invalid file descriptor");
        return null;
    }
    // Io has no descriptor-duplication constructor. Transfer the new descriptor
    // immediately to Io.File; writes and closing stay entirely in Io.
    const duplicated = std.os.linux.fcntl(fd, std.os.linux.F.DUPFD_CLOEXEC, 0);
    if (std.os.linux.errno(duplicated) != .SUCCESS) {
        throwNamed(env, "java/lang/RuntimeException", "dup() failed");
        return null;
    }
    return .{ .handle = @intCast(duplicated), .flags = .{ .nonblocking = false } };
}
fn dumpHeap(env: Env, _: jni.jclass, descriptor: jni.jobject) callconv(.c) void {
    const file = duplicateOutput(env, descriptor) orelse return;
    defer file.close(ndk.frameworkIo());
    // AOSP logs failure when malloc_debug's M_WRITE_MALLOC_LEAK_INFO_TO_FILE is unavailable.
    logFailure(env, "Failed to write native heap dump to file: Android malloc_debug is not enabled");
}
fn dumpMallocInfo(env: Env, _: jni.jclass, descriptor: jni.jobject) callconv(.c) void {
    const file = duplicateOutput(env, descriptor) orelse return;
    defer file.close(ndk.frameworkIo());
    var buffer: ?[*]u8 = null;
    var size: usize = 0;
    const report = open_memstream(&buffer, &size) orelse return throwNamed(env, "java/lang/RuntimeException", "open_memstream() failed");
    const result = malloc_info(0, report);
    const closed = fclose(report); // Finalize the allocator's memory-only report.
    defer if (buffer) |bytes| alloc.free(bytes[0 .. size + 1]);
    if (result != 0 or closed != 0) return logFailure(env, "malloc_info() failed");
    if (buffer) |bytes| file.writeStreamingAll(ndk.frameworkIo(), bytes[0..size]) catch logFailure(env, "Failed to write native malloc info");
}
fn dumpBacktrace(env: Env, _: jni.jclass, _: jni.jint, filename: jni.jstring, _: jni.jint) callconv(.c) jni.jboolean {
    if (filename == null) {
        throwNamed(env, "java/lang/NullPointerException", "file == null");
        return jni.JNI_FALSE;
    }
    const path = env.*.*.GetStringUTFChars.?(env, filename, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseStringUTFChars.?(env, filename, path);
    const io = ndk.frameworkIo();
    const file = Io.Dir.cwd().openFile(io, std.mem.span(path), .{ .mode = .write_only, .follow_symlinks = false }) catch |err| switch (err) {
        error.FileNotFound => Io.Dir.cwd().createFile(io, std.mem.span(path), .{ .exclusive = true }) catch return jni.JNI_FALSE,
        else => return jni.JNI_FALSE,
    };
    defer file.close(io);
    file.sync(io) catch {};
    // The stock debuggerd client fails without its Android daemon. Do not report
    // success or substitute kernel stacks for Java/native user-space backtraces.
    logFailure(env, "Failed to dump backtrace: debuggerd is not hosted");
    return jni.JNI_FALSE;
}
fn unreachableMemory(env: Env, _: jni.jclass, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jstring {
    unsupported(env, "getUnreachableMemory", "libmemunreachable heap tracing is not hosted");
    return null;
}
fn vmapStack(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    const io = ndk.frameworkIo();
    const release_text = memory.readText(io, alloc, "/proc/sys/kernel/osrelease") catch {
        unsupported(env, "isVmapStack", "running kernel configuration is not exposed");
        return jni.JNI_FALSE;
    };
    defer alloc.free(release_text);
    const path = std.fmt.allocPrint(alloc, "/boot/config-{s}", .{std.mem.trim(u8, release_text, " \t\r\n")}) catch return jni.JNI_FALSE;
    defer alloc.free(path);
    const config = memory.readText(io, alloc, path) catch {
        unsupported(env, "isVmapStack", "running kernel configuration is not exposed");
        return jni.JNI_FALSE;
    };
    defer alloc.free(config);
    var lines = std.mem.splitScalar(u8, config, '\n');
    while (lines.next()) |line| if (std.mem.eql(u8, line, "CONFIG_VMAP_STACK=y")) return jni.JNI_TRUE;
    return jni.JNI_FALSE;
}
fn logAllocatorStats(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
} // No bionic M_LOG_STATS extension.

fn method(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    const info = env.*.*.FindClass.?(env, "android/os/Debug$MemoryInfo") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, info);
    const num_other = env.*.*.GetStaticFieldID.?(env, info, "NUM_OTHER_STATS", "I") orelse return error.NoField;
    const num_dalvik = env.*.*.GetStaticFieldID.?(env, info, "NUM_DVK_STATS", "I") orelse return error.NoField;
    if (env.*.*.GetStaticIntField.?(env, info, num_other) + env.*.*.GetStaticIntField.?(env, info, num_dalvik) != memory.heap_count - 3) return error.MemoryInfoShapeMismatch;
    other_stats = env.*.*.GetFieldID.?(env, info, "otherStats", "[I") orelse return error.NoField;
    swapped_pss = env.*.*.GetFieldID.?(env, info, "hasSwappedOutPss", "Z") orelse return error.NoField;
    inline for (.{ "other", "dalvik", "native" }, 0..) |prefix, i| {
        inline for (.{ "Pss", "SwappablePss", "Rss", "PrivateDirty", "SharedDirty", "PrivateClean", "SharedClean", "SwappedOut", "SwappedOutPss" }, 0..) |suffix, j| {
            fields[i][j] = env.*.*.GetFieldID.?(env, info, prefix ++ suffix, "I") orelse return error.NoField;
        }
    }
    const descriptor = env.*.*.FindClass.?(env, "java/io/FileDescriptor") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, descriptor);
    fd_descriptor = env.*.*.GetFieldID.?(env, descriptor, "descriptor", "I") orelse return error.NoField;
    const cls = env.*.*.FindClass.?(env, "android/os/Debug") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("getNativeHeapSize", "()J", &heapSize),
        method("getNativeHeapAllocatedSize", "()J", &heapAllocated),
        method("getNativeHeapFreeSize", "()J", &heapFree),
        method("getMemoryInfo", "(Landroid/os/Debug$MemoryInfo;)V", &memoryInfoSelf),
        method("getMemoryInfo", "(ILandroid/os/Debug$MemoryInfo;)Z", &memoryInfoPid),
        method("getPss", "()J", &pssSelf),
        method("getPss", "(I[J[J)J", &pssPid),
        method("getRss", "()J", &rssSelf),
        method("getRss", "(I[J)J", &rssPid),
        method("getMemInfo", "([J)V", &memInfo),
        method("dumpNativeHeap", "(Ljava/io/FileDescriptor;)V", &dumpHeap),
        method("dumpNativeMallocInfo", "(Ljava/io/FileDescriptor;)V", &dumpMallocInfo),
        method("getBinderSentTransactions", "()I", &binderSent),
        method("getBinderReceivedTransactions", "()I", &binderReceived),
        method("getBinderLocalObjectCount", "()I", &binderLocals),
        method("getBinderProxyObjectCount", "()I", &binderProxies),
        method("getBinderDeathObjectCount", "()I", &binderDeaths),
        method("dumpJavaBacktraceToFileTimeout", "(ILjava/lang/String;I)Z", &dumpBacktrace),
        method("dumpNativeBacktraceToFileTimeout", "(ILjava/lang/String;I)Z", &dumpBacktrace),
        method("getUnreachableMemory", "(IZ)Ljava/lang/String;", &unreachableMemory),
        method("getZramFreeKb", "()J", &zramFree),
        method("getDmabufTotalExportedKb", "()J", &dmaExported),
        method("getDmabufHeapTotalExportedKb", "()J", &dmaHeaps),
        method("getIonHeapsSizeKb", "()J", &ionHeaps),
        method("getDmabufHeapPoolsSizeKb", "()J", &dmaPools),
        method("getIonPoolsSizeKb", "()J", &ionPools),
        method("getGpuPrivateMemoryKb", "()J", &unavailableGpuMetric),
        method("getDmabufMappedSizeKb", "()J", &dmaMapped),
        method("getGpuTotalUsageKb", "()J", &unavailableGpuMetric),
        method("isVmapStack", "()Z", &vmapStack),
        method("logAllocatorStats", "()Z", &logAllocatorStats),
        method("getKernelCmaUsageKb", "()J", &cmaUsage),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
