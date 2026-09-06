//! Pinned android_media_MediaCrypto.cpp / JCrypto, at the DRM factory boundary.
//! The original JNI links ICrypto, CryptoHal and Android AIDL/HIDL DRM factories,
//! none of which are installed in this host. Preserve its no-plugin capability
//! result and initialization failure, including validation and finalizer safety.
//! Java remains verbatim AOSP; this does not supply decryption or media decoding.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var context_field: jni.jfieldID = null;

fn throwNamed(env: Env, name: [*:0]const u8, message: ?[*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}

fn init(env: Env, cls: jni.jclass) callconv(.c) void {
    context_field = env.*.*.GetFieldID.?(env, cls, "mNativeContext", "J");
}

fn release(env: Env, object: jni.jobject) callconv(.c) void {
    // Failed construction never publishes a JCrypto. AOSP release/finalize
    // both clear the context and tolerate repeated cleanup of that null state.
    env.*.*.SetLongField.?(env, object, context_field, 0);
}

fn validUuid(env: Env, uuid: jni.jbyteArray) bool {
    if (env.*.*.GetArrayLength.?(env, uuid) != 16) {
        throwNamed(env, "java/lang/IllegalArgumentException", null);
        return false;
    }
    return true;
}

fn supported(env: Env, _: jni.jclass, uuid: jni.jbyteArray) callconv(.c) jni.jboolean {
    if (!validUuid(env, uuid)) return jni.JNI_FALSE;
    // JCrypto::IsCryptoSchemeSupported with no CryptoHal factories.
    return jni.JNI_FALSE;
}

fn setup(env: Env, _: jni.jobject, uuid: jni.jbyteArray, _: jni.jbyteArray) callconv(.c) void {
    if (!validUuid(env, uuid)) return;
    // A null initData is accepted by the pinned JNI. JCrypto::initCheck returns
    // NO_INIT if MakeCrypto cannot create a plugin; StrCryptoError names it.
    throwNamed(env, "android/media/MediaCryptoException", "Failed to instantiate crypto object: NO_INIT");
}

fn requiresSecureDecoder(env: Env, _: jni.jobject, _: jni.jstring) callconv(.c) jni.jboolean {
    // Pinned JNI rejects a null MIME first, then an uninitialized/released object.
    // No object can hold a native plugin at this capability boundary.
    throwNamed(env, "java/lang/IllegalArgumentException", null);
    return jni.JNI_FALSE;
}

fn setSession(env: Env, _: jni.jobject, _: jni.jbyteArray) callconv(.c) void {
    // Pinned JNI rejects a null session and an absent JCrypto/ICrypto alike.
    throwNamed(env, "java/lang/IllegalArgumentException", null);
}

fn nativeMethod(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCrypto") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        nativeMethod("release", "()V", &release),
        nativeMethod("native_init", "()V", &init),
        nativeMethod("native_setup", "([B[B)V", &setup),
        nativeMethod("native_finalize", "()V", &release),
        nativeMethod("isCryptoSchemeSupportedNative", "([B)Z", &supported),
        nativeMethod("requiresSecureDecoderComponent", "(Ljava/lang/String;)Z", &requiresSecureDecoder),
        nativeMethod("setMediaDrmSession", "([B)V", &setSession),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
