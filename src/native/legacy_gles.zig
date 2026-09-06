//! Native C ABI required by pinned GLImpl JNI. Android's GLES_CM dispatcher
//! uses Android EGL TLS; the host uses GLVND core exports and EGL extension
//! resolution. Signatures follow pinned GLES/glext.h; Bounds methods follow
//! GLES_CM/gl.cpp (the stock Java caller retains client buffers).
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var java_vm: std.atomic.Value(usize) = .init(0);
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;

pub export fn azk_register_legacy_gles_backend(env: Env) callconv(.c) c_int {
    var vm: [*c]jni.JavaVM = null;
    if (env.*.*.GetJavaVM.?(env, &vm) != jni.JNI_OK) return -1;
    java_vm.store(@intFromPtr(vm), .release);
    return 0;
}
fn unsupported(member: [*:0]const u8) void {
    const raw = java_vm.load(.acquire);
    if (raw == 0) return;
    const vm: [*c]jni.JavaVM = @ptrFromInt(raw);
    var raw_env: ?*anyopaque = null;
    if (vm.*.*.GetEnv.?(vm, &raw_env, jni.JNI_VERSION_1_6) != jni.JNI_OK) return;
    const env: Env = @ptrCast(@alignCast(raw_env orelse return));
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "com.google.android.gles_jni.GLImpl") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    const name = env.*.*.NewStringUTF.?(env, member) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, name);
    const detail = env.*.*.NewStringUTF.?(env, "The EGL driver does not expose this GLES extension") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, detail);
    const args = [_]jni.jvalue{ .{ .l = owner }, .{ .l = name }, .{ .l = detail } };
    const exception = env.*.*.CallStaticObjectMethodA.?(env, cls, method, &args) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.Throw.?(env, @ptrCast(exception));
}

fn resolve(comptime Function: type, comptime name: [:0]const u8) ?Function {
    // GLVND owns the dispatch cache. Resolve by symbol on each call so distinct
    // entry points cannot accidentally share a generic-local cache cell.
    const proc = eglGetProcAddress(name) orelse {
        unsupported(name);
        return null;
    };
    return @ptrCast(proc);
}

