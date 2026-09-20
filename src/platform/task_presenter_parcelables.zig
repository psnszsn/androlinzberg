//! Unstructured parcelables of the task presenter's AIDL (`aidl_custom`).
const aidl = @import("aidl");
const Queue = @import("surface_queue");

/// `android.view.Surface` as the presenter returns it: the producer side of a
/// queue it owns. libgui defines the wire form (view::Surface::writeToParcel),
/// so the queue's C++ half writes it. The presenter never receives a Surface.
pub const @"android.view.Surface" = struct {
    queue: *Queue,

    pub fn writeToParcel(self: @This(), parcel: aidl.Parcel) aidl.Error!void {
        if (self.queue.writeSurface(parcel.raw) != aidl.status_ok) return error.BadParcelable;
    }
    pub fn readFromParcel(_: aidl.Parcel, _: aidl.Allocator) aidl.Error!@This() {
        return error.UnsupportedOperation;
    }
};
