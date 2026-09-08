//! Pinned android_media_MediaCodec.cpp signatures and direct-buffer view ownership.
//! HostedMediaCodecBackend owns the existing decoder; stock Java owns returned views
//! and callback delivery. Each JNI view has an independent accessibility lifetime.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var codec_class: jni.jclass = null;
var context: jni.jfieldID = null;
var post_event: jni.jmethodID = null;
var rendered_handler: jni.jfieldID = null;
var block_class: jni.jclass = null;
var block_state: jni.jmethodID = null;

fn method(env: Env, name: [*:0]const u8, signature: [*:0]const u8) jni.jmethodID {
    return env.*.*.GetStaticMethodID.?(env, backend, name, signature);
}
fn blockOperation(env: Env, operation: [*:0]const u8) void {
    const id = method(env, "linearBlockOperation", "(Ljava/lang/String;)V") orelse return;
    const name = env.*.*.NewStringUTF.?(env, operation) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, name);
    const args = [_]jni.jvalue{.{ .l = name }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}
fn blockMap(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    blockOperation(env, "map");
    return null;
}
fn blockObtain(env: Env, _: jni.jobject, _: jni.jint, _: jni.jobject) callconv(.c) void {
    blockOperation(env, "obtain");
}
fn blockRecycle(env: Env, object: jni.jobject) callconv(.c) void {
    // Original recycle resets internal state and deletes the native context.
    // This decoder creates no block context, including after failed obtain.
    const args = [_]jni.jvalue{ .{ .j = 0 }, .{ .z = 0 } };
    env.*.*.CallVoidMethodA.?(env, object, block_state, &args);
}
fn blockCompatible(_: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jboolean {
    // The existing decoder never consumes LinearBlock storage without copying.
    return 0;
}
fn native_init(env: Env, cls: jni.jclass) callconv(.c) void {
    context = env.*.*.GetFieldID.?(env, cls, "mNativeContext", "J");
    post_event = env.*.*.GetMethodID.?(env, cls, "postEventFromNative", "(IIILjava/lang/Object;)V");
    rendered_handler = env.*.*.GetFieldID.?(env, cls, "mOnFrameRenderedHandler", "Landroid/media/MediaCodec$EventHandler;");
}
fn codecException(env: Env, _: jni.jclass, code: jni.jint, action: jni.jint, message: jni.jobject) callconv(.c) jni.jobject {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodec$CodecException") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const ctor = env.*.*.GetMethodID.?(env, cls, "<init>", "(IILjava/lang/String;)V") orelse return null;
    const args = [_]jni.jvalue{ .{ .i = code }, .{ .i = action }, .{ .l = message } };
    return env.*.*.NewObjectA.?(env, cls, ctor, &args);
}
fn postEvent(env: Env, _: jni.jclass, codec: jni.jobject, what: jni.jint, arg1: jni.jint, arg2: jni.jint, value: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .i = what }, .{ .i = arg1 }, .{ .i = arg2 }, .{ .l = value } };
    env.*.*.CallVoidMethodA.?(env, codec, post_event, &args);
}

