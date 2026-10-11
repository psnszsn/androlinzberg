//! `aidl_custom` of IWaylandTaskPresenter clients that call only methods
//! carrying no Surface (SurfaceFlinger's cursor plane). The type exists to
//! satisfy the generated interface.
const aidl = @import("aidl");

pub const @"android.view.Surface" = struct {
    pub fn writeToParcel(_: @This(), _: aidl.Parcel) aidl.Error!void {
        return error.UnsupportedOperation;
    }
    pub fn readFromParcel(_: aidl.Parcel, _: aidl.Allocator) aidl.Error!@This() {
        return error.UnsupportedOperation;
    }
};
