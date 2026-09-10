//! Share the platform file resolver with original Java/JNI resource clients.
//! libc_bio owns only mapped C-allocator buffers, retaining borrowed originals.

pub fn override(path: *[*:0]u8) callconv(.c) bool {
    const mapped = @import("host_paths.zig").azk_host_path(path.*) orelse return false;
    const owned = mapped != path.*;
    path.* = @constCast(mapped);
    return owned;
}