// Follow CreateByteBuffer: independent view, output read-only, native byte order,
// and the current native range. The owner remains retained by the codec state.
fn bufferView(env: Env, owner: jni.jobject, input: bool) jni.jobject {
    if (owner == null) return null;
    const address = env.*.*.GetDirectBufferAddress.?(env, owner) orelse return null;
    const capacity = env.*.*.GetDirectBufferCapacity.?(env, owner);
    const cls = env.*.*.FindClass.?(env, "java/nio/ByteBuffer") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    var view = env.*.*.NewDirectByteBuffer.?(env, address, capacity) orelse return null;
    if (!input) {
        const readonly = env.*.*.GetMethodID.?(env, cls, "asReadOnlyBuffer", "()Ljava/nio/ByteBuffer;") orelse return null;
        const converted = env.*.*.CallObjectMethodA.?(env, view, readonly, null);
        env.*.*.DeleteLocalRef.?(env, view);
        view = converted orelse return null;
    }
    const order_class = env.*.*.FindClass.?(env, "java/nio/ByteOrder") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, order_class);
    const native_order = env.*.*.GetStaticMethodID.?(env, order_class, "nativeOrder", "()Ljava/nio/ByteOrder;") orelse return null;
    const order = env.*.*.CallStaticObjectMethodA.?(env, order_class, native_order, null) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, order);
    const order_method = env.*.*.GetMethodID.?(env, cls, "order", "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;") orelse return null;
    const order_args = [_]jni.jvalue{.{ .l = order }};
    env.*.*.DeleteLocalRef.?(env, env.*.*.CallObjectMethodA.?(env, view, order_method, &order_args));
    if (!input) {
        inline for (.{ "limit", "position" }) |name| {
            const getter = env.*.*.GetMethodID.?(env, cls, name, "()I") orelse return null;
            const setter = env.*.*.GetMethodID.?(env, cls, name, "(I)Ljava/nio/Buffer;") orelse return null;
            const value = env.*.*.CallIntMethodA.?(env, owner, getter, null);
            const args = [_]jni.jvalue{.{ .i = value }};
            env.*.*.DeleteLocalRef.?(env, env.*.*.CallObjectMethodA.?(env, view, setter, &args));
        }
    }
    return view;
}
fn bufferViews(env: Env, owners: jni.jobject, input: bool) jni.jobject {
    if (owners == null) return null;
    const count = env.*.*.GetArrayLength.?(env, @ptrCast(owners));
    const cls = env.*.*.FindClass.?(env, "java/nio/ByteBuffer") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const result = env.*.*.NewObjectArray.?(env, count, cls, null) orelse return null;
    var index: jni.jsize = 0;
    while (index < count) : (index += 1) {
        const owner = env.*.*.GetObjectArrayElement.?(env, @ptrCast(owners), index);
        const view = bufferView(env, owner, input);
        env.*.*.DeleteLocalRef.?(env, owner);
        env.*.*.SetObjectArrayElement.?(env, result, index, view);
        env.*.*.DeleteLocalRef.?(env, view);
        if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    }
    return @ptrCast(result);
}

fn native_release(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "release", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
    env.*.*.SetLongField.?(env, object, context, 0);
}

