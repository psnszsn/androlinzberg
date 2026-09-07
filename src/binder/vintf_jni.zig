//! android_os_VintfRuntimeInfo.cpp / libvintf RuntimeInfo-target.cpp.
//! Full libvintf also requires Android HAL metadata and kernel-config generators
//! which are not in this build. Preserve its independently fetched/cacheable
//! runtime-info groups and null/zero failure results. File
//! reads use std.Io; uname reuses the existing libcore Posix JNI implementation.
const std = @import("std");
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;
const allocator = std.heap.c_allocator;
var mutex: std.Io.Mutex = .init;
var os_class: jni.jclass = null;
var uname_method: jni.jmethodID = null;
var errno_class: jni.jclass = null;
var properties_class: jni.jclass = null;
var property_get: jni.jmethodID = null;
var uname_fields: [5]jni.jfieldID = @splat(null);
var version_values: [6]jni.jstring = @splat(null);
var cpu_info: jni.jstring = null;
var avb_values: [2]jni.jstring = @splat(null);
var policy_version: ?jni.jlong = null;

fn javaString(env: Env, bytes: []const u8) jni.jstring {
    const terminated = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    @memcpy(terminated, bytes);
    defer allocator.free(terminated);
    return env.*.*.NewStringUTF.?(env, terminated.ptr);
}

fn normalizedVersion(env: Env, value: jni.jstring, comptime count: usize, suffix: bool) jni.jstring {
    const utf = env.*.*.GetStringUTFChars.?(env, value, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, value, utf);
    var remaining = std.mem.span(utf);
    var numbers: [count]u64 = undefined;
    for (&numbers, 0..) |*number, index| {
        const end = if (index + 1 < count) std.mem.indexOfScalar(u8, remaining, '.') orelse return null else blk: {
            if (!suffix) break :blk remaining.len;
            var i: usize = 0;
            while (i < remaining.len and std.ascii.isDigit(remaining[i])) : (i += 1) {}
            break :blk i;
        };
        if (end == 0) return null;
        for (remaining[0..end]) |char| if (!std.ascii.isDigit(char)) return null;
        number.* = std.fmt.parseInt(u64, remaining[0..end], 10) catch return null;
        if (index + 1 < count) remaining = remaining[end + 1 ..];
    }
    var buffer: [80]u8 = undefined;
    const normalized = if (count == 3)
        std.fmt.bufPrint(&buffer, "{d}.{d}.{d}", .{ numbers[0], numbers[1], numbers[2] }) catch return null
    else
        std.fmt.bufPrint(&buffer, "{d}.{d}", .{ numbers[0], numbers[1] }) catch return null;
    return javaString(env, normalized);
}

fn cpuVersion(env: Env, comptime index: usize) jni.jstring {
    const io = ndk.frameworkIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (version_values[0] == null) {
        const uts = env.*.*.CallStaticObjectMethod.?(env, os_class, uname_method);
        if (env.*.*.ExceptionCheck.?(env) != 0) {
            const exception = env.*.*.ExceptionOccurred.?(env);
            env.*.*.ExceptionClear.?(env);
            if (env.*.*.IsInstanceOf.?(env, exception, errno_class) == 0) _ = env.*.*.Throw.?(env, exception);
            return null;
        }
        if (uts == null) return null;
        var values: [6]jni.jstring = @splat(null);
        for (uname_fields, 0..) |field, i| values[i] = env.*.*.GetObjectField.?(env, uts, field);
        if (values[2] == null) return null;
        values[5] = normalizedVersion(env, values[2], 3, true);
        if (values[5] == null) return null;
        for (values, 0..) |value, i| {
            version_values[i] = env.*.*.NewGlobalRef.?(env, value);
            if (version_values[i] == null) {
                for (version_values[0..i]) |ref| env.*.*.DeleteGlobalRef.?(env, ref);
                version_values = @splat(null);
                return null;
            }
        }
    }
    return env.*.*.NewLocalRef.?(env, version_values[index]);
}

fn osName(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 0);
}
fn nodeName(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 1);
}
fn osRelease(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 2);
}
fn osVersion(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 3);
}
fn hardwareId(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 4);
}
fn kernelVersion(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return cpuVersion(env, 5);
}

fn cpuInfo(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    const io = ndk.frameworkIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (cpu_info == null) {
        const data = std.Io.Dir.cwd().readFileAlloc(io, "/proc/cpuinfo", allocator, .limited(64 * 1024 * 1024)) catch return null;
        defer allocator.free(data);
        const value = javaString(env, data) orelse return null;
        cpu_info = env.*.*.NewGlobalRef.?(env, value);
    }
    return env.*.*.NewLocalRef.?(env, cpu_info);
}

fn sepolicyVersion(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    const io = ndk.frameworkIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (policy_version) |version| return version;
    const data = std.Io.Dir.cwd().readFileAlloc(io, "/sys/fs/selinux/policyvers", allocator, .limited(128)) catch return 0;
    defer allocator.free(data);
    const version = std.fmt.parseInt(u32, std.mem.trim(u8, data, " \t\r\n"), 10) catch return 0;
    policy_version = version;
    return version;
}

