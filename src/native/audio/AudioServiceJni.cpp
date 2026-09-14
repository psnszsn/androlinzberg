// Bootstrap only: creates the host AudioFlinger/AudioPolicy Binder objects for
// the system process to publish as media.audio_flinger / media.audio_policy.
// The service protocol is the pinned libaudioclient AIDL (HostAudio.h).
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include "HostAudio.h"

namespace android {
jobject javaObjectForIBinder(JNIEnv*, const sp<IBinder>&);
}

namespace {
android::sp<artzig::audio::HostAudioFlinger> gFlinger;
android::sp<artzig::audio::HostAudioPolicyService> gPolicy;

void ensureServices() {
    if (gFlinger != nullptr) return;
    gFlinger = android::sp<artzig::audio::HostAudioFlinger>::make();
    gPolicy = android::sp<artzig::audio::HostAudioPolicyService>::make(gFlinger->output());
    gFlinger->setPolicy(gPolicy);
}

jobject nativeCreateAudioPolicyService(JNIEnv* env, jobject) {
    ensureServices();
    return android::javaObjectForIBinder(env, gPolicy);
}

jobject nativeCreateAudioFlingerService(JNIEnv* env, jobject) {
    ensureServices();
    return android::javaObjectForIBinder(env, gFlinger);
}
}  // namespace

extern "C" int azk_register_audio_services(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativeCreateAudioPolicyService", "()Landroid/os/IBinder;", reinterpret_cast<void*>(nativeCreateAudioPolicyService)},
        {"nativeCreateAudioFlingerService", "()Landroid/os/IBinder;", reinterpret_cast<void*>(nativeCreateAudioFlingerService)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/system/SystemServices", methods, 2);
}
