//! Original libcamera_client units shared by framework JNI and native media services.
const Group = struct { root: []const u8, files: []const []const u8 };
pub const groups = [_]Group{
    .{ .root = "frameworks/native/libs/gui", .files = &.{"Flags.cpp"} },
    .{ .root = "frameworks/av/camera", .files = &.{ "CameraUtils.cpp", "Camera.cpp", "CameraBase.cpp", "CameraParameters.cpp", "CameraParameters2.cpp", "CameraSessionStats.cpp", "CaptureResult.cpp" } },
    .{ .root = "frameworks/av/camera", .files = &.{ "camera2/CaptureRequest.cpp", "camera2/ConcurrentCamera.cpp", "camera2/OutputConfiguration.cpp", "camera2/SessionConfiguration.cpp", "camera2/SubmitInfo.cpp" } },
};