fn avbVersion(env: Env, comptime index: usize) jni.jstring {
    const io = ndk.frameworkIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (avb_values[0] == null) {
        var parsed: [2]jni.jstring = @splat(null);
        for ([_][:0]const u8{ "ro.boot.vbmeta.avb_version", "ro.boot.avb_version" }, 0..) |key, i| {
            const name = javaString(env, key) orelse return null;
            const default = javaString(env, "0.0") orelse return null;
            const value = env.*.*.CallStaticObjectMethod.?(env, properties_class, property_get, name, default);
            if (env.*.*.ExceptionCheck.?(env) != 0 or value == null) return null;
            parsed[i] = normalizedVersion(env, value, 2, false) orelse return null;
        }
        for (parsed, 0..) |value, i| {
            avb_values[i] = env.*.*.NewGlobalRef.?(env, value);
            if (avb_values[i] == null) {
                for (avb_values[0..i]) |ref| env.*.*.DeleteGlobalRef.?(env, ref);
                avb_values = @splat(null);
                return null;
            }
        }
    }
    return env.*.*.NewLocalRef.?(env, avb_values[index]);
}
fn bootAvb(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return avbVersion(env, 1);
}
fn bootVbmetaAvb(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return avbVersion(env, 0);
}

fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}

pub fn register(env: Env) !void {
    os_class = ndk.findGlobalClass(env, "android/system/Os") orelse return error.NoClass;
    errno_class = ndk.findGlobalClass(env, "android/system/ErrnoException") orelse return error.NoClass;
    uname_method = env.*.*.GetStaticMethodID.?(env, os_class, "uname", "()Landroid/system/StructUtsname;") orelse return error.NoMethod;
    properties_class = ndk.findGlobalClass(env, "android/os/SystemProperties") orelse return error.NoClass;
    property_get = env.*.*.GetStaticMethodID.?(env, properties_class, "get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") orelse return error.NoMethod;
    const uts = env.*.*.FindClass.?(env, "android/system/StructUtsname") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, uts);
    for ([_][:0]const u8{ "sysname", "nodename", "release", "version", "machine" }, 0..) |field, index| {
        uname_fields[index] = env.*.*.GetFieldID.?(env, uts, field, "Ljava/lang/String;") orelse return error.NoField;
    }
    const cls = env.*.*.FindClass.?(env, "android/os/VintfRuntimeInfo") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("getKernelSepolicyVersion", "()J", &sepolicyVersion),
        method("getCpuInfo", "()Ljava/lang/String;", &cpuInfo),
        method("getOsName", "()Ljava/lang/String;", &osName),
        method("getNodeName", "()Ljava/lang/String;", &nodeName),
        method("getOsRelease", "()Ljava/lang/String;", &osRelease),
        method("getOsVersion", "()Ljava/lang/String;", &osVersion),
        method("getHardwareId", "()Ljava/lang/String;", &hardwareId),
        method("getKernelVersion", "()Ljava/lang/String;", &kernelVersion),
        method("getBootAvbVersion", "()Ljava/lang/String;", &bootAvb),
        method("getBootVbmetaAvbVersion", "()Ljava/lang/String;", &bootVbmetaAvb),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    try registerObject(env);
}

// Pinned android_os_VintfObject.cpp and VintfObject::checkCompatibility.
// The hosted system has no Android vendor/framework HAL manifests or matrices.
// The original JNI additionally requires libvintf and UiAutomation's privileged
// `vintf` shell commands. Preserve its missing-metadata results at this seam;
// do not advertise a compatible vendor build or a fabricated policy version.
fn missingManifestStrings(env: Env, _: jni.jclass) callconv(.c) jni.jobjectArray {
    const string_class = env.*.*.FindClass.?(env, "java/lang/String") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, string_class);
    return env.*.*.NewObjectArray.?(env, 0, string_class, null);
}

fn missingManifestObject(_: Env, _: jni.jclass) callconv(.c) jni.jobject {
    return null;
}

fn missingManifestCompatibility(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return -19; // utils/Errors.h: NO_INIT (-ENODEV).
}

fn missingPlatformSepolicy(env: Env, _: jni.jclass) callconv(.c) jni.jstring {
    const exception = env.*.*.FindClass.?(env, "java/lang/RuntimeException") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.ThrowNew.?(env, exception, "Cannot get framework compatibility matrix");
    return null;
}

fn registerObject(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/os/VintfObject") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("report", "()[Ljava/lang/String;", &missingManifestStrings),
        method("verifyBuildAtBoot", "()I", &missingManifestCompatibility),
        method("getHalNamesAndVersions", "()[Ljava/lang/String;", &missingManifestStrings),
        method("getSepolicyVersion", "()Ljava/lang/String;", &missingManifestObject),
        method("getPlatformSepolicyVersion", "()Ljava/lang/String;", &missingPlatformSepolicy),
        method("getVndkSnapshots", "()Ljava/util/Map;", &missingManifestObject),
        method("getTargetFrameworkCompatibilityMatrixVersion", "()Ljava/lang/Long;", &missingManifestObject),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
