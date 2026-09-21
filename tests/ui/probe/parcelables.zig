//! `aidl_custom` of the presenter probe. It calls only presenter methods that
//! carry no Surface, so the type exists to satisfy the generated interface.
const aidl = @import("aidl");

pub const @"android.view.Surface" = struct {
    pub fn writeToParcel(_: @This(), _: aidl.Parcel) aidl.Error!void {
        return error.UnsupportedOperation;
    }
    pub fn readFromParcel(_: aidl.Parcel, _: aidl.Allocator) aidl.Error!@This() {
        return error.UnsupportedOperation;
    }
};
