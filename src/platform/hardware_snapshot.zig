//! Stock HardwareRenderer snapshot JNI over HardwareBufferRenderer. Native
//! RenderNode retention transfers ownership safely across the Java adoption.
const art = @import("art");
const jni = art.jni;
pub const RetainRenderNodeFn = *const fn (node: *anyopaque) callconv(.c) void;
pub const Bridges = struct { retain_render_node: RetainRenderNodeFn, release_render_node: RetainRenderNodeFn };
var bridges: ?Bridges = null;

fn nativeSnapshot(env: [*c]jni.JNIEnv, _: jni.jclass, node: jni.jlong, width: jni.jint, height: jni.jint) callconv(.c) jni.jobject {
    if (node == 0 or width <= 0 or height <= 0) return null;
    const bridge = bridges orelse return null;
    // Retain before any JNI lookup can collect the caller's Java RenderNode.
    const pointer: *anyopaque = @ptrFromInt(@as(usize, @bitCast(node)));
    bridge.retain_render_node(pointer);
    var adopted = false;
    defer if (!adopted) bridge.release_render_node(pointer);
    const node_class = env.*.*.FindClass.?(env, "android/graphics/RenderNode") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, node_class);
    const adopt = env.*.*.GetStaticMethodID.?(env, node_class, "adopt", "(J)Landroid/graphics/RenderNode;") orelse return null;
    const node_args = [_]jni.jvalue{.{ .j = node }};
    const owned = env.*.*.CallStaticObjectMethodA.?(env, node_class, adopt, &node_args) orelse return null;
    adopted = true;
    defer env.*.*.DeleteLocalRef.?(env, owned);
    const cls = env.*.*.FindClass.?(env, "dev/artzig/HostedHardwareSnapshot") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const create = env.*.*.GetStaticMethodID.?(env, cls, "create", "(Landroid/graphics/RenderNode;II)Landroid/graphics/Bitmap;") orelse return null;
    const args = [_]jni.jvalue{ .{ .l = owned }, .{ .i = width }, .{ .i = height } };
    return env.*.*.CallStaticObjectMethodA.?(env, cls, create, &args);
}

pub fn register(env: [*c]jni.JNIEnv, hwui_bridges: Bridges) !void {
    const hardware_renderer = env.*.*.FindClass.?(env, "android/graphics/HardwareRenderer") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, hardware_renderer);
    bridges = hwui_bridges;
    // Pinned snapshot JNI is Android-only; reuse the hosted HardwareBufferRenderer.
    const snapshots = [_]jni.JNINativeMethod{
        .{ .name = "nCreateHardwareBitmap", .signature = "(JII)Landroid/graphics/Bitmap;", .fnPtr = @ptrCast(@constCast(&nativeSnapshot)) },
    };
    if (env.*.*.RegisterNatives.?(env, hardware_renderer, &snapshots, snapshots.len) != 0) return error.RegisterFailed;
}

test "snapshot bridge ABI compiles" {
    _ = &register;
}
