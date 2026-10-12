//! The libselinux calls keystore2 makes (system/security/keystore2/selinux),
//! answered as Android answers them on a kernel without SELinux. The host
//! kernel enforces no Android policy, so access checks permit, as
//! libselinux's selinux_check_access does when SELinux is disabled; keystore2's
//! uid, namespace and grant checks still apply. keystore2 runs in its init
//! domain, and key namespaces map to their labels through the stock
//! keystore2_key_contexts, installed as the system image installs it.
const std = @import("std");

const allocator = std.heap.c_allocator;
const own_context = "u:r:keystore:s0";
const contexts_path = "bin/art/etc/selinux/plat_keystore2_key_contexts";

extern fn getenv(name: [*:0]const u8) ?[*:0]const u8;

var io_state: std.Io.Threaded = .init_single_threaded;

fn duplicate(text: []const u8) ?[*:0]u8 {
    const copy = allocator.dupeSentinel(u8, text, 0) catch return null;
    return copy.ptr;
}

pub export fn getcon(context: *?[*:0]u8) callconv(.c) c_int {
    context.* = duplicate(own_context) orelse return -1;
    return 0;
}

pub export fn freecon(context: ?[*:0]u8) callconv(.c) void {
    const text = context orelse return;
    allocator.free(std.mem.span(text));
}

/// Changing domains needs the kernel's SELinux; libselinux fails without it.
pub export fn setcon(context: ?[*:0]const u8) callconv(.c) c_int {
    _ = context;
    return -1;
}

pub export fn selinux_check_access(source: ?[*:0]const u8, target: ?[*:0]const u8, class: ?[*:0]const u8, permission: ?[*:0]const u8, auxiliary: ?*anyopaque) callconv(.c) c_int {
    _ = source;
    _ = target;
    _ = class;
    _ = permission;
    _ = auxiliary;
    return 0;
}

/// keystore2 routes libselinux's log callback to logcat; nothing logs here.
pub export fn selinux_set_callback(kind: c_int, callback: ?*const anyopaque) callconv(.c) void {
    _ = kind;
    _ = callback;
}

/// Android libselinux's logcat callback, which keystore2 installs. Its
/// variadic arguments are not read: this backend emits no messages.
pub export fn selinux_log_callback(kind: c_int, format: [*:0]const u8) callconv(.c) c_int {
    _ = kind;
    _ = format;
    return 0;
}

const Entry = struct { key: []const u8, context: []const u8 };
const Handle = struct { text: []u8, entries: []Entry };

/// selabel's ANDROID_KEYSTORE2_KEY backend over plat_keystore2_key_contexts:
/// `<namespace> <context>` lines with `#` comments.
pub export fn selinux_android_keystore2_key_context_handle() callconv(.c) ?*Handle {
    const root = getenv("ARTZIG_PLATFORM_ROOT") orelse return null;
    const io = io_state.io();
    const path = std.fs.path.join(allocator, &.{ std.mem.span(root), contexts_path }) catch return null;
    defer allocator.free(path);
    const text = std.Io.Dir.cwd().readFileAlloc(io, path, allocator, .limited(1 << 20)) catch return null;
    var entries: std.ArrayList(Entry) = .empty;
    var lines = std.mem.tokenizeScalar(u8, text, '\n');
    while (lines.next()) |line| {
        const content = std.mem.trim(u8, line[0 .. std.mem.indexOfScalar(u8, line, '#') orelse line.len], " \t\r");
        var fields = std.mem.tokenizeAny(u8, content, " \t");
        const key = fields.next() orelse continue;
        const context = fields.next() orelse continue;
        entries.append(allocator, .{ .key = key, .context = context }) catch {
            entries.deinit(allocator);
            allocator.free(text);
            return null;
        };
    }
    const handle = allocator.create(Handle) catch return null;
    handle.* = .{ .text = text, .entries = entries.toOwnedSlice(allocator) catch return null };
    return handle;
}

pub export fn selabel_lookup(handle: *Handle, context: *?[*:0]u8, key: [*:0]const u8, kind: c_int) callconv(.c) c_int {
    _ = kind;
    const name = std.mem.span(key);
    for (handle.entries) |entry| if (std.mem.eql(u8, entry.key, name)) {
        context.* = duplicate(entry.context) orelse return -1;
        return 0;
    };
    return -1;
}

pub export fn selabel_close(handle: *Handle) callconv(.c) void {
    allocator.free(handle.entries);
    allocator.free(handle.text);
    allocator.destroy(handle);
}
