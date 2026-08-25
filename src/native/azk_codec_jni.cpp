// artzig: JNI wrapper for android.media.MediaCodec over the azk_codec C ABI.
// The codec transport lives in the androlinzberg executable, so its functions are
// resolved with dlsym(RTLD_DEFAULT, ...).
#include <dlfcn.h>
#include <jni.h>

#include <android/log.h>
#define ALOGW_TAG(...) __android_log_print(ANDROID_LOG_WARN, "azk-codec", __VA_ARGS__)

namespace {

using OpenFn = void* (*)(const char*);
using ConfigureFn = int (*)(void*, const uint8_t*, int, int, int);
using SendFn = int (*)(void*, const uint8_t*, int, int64_t, int);
using ReceiveAudioFn = int (*)(void*, uint8_t*, int, int*, int64_t*);
using ReceiveVideoFn = int (*)(void*, uint8_t*, int, int*, int64_t*);
using ReceiveVideo2Fn = int (*)(void*, uint8_t*, int, int*, int64_t*, uintptr_t*);
using FlushFn = void (*)(void*);
using CloseFn = void (*)(void*);

OpenFn f_open;
ConfigureFn f_configure;
SendFn f_send;
ReceiveAudioFn f_receive_audio;
ReceiveVideoFn f_receive_video;
ReceiveVideo2Fn f_receive_video2;
FlushFn f_flush;
CloseFn f_close;

bool resolve() {
    if (f_open) return true;
    f_open = (OpenFn)dlsym(RTLD_DEFAULT, "azk_codec_open");
    f_configure = (ConfigureFn)dlsym(RTLD_DEFAULT, "azk_codec_configure");
    f_send = (SendFn)dlsym(RTLD_DEFAULT, "azk_codec_send");
    f_receive_audio = (ReceiveAudioFn)dlsym(RTLD_DEFAULT, "azk_codec_receive_audio");
    f_receive_video = (ReceiveVideoFn)dlsym(RTLD_DEFAULT, "azk_codec_receive_video");
    f_receive_video2 = (ReceiveVideo2Fn)dlsym(RTLD_DEFAULT, "azk_codec_receive_video2");
    f_flush = (FlushFn)dlsym(RTLD_DEFAULT, "azk_codec_flush");
    f_close = (CloseFn)dlsym(RTLD_DEFAULT, "azk_codec_close");
    if (!f_open || !f_configure || !f_send || !f_receive_audio || !f_receive_video ||
            !f_receive_video2 || !f_flush || !f_close) {
        ALOGW_TAG("azk_codec symbols not found in the host executable");
        f_open = nullptr;
        return false;
    }
    return true;
}

jlong MC_nativeOpen(JNIEnv* env, jclass, jstring name) {
    if (!resolve()) return 0;
    const char* n = env->GetStringUTFChars(name, nullptr);
    void* d = f_open(n);
    env->ReleaseStringUTFChars(name, n);
    return reinterpret_cast<jlong>(d);
}

jint MC_nativeConfigure(JNIEnv* env, jclass, jlong h, jbyteArray csd, jint sampleRate, jint channels) {
    const uint8_t* p = nullptr;
    int len = 0;
    jbyte* base = nullptr;
    if (csd != nullptr) {
        base = env->GetByteArrayElements(csd, nullptr);
        p = reinterpret_cast<const uint8_t*>(base);
        len = env->GetArrayLength(csd);
    }
    int r = f_configure(reinterpret_cast<void*>(h), p, len, sampleRate, channels);
    if (base) env->ReleaseByteArrayElements(csd, base, JNI_ABORT);
    return r;
}

jint MC_nativeSend(JNIEnv* env, jclass, jlong h, jobject buf, jint offset, jint size, jlong ptsUs, jint eof) {
    const uint8_t* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!base && eof == 0) return -100;
    return f_send(reinterpret_cast<void*>(h), base ? base + offset : nullptr, size, ptsUs, eof);
}

jint MC_nativeReceiveAudio(JNIEnv* env, jclass, jlong h, jobject dst, jint cap, jintArray meta, jlongArray pts) {
    uint8_t* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!out) return -100;
    int m[3] = {0, 0, 0};
    int64_t p = 0;
    int r = f_receive_audio(reinterpret_cast<void*>(h), out, cap, m, &p);
    env->SetIntArrayRegion(meta, 0, 3, m);
    jlong jp = p;
    env->SetLongArrayRegion(pts, 0, 1, &jp);
    return r;
}

jint MC_nativeReceiveVideo(JNIEnv* env, jclass, jlong h, jobject dst, jint cap,
        jintArray meta, jlongArray pts, jlongArray handle) {
    uint8_t* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!out) return -100;
    int m[3] = {0, 0, 0};
    int64_t p = 0;
    uintptr_t native_handle = 0;
    int r = f_receive_video2(reinterpret_cast<void*>(h), out, cap, m, &p, &native_handle);
    env->SetIntArrayRegion(meta, 0, 3, m);
    jlong jp = p;
    env->SetLongArrayRegion(pts, 0, 1, &jp);
    jlong jhandle = static_cast<jlong>(native_handle);
    env->SetLongArrayRegion(handle, 0, 1, &jhandle);
    return r;
}

void MC_nativeFlush(JNIEnv*, jclass, jlong h) { f_flush(reinterpret_cast<void*>(h)); }
void MC_nativeClose(JNIEnv*, jclass, jlong h) { f_close(reinterpret_cast<void*>(h)); }

const JNINativeMethod kMethods[] = {
    { "nativeOpen", "(Ljava/lang/String;)J", (void*)MC_nativeOpen },
    { "nativeConfigure", "(J[BII)I", (void*)MC_nativeConfigure },
    { "nativeSend", "(JLjava/nio/ByteBuffer;IIJI)I", (void*)MC_nativeSend },
    { "nativeReceiveAudio", "(JLjava/nio/ByteBuffer;I[I[J)I", (void*)MC_nativeReceiveAudio },
    { "nativeReceiveVideo", "(JLjava/nio/ByteBuffer;I[I[J[J)I", (void*)MC_nativeReceiveVideo },
    { "nativeFlush", "(J)V", (void*)MC_nativeFlush },
    { "nativeClose", "(J)V", (void*)MC_nativeClose },
};

}  // namespace

extern "C" int azk_register_codec(JNIEnv* env) {
    jclass cls = env->FindClass("android/media/MediaCodec");
    if (!cls) return -1;
    return env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
}
