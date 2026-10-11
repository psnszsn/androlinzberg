//! The host composer's cursor plane consumer. A device composer scans the
//! CURSOR layer out on a hardware cursor plane; this host has no scanout, and
//! the session's Wayland task presenter owns the seat's cursor surface, so the
//! plane's contents go there (IWaylandTaskPresenter.setDisplayCursor). Sessions
//! without a desktop have no presenter and keep no cursor.
const aidl = @import("aidl");
const Presenter = @import("task_presenter_aidl").@"dev.artzig.window.IWaylandTaskPresenter";

extern fn AServiceManager_checkService(instance: [*:0]const u8) callconv(.c) ?*aidl.AIBinder;

/// Only SurfaceFlinger's main thread presents the display.
var presenter: ?Presenter.Client = null;

/// Premultiplied ARGB `pixels` (width x height) with the sprite's hotspot, or
/// an empty plane (all zero) when Android shows no pointer.
pub export fn azk_host_composer_cursor(hotspot_x: i32, hotspot_y: i32, width: i32, height: i32, pixels: ?[*]const i32, count: usize) callconv(.c) void {
    const values: []const i32 = if (pixels) |p| p[0..count] else &.{};
    // One retry: a cached presenter may have died with its desktop session.
    for (0..2) |_| {
        const client = presenter orelse found: {
            const handle = AServiceManager_checkService("dev.artzig.window.presenter") orelse return;
            const value = Presenter.Client.fromBinder(.adopt(handle)) catch {
                aidl.Binder.adopt(handle).deinit();
                return;
            };
            presenter = value;
            break :found value;
        };
        client.setDisplayCursor(hotspot_x, hotspot_y, width, height, values) catch {
            client.deinit();
            presenter = null;
            continue;
        };
        return;
    }
}
