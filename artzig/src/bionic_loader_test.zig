const std = @import("std");
extern fn bionic_dlopen(?[*:0]const u8, c_int) callconv(.c) ?*anyopaque;
extern fn bionic_dlsym(?*anyopaque, [*:0]const u8) callconv(.c) ?*anyopaque;
extern fn bionic_dlclose(*anyopaque) callconv(.c) c_int;
extern fn bionic_dlerror() callconv(.c) ?[*:0]const u8;

// An executable export models a host-loaded platform API. The bridge must
// discover the very same callable function through the process handle.
export fn artzig_loader_test_operation(value: u32) callconv(.c) u32 {
    return value + 17;
}

fn require(ok: bool) !void {
    if (!ok) return error.NativeLoaderContractFailed;
}

pub fn main() !void {
    const first = bionic_dlopen(null, 2) orelse return error.MissingMainHandle;
    defer _ = bionic_dlclose(first);
    const second = bionic_dlopen(null, 2) orelse return error.MissingMainHandle;
    const by_handle = bionic_dlsym(second, "artzig_loader_test_operation") orelse return error.MissingProcessExport;
    try require(by_handle == @as(*const anyopaque, @ptrCast(&artzig_loader_test_operation)));
    const operation: *const fn (u32) callconv(.c) u32 = @ptrCast(by_handle);
    try require(operation(25) == 42);
    try require(bionic_dlclose(second) == 0);
    try require(bionic_dlsym(first, "artzig_loader_test_operation") == by_handle);
    try require(bionic_dlsym(null, "artzig_loader_test_operation") == by_handle);
    try require(bionic_dlsym(first, "artzig_missing_process_export") == null);
    try require(bionic_dlerror() != null);
    try require(bionic_dlerror() == null);
    try require(bionic_dlsym(null, "artzig_missing_process_export") == null);
    try require(bionic_dlerror() != null);
    const math = bionic_dlopen("libm.so.6", 2) orelse return error.MissingHostLibrary;
    defer _ = bionic_dlclose(math);
    const cos: *const fn (f64) callconv(.c) f64 = @ptrCast(bionic_dlsym(math, "cos") orelse return error.MissingHostExport);
    try require(cos(0) == 1);
    // Explicit DSO lookup must not fall back to unrelated process globals.
    try require(bionic_dlsym(math, "artzig_loader_test_operation") == null);
    try require(bionic_dlerror() != null);
    std.debug.print("Bionic process symbol lookup: PASS\n", .{});
}
