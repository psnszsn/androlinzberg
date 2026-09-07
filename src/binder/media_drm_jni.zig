//! Pinned android_media_MediaDrm.cpp / JDrm and DrmUtils::MakeObject.
//! No AIDL/HIDL DRM factories are installed. Both DrmHal implementations report
//! ERROR_UNSUPPORTED, so MakeDrm returns null. Keep that factory-absence path:
//! empty enumeration, BAD_VALUE for per-scheme queries, NO_INIT construction,
//! and CheckDrm's error before instance argument validation. This supplies no
//! sessions, keys, provisioning or decryption; original MediaDrm owns Java policy.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var context_field: jni.jfieldID = null;

fn throwNamed(env: Env, name: [*:0]const u8, message: ?[*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn noDrm(env: Env) void {
    throwNamed(env, "java/lang/IllegalStateException", "MediaDrm obj is null");
}
fn native_init(env: Env, cls: jni.jclass) callconv(.c) void {
    context_field = env.*.*.GetFieldID.?(env, cls, "mNativeContext", "J");
}
fn native_release(env: Env, object: jni.jobject) callconv(.c) void {
    // The failed native_setup never publishes a JDrm; stock cleanup clears it.
    env.*.*.SetLongField.?(env, object, context_field, 0);
}
fn validUuid(env: Env, uuid: jni.jbyteArray, setup: bool) bool {
    if (uuid == null) {
        throwNamed(env, "java/lang/IllegalArgumentException", if (setup) "uuid is null" else null);
        return false;
    }
    if (env.*.*.GetArrayLength.?(env, uuid) != 16) {
        throwNamed(env, "java/lang/IllegalArgumentException", "invalid UUID size, expected 16 bytes");
        return false;
    }
    return true;
}
fn native_setup(env: Env, _: jni.jobject, _: jni.jobject, uuid: jni.jbyteArray, package_name: jni.jstring) callconv(.c) void {
    if (!validUuid(env, uuid, true)) return;
    if (package_name == null) {
        throwNamed(env, "java/lang/IllegalArgumentException", "application package name cannot be null");
        return;
    }
    // DrmUtils::GetExceptionMessage(NO_INIT, ..., empty plugin log).
    throwNamed(env, "android/media/UnsupportedSchemeException", "Failed to instantiate drm object: NO_INIT\ncdm err: 0, oem err: 0, ctx: 0\n" ++
        "============================== Beginning of DRM Plugin Log ==============================\n" ++
        "============================== End of DRM Plugin Log ==============================");
}
fn getSupportedCryptoSchemesNative(env: Env, _: jni.jclass) callconv(.c) jni.jbyteArray {
    return env.*.*.NewByteArray.?(env, 0);
}
fn isCryptoSchemeSupportedNative(env: Env, _: jni.jclass, uuid: jni.jbyteArray, _: jni.jstring, _: jni.jint) callconv(.c) jni.jboolean {
    if (!validUuid(env, uuid, false)) return jni.JNI_FALSE;
    // JDrm::IsCryptoSchemeSupported returns BAD_VALUE when MakeDrm is null.
    throwNamed(env, "java/lang/IllegalArgumentException", "Failed to query crypto scheme support");
    return jni.JNI_FALSE;
}

// Each original entry first checks its JDrm (or CheckSession, which checks JDrm
// first). Preserve exact JNI argument/return types even for this terminal state.
fn openSessionNative(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn closeSessionNative(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn getKeyRequestNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jint, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn provideKeyResponse(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn removeKeys(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn restoreKeys(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn queryKeyStatus(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getProvisionRequestNative(env: Env, _: jni.jobject, _: jni.jint, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn provideProvisionResponseNative(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getSecureStops(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getSecureStopIds(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getSecureStop(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn releaseSecureStops(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn removeSecureStop(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn removeAllSecureStops(env: Env, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn getConnectedHdcpLevel(env: Env, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn getMaxHdcpLevel(env: Env, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn getOpenSessionCount(env: Env, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn getMaxSessionCount(env: Env, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn getSecurityLevel(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn removeOfflineLicense(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn getOfflineLicenseKeySetIds(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getOfflineLicenseState(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
    noDrm(env);
    return 0;
}
fn getPropertyString(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getPropertyByteArray(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn setPropertyString(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn setPropertyByteArray(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn setCipherAlgorithmNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn setMacAlgorithmNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn encryptNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn decryptNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn signNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn verifyNative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jboolean {
    noDrm(env);
    return jni.JNI_FALSE;
}
fn signRSANative(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn getMetricsNative(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}
fn requiresSecureDecoder(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jint) callconv(.c) jni.jboolean {
    noDrm(env);
    return jni.JNI_FALSE;
}
fn setPlaybackId(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noDrm(env);
}
fn getLogMessages(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noDrm(env);
    return null;
}

fn method(comptime name: [*:0]const u8, comptime signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaDrm") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("native_release", "()V", &native_release),
        method("native_init", "()V", &native_init),
        method("native_setup", "(Ljava/lang/Object;[BLjava/lang/String;)V", &native_setup),
        method("getSupportedCryptoSchemesNative", "()[B", &getSupportedCryptoSchemesNative),
        method("isCryptoSchemeSupportedNative", "([BLjava/lang/String;I)Z", &isCryptoSchemeSupportedNative),
        method("openSessionNative", "(I)[B", &openSessionNative),
        method("closeSessionNative", "([B)V", &closeSessionNative),
        method("getKeyRequestNative", "([B[BLjava/lang/String;ILjava/util/HashMap;)Landroid/media/MediaDrm$KeyRequest;", &getKeyRequestNative),
        method("provideKeyResponse", "([B[B)[B", &provideKeyResponse),
        method("removeKeys", "([B)V", &removeKeys),
        method("restoreKeys", "([B[B)V", &restoreKeys),
        method("queryKeyStatus", "([B)Ljava/util/HashMap;", &queryKeyStatus),
        method("getProvisionRequestNative", "(ILjava/lang/String;)Landroid/media/MediaDrm$ProvisionRequest;", &getProvisionRequestNative),
        method("provideProvisionResponseNative", "([B)Landroid/media/MediaDrm$Certificate;", &provideProvisionResponseNative),
        method("getSecureStops", "()Ljava/util/List;", &getSecureStops),
        method("getSecureStopIds", "()Ljava/util/List;", &getSecureStopIds),
        method("getSecureStop", "([B)[B", &getSecureStop),
        method("releaseSecureStops", "([B)V", &releaseSecureStops),
        method("removeSecureStop", "([B)V", &removeSecureStop),
        method("removeAllSecureStops", "()V", &removeAllSecureStops),
        method("getConnectedHdcpLevel", "()I", &getConnectedHdcpLevel),
        method("getMaxHdcpLevel", "()I", &getMaxHdcpLevel),
        method("getOpenSessionCount", "()I", &getOpenSessionCount),
        method("getMaxSessionCount", "()I", &getMaxSessionCount),
        method("getSecurityLevel", "([B)I", &getSecurityLevel),
        method("removeOfflineLicense", "([B)V", &removeOfflineLicense),
        method("getOfflineLicenseKeySetIds", "()Ljava/util/List;", &getOfflineLicenseKeySetIds),
        method("getOfflineLicenseState", "([B)I", &getOfflineLicenseState),
        method("getPropertyString", "(Ljava/lang/String;)Ljava/lang/String;", &getPropertyString),
        method("getPropertyByteArray", "(Ljava/lang/String;)[B", &getPropertyByteArray),
        method("setPropertyString", "(Ljava/lang/String;Ljava/lang/String;)V", &setPropertyString),
        method("setPropertyByteArray", "(Ljava/lang/String;[B)V", &setPropertyByteArray),
        method("setCipherAlgorithmNative", "(Landroid/media/MediaDrm;[BLjava/lang/String;)V", &setCipherAlgorithmNative),
        method("setMacAlgorithmNative", "(Landroid/media/MediaDrm;[BLjava/lang/String;)V", &setMacAlgorithmNative),
        method("encryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B", &encryptNative),
        method("decryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B", &decryptNative),
        method("signNative", "(Landroid/media/MediaDrm;[B[B[B)[B", &signNative),
        method("verifyNative", "(Landroid/media/MediaDrm;[B[B[B[B)Z", &verifyNative),
        method("signRSANative", "(Landroid/media/MediaDrm;[BLjava/lang/String;[B[B)[B", &signRSANative),
        method("getMetricsNative", "()Landroid/os/PersistableBundle;", &getMetricsNative),
        method("requiresSecureDecoder", "(Ljava/lang/String;I)Z", &requiresSecureDecoder),
        method("setPlaybackId", "([BLjava/lang/String;)V", &setPlaybackId),
        method("getLogMessages", "()Ljava/util/List;", &getLogMessages),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