pub export fn glBindFramebufferOES(target: u32, framebuffer: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32) callconv(.c) void, "glBindFramebufferOES") orelse return;
    return operation(target, framebuffer);
}
pub export fn glBindRenderbufferOES(target: u32, renderbuffer: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32) callconv(.c) void, "glBindRenderbufferOES") orelse return;
    return operation(target, renderbuffer);
}
pub export fn glBlendEquationOES(mode: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32) callconv(.c) void, "glBlendEquationOES") orelse return;
    return operation(mode);
}
pub export fn glBlendEquationSeparateOES(modeRGB: u32, modeAlpha: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32) callconv(.c) void, "glBlendEquationSeparateOES") orelse return;
    return operation(modeRGB, modeAlpha);
}
pub export fn glBlendFuncSeparateOES(srcRGB: u32, dstRGB: u32, srcAlpha: u32, dstAlpha: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, u32, u32) callconv(.c) void, "glBlendFuncSeparateOES") orelse return;
    return operation(srcRGB, dstRGB, srcAlpha, dstAlpha);
}
pub export fn glCheckFramebufferStatusOES(target: u32) callconv(.c) u32 {
    const operation = resolve(*const fn (u32) callconv(.c) u32, "glCheckFramebufferStatusOES") orelse return 0;
    return operation(target);
}
pub export fn glCurrentPaletteMatrixOES(matrixpaletteindex: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32) callconv(.c) void, "glCurrentPaletteMatrixOES") orelse return;
    return operation(matrixpaletteindex);
}
pub export fn glDeleteFramebuffersOES(n: i32, framebuffers: [*c]const u32) callconv(.c) void {
    const operation = resolve(*const fn (i32, [*c]const u32) callconv(.c) void, "glDeleteFramebuffersOES") orelse return;
    return operation(n, framebuffers);
}
pub export fn glDeleteRenderbuffersOES(n: i32, renderbuffers: [*c]const u32) callconv(.c) void {
    const operation = resolve(*const fn (i32, [*c]const u32) callconv(.c) void, "glDeleteRenderbuffersOES") orelse return;
    return operation(n, renderbuffers);
}
pub export fn glDrawTexfOES(x: f32, y: f32, z: f32, width: f32, height: f32) callconv(.c) void {
    const operation = resolve(*const fn (f32, f32, f32, f32, f32) callconv(.c) void, "glDrawTexfOES") orelse return;
    return operation(x, y, z, width, height);
}
pub export fn glDrawTexfvOES(coords: [*c]const f32) callconv(.c) void {
    const operation = resolve(*const fn ([*c]const f32) callconv(.c) void, "glDrawTexfvOES") orelse return;
    return operation(coords);
}
pub export fn glDrawTexiOES(x: i32, y: i32, z: i32, width: i32, height: i32) callconv(.c) void {
    const operation = resolve(*const fn (i32, i32, i32, i32, i32) callconv(.c) void, "glDrawTexiOES") orelse return;
    return operation(x, y, z, width, height);
}
pub export fn glDrawTexivOES(coords: [*c]const i32) callconv(.c) void {
    const operation = resolve(*const fn ([*c]const i32) callconv(.c) void, "glDrawTexivOES") orelse return;
    return operation(coords);
}
pub export fn glDrawTexsOES(x: i16, y: i16, z: i16, width: i16, height: i16) callconv(.c) void {
    const operation = resolve(*const fn (i16, i16, i16, i16, i16) callconv(.c) void, "glDrawTexsOES") orelse return;
    return operation(x, y, z, width, height);
}
pub export fn glDrawTexsvOES(coords: [*c]const i16) callconv(.c) void {
    const operation = resolve(*const fn ([*c]const i16) callconv(.c) void, "glDrawTexsvOES") orelse return;
    return operation(coords);
}
pub export fn glDrawTexxOES(x: i32, y: i32, z: i32, width: i32, height: i32) callconv(.c) void {
    const operation = resolve(*const fn (i32, i32, i32, i32, i32) callconv(.c) void, "glDrawTexxOES") orelse return;
    return operation(x, y, z, width, height);
}
pub export fn glDrawTexxvOES(coords: [*c]const i32) callconv(.c) void {
    const operation = resolve(*const fn ([*c]const i32) callconv(.c) void, "glDrawTexxvOES") orelse return;
    return operation(coords);
}
pub export fn glFramebufferRenderbufferOES(target: u32, attachment: u32, renderbuffertarget: u32, renderbuffer: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, u32, u32) callconv(.c) void, "glFramebufferRenderbufferOES") orelse return;
    return operation(target, attachment, renderbuffertarget, renderbuffer);
}
pub export fn glFramebufferTexture2DOES(target: u32, attachment: u32, textarget: u32, texture: u32, level: i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, u32, u32, i32) callconv(.c) void, "glFramebufferTexture2DOES") orelse return;
    return operation(target, attachment, textarget, texture, level);
}
pub export fn glGenFramebuffersOES(n: i32, framebuffers: [*c]u32) callconv(.c) void {
    const operation = resolve(*const fn (i32, [*c]u32) callconv(.c) void, "glGenFramebuffersOES") orelse return;
    return operation(n, framebuffers);
}
pub export fn glGenRenderbuffersOES(n: i32, renderbuffers: [*c]u32) callconv(.c) void {
    const operation = resolve(*const fn (i32, [*c]u32) callconv(.c) void, "glGenRenderbuffersOES") orelse return;
    return operation(n, renderbuffers);
}
pub export fn glGenerateMipmapOES(target: u32) callconv(.c) void {
    const operation = resolve(*const fn (u32) callconv(.c) void, "glGenerateMipmapOES") orelse return;
    return operation(target);
}
pub export fn glGetFramebufferAttachmentParameterivOES(target: u32, attachment: u32, pname: u32, params: [*c]i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, u32, [*c]i32) callconv(.c) void, "glGetFramebufferAttachmentParameterivOES") orelse return;
    return operation(target, attachment, pname, params);
}
pub export fn glGetRenderbufferParameterivOES(target: u32, pname: u32, params: [*c]i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]i32) callconv(.c) void, "glGetRenderbufferParameterivOES") orelse return;
    return operation(target, pname, params);
}
pub export fn glGetTexGenfvOES(coord: u32, pname: u32, params: [*c]f32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]f32) callconv(.c) void, "glGetTexGenfvOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glGetTexGenivOES(coord: u32, pname: u32, params: [*c]i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]i32) callconv(.c) void, "glGetTexGenivOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glGetTexGenxvOES(coord: u32, pname: u32, params: [*c]i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]i32) callconv(.c) void, "glGetTexGenxvOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glIsFramebufferOES(framebuffer: u32) callconv(.c) u8 {
    const operation = resolve(*const fn (u32) callconv(.c) u8, "glIsFramebufferOES") orelse return 0;
    return operation(framebuffer);
}
pub export fn glIsRenderbufferOES(renderbuffer: u32) callconv(.c) u8 {
    const operation = resolve(*const fn (u32) callconv(.c) u8, "glIsRenderbufferOES") orelse return 0;
    return operation(renderbuffer);
}
pub export fn glLoadPaletteFromModelViewMatrixOES() callconv(.c) void {
    const operation = resolve(*const fn () callconv(.c) void, "glLoadPaletteFromModelViewMatrixOES") orelse return;
    return operation();
}
pub export fn glMatrixIndexPointerOES(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque) callconv(.c) void {
    const operation = resolve(*const fn (i32, u32, i32, ?*const anyopaque) callconv(.c) void, "glMatrixIndexPointerOES") orelse return;
    return operation(size, element_type, stride, pointer);
}
pub export fn glQueryMatrixxOES(mantissa: [*c]i32, exponent: [*c]i32) callconv(.c) u32 {
    const operation = resolve(*const fn ([*c]i32, [*c]i32) callconv(.c) u32, "glQueryMatrixxOES") orelse return 0;
    return operation(mantissa, exponent);
}
pub export fn glRenderbufferStorageOES(target: u32, internalformat: u32, width: i32, height: i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, i32, i32) callconv(.c) void, "glRenderbufferStorageOES") orelse return;
    return operation(target, internalformat, width, height);
}
pub export fn glTexGenfOES(coord: u32, pname: u32, param: f32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, f32) callconv(.c) void, "glTexGenfOES") orelse return;
    return operation(coord, pname, param);
}
pub export fn glTexGenfvOES(coord: u32, pname: u32, params: [*c]const f32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]const f32) callconv(.c) void, "glTexGenfvOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glTexGeniOES(coord: u32, pname: u32, param: i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, i32) callconv(.c) void, "glTexGeniOES") orelse return;
    return operation(coord, pname, param);
}
pub export fn glTexGenivOES(coord: u32, pname: u32, params: [*c]const i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]const i32) callconv(.c) void, "glTexGenivOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glTexGenxOES(coord: u32, pname: u32, param: i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, i32) callconv(.c) void, "glTexGenxOES") orelse return;
    return operation(coord, pname, param);
}
pub export fn glTexGenxvOES(coord: u32, pname: u32, params: [*c]const i32) callconv(.c) void {
    const operation = resolve(*const fn (u32, u32, [*c]const i32) callconv(.c) void, "glTexGenxvOES") orelse return;
    return operation(coord, pname, params);
}
pub export fn glWeightPointerOES(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque) callconv(.c) void {
    const operation = resolve(*const fn (i32, u32, i32, ?*const anyopaque) callconv(.c) void, "glWeightPointerOES") orelse return;
    return operation(size, element_type, stride, pointer);
}
extern "GLESv1_CM" fn glColorPointer(i32, u32, i32, ?*const anyopaque) void;
pub export fn glColorPointerBounds(size: i32, element_type: u32, stride: i32, ptr: ?*const anyopaque, _: i32) callconv(.c) void {
    glColorPointer(size, element_type, stride, ptr);
}
extern "GLESv1_CM" fn glNormalPointer(u32, i32, ?*const anyopaque) void;
pub export fn glNormalPointerBounds(element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glNormalPointer(element_type, stride, pointer);
}
extern "GLESv1_CM" fn glTexCoordPointer(i32, u32, i32, ?*const anyopaque) void;
pub export fn glTexCoordPointerBounds(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glTexCoordPointer(size, element_type, stride, pointer);
}
extern "GLESv1_CM" fn glVertexPointer(i32, u32, i32, ?*const anyopaque) void;
pub export fn glVertexPointerBounds(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glVertexPointer(size, element_type, stride, pointer);
}
extern "GLESv1_CM" fn glPointSizePointerOES(u32, i32, ?*const anyopaque) void;
pub export fn glPointSizePointerOESBounds(element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glPointSizePointerOES(element_type, stride, pointer);
}
pub export fn glMatrixIndexPointerOESBounds(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glMatrixIndexPointerOES(size, element_type, stride, pointer);
}
pub export fn glWeightPointerOESBounds(size: i32, element_type: u32, stride: i32, pointer: ?*const anyopaque, _: i32) callconv(.c) void {
    glWeightPointerOES(size, element_type, stride, pointer);
}
