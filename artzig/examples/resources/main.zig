const std = @import("std");
const fw = @import("androidfw");

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) {
        std.debug.print("usage: {s} <apk> [type/name ...]\n", .{args[0]});
        return error.Usage;
    }
    const assets = fw.ApkAssets_load(args[1].ptr, true) orelse return error.LoadFailed;
    const am = fw.AssetManager_new() orelse return error.OutOfMemory;
    var list = [_]?*const fw.ApkAssets{assets};
    if (!fw.AssetManager_setApkAssets(am, &list, 1, true, false)) return error.SetApkAssets;

    const queries: []const [:0]const u8 = if (args.len > 2) args[2..] else &.{ "string/ok", "string/cancel", "color/white", "attr/textColor" };
    for (queries) |q| {
        const slash = std.mem.indexOfScalar(u8, q, '/') orelse continue;
        const typ = try init.arena.allocator().dupeSentinel(u8, q[0..slash], 0);
        const name = try init.arena.allocator().dupeSentinel(u8, q[slash + 1 ..], 0);
        const id = fw.AssetManager_getResourceId(am, name.ptr, typ.ptr, "android");
        var rn: fw.resource_name = undefined;
        const ok = fw.AssetManager_getResourceName(am, id, &rn);
        std.debug.print("{s}: id=0x{x} name_ok={} -> {s}:{s}/{s}\n", .{
            q, id, ok, field(rn.package, rn.package_len), field(rn.type, rn.type_len), field(rn.entry, rn.entry_len),
        });
    }
}

fn field(p: ?[*]const u8, len: usize) []const u8 {
    return if (p) |ptr| ptr[0..len] else "<utf16>";
}
