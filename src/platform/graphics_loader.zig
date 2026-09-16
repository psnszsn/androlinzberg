//! Resolve Android's source-linked libgui name to its existing ELF owner.
//! Original ANativeWindow owns parcel loading; never create a second libgui state.
const std = @import("std");
extern "c" fn dlopen(?[*:0]const u8, c_int) ?*anyopaque;
extern "c" fn dlsym(?*anyopaque, [*:0]const u8) ?*const anyopaque;
extern "c" fn dlclose(*anyopaque) c_int;
const DlInfo = extern struct {
    filename: ?[*:0]const u8,
    base: ?*anyopaque,
    symbol_name: ?[*:0]const u8,
    symbol: ?*const anyopaque,
};
extern "c" fn dladdr(*const anyopaque, *DlInfo) c_int;
extern "c" fn android_view_Surface_writeToParcel(*anyopaque, *anyopaque) i32;
extern "c" fn android_view_Surface_readFromParcel(*const anyopaque, *?*anyopaque) i32;
export fn azk_graphics_dlopen(name: ?[*:0]const u8, flags: c_int) ?*anyopaque {
    const library = name orelse return dlopen(null, flags);
    if (!std.mem.eql(u8, std.mem.span(library), "libgui.so")) return dlopen(library, flags);
    const write: *const anyopaque = @ptrCast(&android_view_Surface_writeToParcel);
    const read: *const anyopaque = @ptrCast(&android_view_Surface_readFromParcel);
    var owner: DlInfo = undefined;
    if (dladdr(write, &owner) == 0) return null;
    // Standalone native tests source-link libgui in a PIE, which cannot itself
    // be dlopened. Its explicit parcel exports must resolve to these same owners.
    const handle = dlopen(owner.filename orelse return null, flags) orelse dlopen(null, flags) orelse return null;
    if (dlsym(handle, "android_view_Surface_writeToParcel") != write or
        dlsym(handle, "android_view_Surface_readFromParcel") != read)
    {
        _ = dlclose(handle);
        return null;
    }
    return handle;
}
