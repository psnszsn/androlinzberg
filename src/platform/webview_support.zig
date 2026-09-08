//! Keep the stock platform-support registration in the provider's class loader.
const jni = @import("art").jni;
extern fn azk_webview_JNI_OnLoad(vm: ?*anyopaque, reserved: ?*anyopaque) callconv(.c) jni.jint;
export fn JNI_OnLoad(vm: ?*anyopaque, reserved: ?*anyopaque) callconv(.c) jni.jint {
    return azk_webview_JNI_OnLoad(vm, reserved);
}
