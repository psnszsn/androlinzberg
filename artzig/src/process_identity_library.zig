//! Configure the libcore DSO before ART can cache the process identity.
const std = @import("std");
const art = @import("art.zig");
pub const Identity = struct {
    pub const Mapper = *const fn (i32, *u32, *u32) callconv(.c) void;
    uid: u32,
    gid: u32,
    map_credentials: ?Mapper = null,
};
pub const Library = struct {
    handle: *anyopaque,
    pub fn open(a: std.mem.Allocator, root: []const u8, identity: Identity) !Library {
        const path = try std.fs.path.joinZ(a, &.{ root, "lib64", "libjavacore.so" });
        defer a.free(path);
        const loader = try art.HostLoader.init(root);
        const handle = loader.dlopen(path, 2, null) orelse return error.ProcessIdentityLibraryLoadFailed;
        errdefer _ = dlclose(handle);
        const Initialize = *const fn (u32, u32, ?Identity.Mapper) callconv(.c) c_int;
        const initialize: Initialize = @ptrCast(loader.dlsym(handle, "azk_process_identity_initialize") orelse
            return error.ProcessIdentitySymbolNotFound);
        if (initialize(identity.uid, identity.gid, identity.map_credentials) != 0) return error.ProcessIdentityAlreadyInitialized;
        return .{ .handle = handle };
    }
    pub fn close(self: Library) void {
        _ = dlclose(self.handle);
    }
    extern "c" fn dlclose(handle: *anyopaque) c_int;
};