fn native_reset(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "reset", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_createPersistentInputSurface(env: Env, _: jni.jclass) callconv(.c) jni.jobject {
    const id = method(env, "createPersistentInputSurface", "()Landroid/view/Surface;") orelse return null;
    const args = [_]jni.jvalue{};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_setInputSurface(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "setInputSurface", "(JLandroid/view/Surface;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_enableOnFirstTunnelFrameReadyListener(env: Env, object: jni.jobject, a0: jni.jboolean) callconv(.c) void {
    const id = method(env, "enableOnFirstTunnelFrameReadyListener", "(JZ)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_enableOnFrameRenderedListener(env: Env, object: jni.jobject, a0: jni.jboolean) callconv(.c) void {
    const id = method(env, "enableOnFrameRenderedListener", "(JZLandroid/os/Handler;)V") orelse return;
    // Stock Java has already selected this handler while holding mListenerLock.
    // Keep native polling on that looper so queued timestamp notifications remain bounded.
    const handler = env.*.*.GetObjectField.?(env, object, rendered_handler);
    defer if (handler != null) env.*.*.DeleteLocalRef.?(env, handler);
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 }, .{ .l = handler } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setCallback(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "setCallback", "(JLandroid/media/MediaCodec$Callback;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_configure(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jobject, a2: jni.jobject, a3: jni.jobject, a4: jni.jobject, a5: jni.jint) callconv(.c) void {
    const id = method(env, "configure", "(J[Ljava/lang/String;[Ljava/lang/Object;Landroid/view/Surface;Landroid/media/MediaCrypto;Landroid/os/IHwBinder;I)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .l = a1 }, .{ .l = a2 }, .{ .l = a3 }, .{ .l = a4 }, .{ .i = a5 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setSurface(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "setSurface", "(JLandroid/view/Surface;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_detachOutputSurface(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "detachOutputSurface", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn createInputSurface(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "createInputSurface", "(J)Landroid/view/Surface;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_start(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "start", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_stop(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "stop", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_flush(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "flush", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueInputBuffer(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint, a2: jni.jint, a3: jni.jlong, a4: jni.jint) callconv(.c) void {
    const id = method(env, "queueInputBuffer", "(JIIIJI)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .j = a3 }, .{ .i = a4 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueInputBuffers(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jobject) callconv(.c) void {
    const id = method(env, "queueInputBuffers", "(JI[Ljava/lang/Object;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .l = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueSecureInputBuffer(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint, a2: jni.jobject, a3: jni.jlong, a4: jni.jint) callconv(.c) void {
    const id = method(env, "queueSecureInputBuffer", "(JIILandroid/media/MediaCodec$CryptoInfo;JI)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 }, .{ .l = a2 }, .{ .j = a3 }, .{ .i = a4 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueSecureInputBuffers(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jobject, a2: jni.jobject) callconv(.c) void {
    const id = method(env, "queueSecureInputBuffers", "(JI[Ljava/lang/Object;[Ljava/lang/Object;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .l = a1 }, .{ .l = a2 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_mapHardwareBuffer(env: Env, _: jni.jclass, a0: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "mapHardwareBuffer", "(Landroid/hardware/HardwareBuffer;)Landroid/media/Image;") orelse return null;
    const args = [_]jni.jvalue{.{ .l = a0 }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_closeMediaImage(env: Env, _: jni.jclass, a0: jni.jlong) callconv(.c) void {
    const id = method(env, "closeMediaImage", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = a0 }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueLinearBlock(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jobject, a2: jni.jobject, a3: jni.jobject, a4: jni.jobject, a5: jni.jobject) callconv(.c) void {
    const id = method(env, "queueLinearBlock", "(JILandroid/media/MediaCodec$LinearBlock;[Ljava/lang/Object;[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/util/ArrayList;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .l = a1 }, .{ .l = a2 }, .{ .l = a3 }, .{ .l = a4 }, .{ .l = a5 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_queueHardwareBuffer(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jobject, a2: jni.jlong, a3: jni.jint, a4: jni.jobject, a5: jni.jobject) callconv(.c) void {
    const id = method(env, "queueHardwareBuffer", "(JILandroid/hardware/HardwareBuffer;JILjava/util/ArrayList;Ljava/util/ArrayList;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .l = a1 }, .{ .j = a2 }, .{ .i = a3 }, .{ .l = a4 }, .{ .l = a5 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_getOutputFrame(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jint) callconv(.c) void {
    const id = method(env, "getOutputFrame", "(JLandroid/media/MediaCodec$OutputFrame;I)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .i = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_dequeueInputBuffer(env: Env, object: jni.jobject, a0: jni.jlong) callconv(.c) jni.jint {
    const id = method(env, "dequeueInputBuffer", "(JJ)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .j = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_dequeueOutputBuffer(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jlong) callconv(.c) jni.jint {
    const id = method(env, "dequeueOutputBuffer", "(JLandroid/media/MediaCodec$BufferInfo;J)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .j = a1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn releaseOutputBuffer(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jboolean, a2: jni.jboolean, a3: jni.jlong) callconv(.c) void {
    const id = method(env, "releaseOutputBuffer", "(JIZZJ)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .z = a1 }, .{ .z = a2 }, .{ .j = a3 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn signalEndOfInputStream(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "signalEndOfInputStream", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn getFormatNative(env: Env, object: jni.jobject, a0: jni.jboolean) callconv(.c) jni.jobject {
    const id = method(env, "getFormatNative", "(JZ)Ljava/util/Map;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn getOutputFormatNative(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jobject {
    const id = method(env, "getOutputFormatNative", "(JI)Ljava/util/Map;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn getBuffers(env: Env, object: jni.jobject, a0: jni.jboolean) callconv(.c) jni.jobject {
    const id = method(env, "getBuffers", "(JZ)[Ljava/nio/ByteBuffer;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 } };
    const owner = env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    return bufferViews(env, owner, a0 != 0);
}

fn getBuffer(env: Env, object: jni.jobject, a0: jni.jboolean, a1: jni.jint) callconv(.c) jni.jobject {
    const id = method(env, "getBuffer", "(JZI)Ljava/nio/ByteBuffer;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 }, .{ .i = a1 } };
    const owner = env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    return bufferView(env, owner, a0 != 0);
}

fn getImage(env: Env, object: jni.jobject, a0: jni.jboolean, a1: jni.jint) callconv(.c) jni.jobject {
    const id = method(env, "getImage", "(JZI)Landroid/media/Image;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .z = a0 }, .{ .i = a1 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn getCanonicalName(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getCanonicalName", "(J)Ljava/lang/String;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn getOwnCodecInfo(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getOwnCodecInfo", "(J)Landroid/media/MediaCodecInfo;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_getMetrics(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getMetrics", "(J)Landroid/os/PersistableBundle;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn setParameters(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jobject) callconv(.c) void {
    const id = method(env, "setParameters", "(J[Ljava/lang/String;[Ljava/lang/Object;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .l = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn setVideoScalingMode(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) void {
    const id = method(env, "setVideoScalingMode", "(JI)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setAudioPresentation(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint) callconv(.c) void {
    const id = method(env, "setAudioPresentation", "(JII)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_getSupportedVendorParameters(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getSupportedVendorParameters", "(J)Ljava/util/List;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_getParameterDescriptor(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getParameterDescriptor", "(JLjava/lang/String;)Landroid/media/MediaCodec$ParameterDescriptor;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_subscribeToVendorParameters(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "subscribeToVendorParameters", "(JLjava/util/List;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_unsubscribeFromVendorParameters(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "unsubscribeFromVendorParameters", "(JLjava/util/List;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setup(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jboolean, a2: jni.jboolean, a3: jni.jint, a4: jni.jint) callconv(.c) void {
    const id = method(env, "setup", "(Landroid/media/MediaCodec;Ljava/lang/String;ZZII)J") orelse return;
    const args = [_]jni.jvalue{ .{ .l = object }, .{ .l = a0 }, .{ .z = a1 }, .{ .z = a2 }, .{ .i = a3 }, .{ .i = a4 } };
    const handle = env.*.*.CallStaticLongMethodA.?(env, backend, id, &args);
    if (env.*.*.ExceptionCheck.?(env) == 0) env.*.*.SetLongField.?(env, object, context, handle);
}

fn native_finalize(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "release", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
    env.*.*.SetLongField.?(env, object, context, 0);
}

fn native_getGloballyAvailableResources(env: Env, _: jni.jclass) callconv(.c) jni.jobject {
    const id = method(env, "getGloballyAvailableResources", "()Ljava/util/List;") orelse return null;
    const args = [_]jni.jvalue{};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_getRequiredResources(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getRequiredResources", "(J)Ljava/util/List;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodec") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    codec_class = @ptrCast(env.*.*.NewGlobalRef.?(env, cls) orelse return error.NoClass);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "native_release", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_release)) },
        .{ .name = "native_reset", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_reset)) },
        .{ .name = "native_createPersistentInputSurface", .signature = "()Landroid/media/MediaCodec$PersistentSurface;", .fnPtr = @ptrCast(@constCast(&native_createPersistentInputSurface)) },
        .{ .name = "native_setInputSurface", .signature = "(Landroid/view/Surface;)V", .fnPtr = @ptrCast(@constCast(&native_setInputSurface)) },
        .{ .name = "native_enableOnFirstTunnelFrameReadyListener", .signature = "(Z)V", .fnPtr = @ptrCast(@constCast(&native_enableOnFirstTunnelFrameReadyListener)) },
        .{ .name = "native_enableOnFrameRenderedListener", .signature = "(Z)V", .fnPtr = @ptrCast(@constCast(&native_enableOnFrameRenderedListener)) },
        .{ .name = "native_setCallback", .signature = "(Landroid/media/MediaCodec$Callback;)V", .fnPtr = @ptrCast(@constCast(&native_setCallback)) },
        .{ .name = "native_configure", .signature = "([Ljava/lang/String;[Ljava/lang/Object;Landroid/view/Surface;Landroid/media/MediaCrypto;Landroid/os/IHwBinder;I)V", .fnPtr = @ptrCast(@constCast(&native_configure)) },
        .{ .name = "native_setSurface", .signature = "(Landroid/view/Surface;)V", .fnPtr = @ptrCast(@constCast(&native_setSurface)) },
        .{ .name = "native_detachOutputSurface", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_detachOutputSurface)) },
        .{ .name = "createInputSurface", .signature = "()Landroid/view/Surface;", .fnPtr = @ptrCast(@constCast(&createInputSurface)) },
        .{ .name = "native_start", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_start)) },
        .{ .name = "native_stop", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_stop)) },
        .{ .name = "native_flush", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_flush)) },
        .{ .name = "native_queueInputBuffer", .signature = "(IIIJI)V", .fnPtr = @ptrCast(@constCast(&native_queueInputBuffer)) },
        .{ .name = "native_queueInputBuffers", .signature = "(I[Ljava/lang/Object;)V", .fnPtr = @ptrCast(@constCast(&native_queueInputBuffers)) },
        .{ .name = "native_queueSecureInputBuffer", .signature = "(IILandroid/media/MediaCodec$CryptoInfo;JI)V", .fnPtr = @ptrCast(@constCast(&native_queueSecureInputBuffer)) },
        .{ .name = "native_queueSecureInputBuffers", .signature = "(I[Ljava/lang/Object;[Ljava/lang/Object;)V", .fnPtr = @ptrCast(@constCast(&native_queueSecureInputBuffers)) },
        .{ .name = "native_mapHardwareBuffer", .signature = "(Landroid/hardware/HardwareBuffer;)Landroid/media/Image;", .fnPtr = @ptrCast(@constCast(&native_mapHardwareBuffer)) },
        .{ .name = "native_closeMediaImage", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&native_closeMediaImage)) },
        .{ .name = "native_queueLinearBlock", .signature = "(ILandroid/media/MediaCodec$LinearBlock;[Ljava/lang/Object;[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/util/ArrayList;)V", .fnPtr = @ptrCast(@constCast(&native_queueLinearBlock)) },
        .{ .name = "native_queueHardwareBuffer", .signature = "(ILandroid/hardware/HardwareBuffer;JILjava/util/ArrayList;Ljava/util/ArrayList;)V", .fnPtr = @ptrCast(@constCast(&native_queueHardwareBuffer)) },
        .{ .name = "native_getOutputFrame", .signature = "(Landroid/media/MediaCodec$OutputFrame;I)V", .fnPtr = @ptrCast(@constCast(&native_getOutputFrame)) },
        .{ .name = "native_dequeueInputBuffer", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&native_dequeueInputBuffer)) },
        .{ .name = "native_dequeueOutputBuffer", .signature = "(Landroid/media/MediaCodec$BufferInfo;J)I", .fnPtr = @ptrCast(@constCast(&native_dequeueOutputBuffer)) },
        .{ .name = "releaseOutputBuffer", .signature = "(IZZJ)V", .fnPtr = @ptrCast(@constCast(&releaseOutputBuffer)) },
        .{ .name = "signalEndOfInputStream", .signature = "()V", .fnPtr = @ptrCast(@constCast(&signalEndOfInputStream)) },
        .{ .name = "getFormatNative", .signature = "(Z)Ljava/util/Map;", .fnPtr = @ptrCast(@constCast(&getFormatNative)) },
        .{ .name = "getOutputFormatNative", .signature = "(I)Ljava/util/Map;", .fnPtr = @ptrCast(@constCast(&getOutputFormatNative)) },
        .{ .name = "getBuffers", .signature = "(Z)[Ljava/nio/ByteBuffer;", .fnPtr = @ptrCast(@constCast(&getBuffers)) },
        .{ .name = "getBuffer", .signature = "(ZI)Ljava/nio/ByteBuffer;", .fnPtr = @ptrCast(@constCast(&getBuffer)) },
        .{ .name = "getImage", .signature = "(ZI)Landroid/media/Image;", .fnPtr = @ptrCast(@constCast(&getImage)) },
        .{ .name = "getCanonicalName", .signature = "()Ljava/lang/String;", .fnPtr = @ptrCast(@constCast(&getCanonicalName)) },
        .{ .name = "getOwnCodecInfo", .signature = "()Landroid/media/MediaCodecInfo;", .fnPtr = @ptrCast(@constCast(&getOwnCodecInfo)) },
        .{ .name = "native_getMetrics", .signature = "()Landroid/os/PersistableBundle;", .fnPtr = @ptrCast(@constCast(&native_getMetrics)) },
        .{ .name = "setParameters", .signature = "([Ljava/lang/String;[Ljava/lang/Object;)V", .fnPtr = @ptrCast(@constCast(&setParameters)) },
        .{ .name = "setVideoScalingMode", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&setVideoScalingMode)) },
        .{ .name = "native_setAudioPresentation", .signature = "(II)V", .fnPtr = @ptrCast(@constCast(&native_setAudioPresentation)) },
        .{ .name = "native_getSupportedVendorParameters", .signature = "()Ljava/util/List;", .fnPtr = @ptrCast(@constCast(&native_getSupportedVendorParameters)) },
        .{ .name = "native_getParameterDescriptor", .signature = "(Ljava/lang/String;)Landroid/media/MediaCodec$ParameterDescriptor;", .fnPtr = @ptrCast(@constCast(&native_getParameterDescriptor)) },
        .{ .name = "native_subscribeToVendorParameters", .signature = "(Ljava/util/List;)V", .fnPtr = @ptrCast(@constCast(&native_subscribeToVendorParameters)) },
        .{ .name = "native_unsubscribeFromVendorParameters", .signature = "(Ljava/util/List;)V", .fnPtr = @ptrCast(@constCast(&native_unsubscribeFromVendorParameters)) },
        .{ .name = "native_init", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_init)) },
        .{ .name = "native_setup", .signature = "(Ljava/lang/String;ZZII)V", .fnPtr = @ptrCast(@constCast(&native_setup)) },
        .{ .name = "native_finalize", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_finalize)) },
        .{ .name = "native_getGloballyAvailableResources", .signature = "()Ljava/util/List;", .fnPtr = @ptrCast(@constCast(&native_getGloballyAvailableResources)) },
        .{ .name = "native_getRequiredResources", .signature = "()Ljava/util/List;", .fnPtr = @ptrCast(@constCast(&native_getRequiredResources)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    native_init(env, cls);
    if (env.*.*.ExceptionCheck.?(env) != 0) return error.NoField;
    const local = env.*.*.FindClass.?(env, "dev/artzig/HostedMediaCodecBackend") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, local);
    backend = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.NoClass);
    const helpers = [_]jni.JNINativeMethod{
        .{ .name = "codecException", .signature = "(IILjava/lang/String;)Landroid/media/MediaCodec$CodecException;", .fnPtr = @ptrCast(@constCast(&codecException)) },
        .{ .name = "postEvent", .signature = "(Landroid/media/MediaCodec;IIILjava/lang/Object;)V", .fnPtr = @ptrCast(@constCast(&postEvent)) },
    };
    if (env.*.*.RegisterNatives.?(env, local, &helpers, helpers.len) != 0) return error.RegisterFailed;
    const backend_methods = .{
        .{ "release", "(J)V" },
        .{ "reset", "(J)V" },
        .{ "createPersistentInputSurface", "()Landroid/view/Surface;" },
        .{ "setInputSurface", "(JLandroid/view/Surface;)V" },
        .{ "enableOnFirstTunnelFrameReadyListener", "(JZ)V" },
        .{ "enableOnFrameRenderedListener", "(JZLandroid/os/Handler;)V" },
        .{ "setCallback", "(JLandroid/media/MediaCodec$Callback;)V" },
        .{ "configure", "(J[Ljava/lang/String;[Ljava/lang/Object;Landroid/view/Surface;Landroid/media/MediaCrypto;Landroid/os/IHwBinder;I)V" },
        .{ "setSurface", "(JLandroid/view/Surface;)V" },
        .{ "detachOutputSurface", "(J)V" },
        .{ "createInputSurface", "(J)Landroid/view/Surface;" },
        .{ "start", "(J)V" },
        .{ "stop", "(J)V" },
        .{ "flush", "(J)V" },
        .{ "queueInputBuffer", "(JIIIJI)V" },
        .{ "queueInputBuffers", "(JI[Ljava/lang/Object;)V" },
        .{ "queueSecureInputBuffer", "(JIILandroid/media/MediaCodec$CryptoInfo;JI)V" },
        .{ "queueSecureInputBuffers", "(JI[Ljava/lang/Object;[Ljava/lang/Object;)V" },
        .{ "mapHardwareBuffer", "(Landroid/hardware/HardwareBuffer;)Landroid/media/Image;" },
        .{ "closeMediaImage", "(J)V" },
        .{ "queueLinearBlock", "(JILandroid/media/MediaCodec$LinearBlock;[Ljava/lang/Object;[Ljava/lang/Object;Ljava/util/ArrayList;Ljava/util/ArrayList;)V" },
        .{ "queueHardwareBuffer", "(JILandroid/hardware/HardwareBuffer;JILjava/util/ArrayList;Ljava/util/ArrayList;)V" },
        .{ "getOutputFrame", "(JLandroid/media/MediaCodec$OutputFrame;I)V" },
        .{ "dequeueInputBuffer", "(JJ)I" },
        .{ "dequeueOutputBuffer", "(JLandroid/media/MediaCodec$BufferInfo;J)I" },
        .{ "releaseOutputBuffer", "(JIZZJ)V" },
        .{ "signalEndOfInputStream", "(J)V" },
        .{ "getFormatNative", "(JZ)Ljava/util/Map;" },
        .{ "getOutputFormatNative", "(JI)Ljava/util/Map;" },
        .{ "getBuffers", "(JZ)[Ljava/nio/ByteBuffer;" },
        .{ "getBuffer", "(JZI)Ljava/nio/ByteBuffer;" },
        .{ "getImage", "(JZI)Landroid/media/Image;" },
        .{ "getCanonicalName", "(J)Ljava/lang/String;" },
        .{ "getOwnCodecInfo", "(J)Landroid/media/MediaCodecInfo;" },
        .{ "getMetrics", "(J)Landroid/os/PersistableBundle;" },
        .{ "setParameters", "(J[Ljava/lang/String;[Ljava/lang/Object;)V" },
        .{ "setVideoScalingMode", "(JI)V" },
        .{ "setAudioPresentation", "(JII)V" },
        .{ "getSupportedVendorParameters", "(J)Ljava/util/List;" },
        .{ "getParameterDescriptor", "(JLjava/lang/String;)Landroid/media/MediaCodec$ParameterDescriptor;" },
        .{ "subscribeToVendorParameters", "(JLjava/util/List;)V" },
        .{ "unsubscribeFromVendorParameters", "(JLjava/util/List;)V" },
        .{ "setup", "(Landroid/media/MediaCodec;Ljava/lang/String;ZZII)J" },
        .{ "release", "(J)V" },
        .{ "getGloballyAvailableResources", "()Ljava/util/List;" },
        .{ "getRequiredResources", "(J)Ljava/util/List;" },
    };
    inline for (backend_methods) |entry| {
        _ = method(env, entry[0], entry[1]) orelse return error.NoMethod;
    }
    const block_local = env.*.*.FindClass.?(env, "android/media/MediaCodec$LinearBlock") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, block_local);
    block_class = @ptrCast(env.*.*.NewGlobalRef.?(env, block_local) orelse return error.NoClass);
    block_state = env.*.*.GetMethodID.?(env, block_class, "setInternalStateLocked", "(JZ)V") orelse return error.NoMethod;
    const block_methods = [_]jni.JNINativeMethod{
        .{ .name = "native_map", .signature = "()Ljava/nio/ByteBuffer;", .fnPtr = @ptrCast(@constCast(&blockMap)) },
        .{ .name = "native_recycle", .signature = "()V", .fnPtr = @ptrCast(@constCast(&blockRecycle)) },
        .{ .name = "native_obtain", .signature = "(I[Ljava/lang/String;)V", .fnPtr = @ptrCast(@constCast(&blockObtain)) },
        .{ .name = "native_checkCompatible", .signature = "([Ljava/lang/String;)Z", .fnPtr = @ptrCast(@constCast(&blockCompatible)) },
    };
    if (env.*.*.RegisterNatives.?(env, block_class, &block_methods, block_methods.len) != 0) return error.RegisterFailed;
}
