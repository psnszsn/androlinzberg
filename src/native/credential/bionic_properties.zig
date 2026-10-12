//! Bionic's system property ABI for the glibc-hosted credential daemons. Their
//! stock Rust and C++ (librustutils, libcutils) call the bionic names, which
//! libbinder_ndk implements over the session property area under prefixed
//! names (src/binder/native_properties.zig) so that application processes'
//! bionic loader keeps its own resolution.
const Info = opaque {};
const Callback = *const fn (?*anyopaque, [*:0]const u8, [*:0]const u8, u32) callconv(.c) void;
const ForeachCallback = *const fn (*const Info, ?*anyopaque) callconv(.c) void;
const Timespec = extern struct { sec: isize, nsec: isize };

extern fn bionic___system_property_find(name: ?[*:0]const u8) callconv(.c) ?*const Info;
extern fn bionic___system_property_read(info: ?*const Info, name: ?[*]u8, value: ?[*]u8) callconv(.c) c_int;
extern fn bionic___system_property_read_callback(info: ?*const Info, callback: Callback, cookie: ?*anyopaque) callconv(.c) void;
extern fn bionic___system_property_serial(info: ?*const Info) callconv(.c) u32;
extern fn bionic___system_property_area_serial() callconv(.c) u32;
extern fn bionic___system_property_foreach(callback: ForeachCallback, cookie: ?*anyopaque) callconv(.c) c_int;
extern fn bionic___system_property_wait(info: ?*const Info, old_serial: u32, new_serial: ?*u32, timeout: ?*const Timespec) callconv(.c) bool;

export fn __system_property_find(name: ?[*:0]const u8) callconv(.c) ?*const Info {
    return bionic___system_property_find(name);
}
export fn __system_property_read(info: ?*const Info, name: ?[*]u8, value: ?[*]u8) callconv(.c) c_int {
    return bionic___system_property_read(info, name, value);
}
export fn __system_property_read_callback(info: ?*const Info, callback: Callback, cookie: ?*anyopaque) callconv(.c) void {
    bionic___system_property_read_callback(info, callback, cookie);
}
export fn __system_property_serial(info: ?*const Info) callconv(.c) u32 {
    return bionic___system_property_serial(info);
}
export fn __system_property_area_serial() callconv(.c) u32 {
    return bionic___system_property_area_serial();
}
export fn __system_property_foreach(callback: ForeachCallback, cookie: ?*anyopaque) callconv(.c) c_int {
    return bionic___system_property_foreach(callback, cookie);
}
export fn __system_property_wait(info: ?*const Info, old_serial: u32, new_serial: ?*u32, timeout: ?*const Timespec) callconv(.c) bool {
    return bionic___system_property_wait(info, old_serial, new_serial, timeout);
}
