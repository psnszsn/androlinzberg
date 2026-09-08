// Surface JNI stays with the pinned C++ object API. Codec data/ownership crosses
// this seam as a C buffer description; no AVFrame or C++ Parcel layout is shared.
#include <azk/VideoSurface.h>
#include <android_runtime/android_view_Surface.h>
#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>

struct AzkHardwareFrame;
namespace {
using DescribeFrame = int (*)(const AzkHardwareFrame*, AzkGraphicsBufferInfo*);
using ReleaseFrame = void (*)(AzkHardwareFrame*);
struct CodecApi {
    DescribeFrame describe = reinterpret_cast<DescribeFrame>(
            dlsym(RTLD_DEFAULT, "azk_hardware_frame_describe_surface"));
    ReleaseFrame release = reinterpret_cast<ReleaseFrame>(
            dlsym(RTLD_DEFAULT, "azk_hardware_frame_release"));
};
const CodecApi& codec() { static const CodecApi api; return api; }

jlong nativeConnect(JNIEnv* env, jclass, jobject javaSurface) {
    android::status_t status = android::NO_INIT;
    if (!codec().describe || !codec().release) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Codec GPU-frame API unavailable");
        return 0;
    }
    auto surface = android::android_view_Surface_getSurface(env, javaSurface);
    if (env->ExceptionCheck()) return 0;
    auto output = azk::VideoSurface::connect(surface, &status);
    if (!output) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                "Cannot connect codec to Surface: invalid, abandoned or already connected");
        return 0;
    }
    return reinterpret_cast<jlong>(output.release());
}
void nativeDisconnect(JNIEnv*, jclass, jlong output) {
    delete reinterpret_cast<azk::VideoSurface*>(output);
}
void nativeConfigure(JNIEnv* env, jclass, jlong output, jint rotation, jint scaling) {
    const auto status = reinterpret_cast<azk::VideoSurface*>(output)->configure(rotation, scaling);
    if (status != android::OK)
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                "Cannot configure codec Surface transform/scaling");
}
jint nativeQueue(JNIEnv*, jclass, jlong output, jlong hardware, jlong timestamp, jlong pts) {
    auto* frame = reinterpret_cast<AzkHardwareFrame*>(hardware);
    AzkGraphicsBufferInfo info{};
    const auto& api = codec(); // Connection already verified both functions.
    int status = api.describe(frame, &info);
    if (status == android::OK)
        status = reinterpret_cast<azk::VideoSurface*>(output)->queue(info, timestamp, pts);
    // VPP output is a separate allocation, never a decoder-pool AVFrame. Its
    // imported DMA-BUF references own storage after VA destroys its surface.
    api.release(frame);
    return status;
}
void nativeSetFrameTracking(JNIEnv*, jclass, jlong output, jboolean enabled) {
    reinterpret_cast<azk::VideoSurface*>(output)->setFrameTracking(enabled);
}
jlongArray nativeDrainRenderedFrames(JNIEnv* env, jclass, jlong output) {
    const auto rendered = reinterpret_cast<azk::VideoSurface*>(output)->drainRenderedFrames();
    std::array<jlong, 1 + azk::VideoSurface::kMaxTrackedFrames * 2> values{};
    values[0] = rendered.pending;
    for (size_t i = 0; i < rendered.count; ++i) {
        values[1 + 2 * i] = rendered.frames[i].presentationTimeUs;
        values[2 + 2 * i] = rendered.frames[i].renderTimeNs;
    }
    const auto count = static_cast<jsize>(1 + rendered.count * 2);
    auto result = env->NewLongArray(count);
    if (result) env->SetLongArrayRegion(result, 0, count, values.data());
    return result;
}
void nativeRelease(JNIEnv* env, jclass, jlong hardware) {
    if (!hardware) return;
    if (!codec().release) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Codec GPU-frame release unavailable");
        return;
    }
    codec().release(reinterpret_cast<AzkHardwareFrame*>(hardware));
}
}

extern "C" int azk_video_surface_available() {
    const char* backend = std::getenv("AZK_WINDOW_BACKEND");
    return backend && std::strcmp(backend, "stock") == 0 &&
            azk_graphics_supported(16, 16, 2, 1, GRALLOC_USAGE_HW_TEXTURE) == 1;
}

int register_dev_artzig_HostedVideoOutput(JNIEnv* env) {
    auto cls = env->FindClass("dev/artzig/HostedVideoOutput");
    if (!cls) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"nativeConnect", "(Landroid/view/Surface;)J", reinterpret_cast<void*>(nativeConnect)},
        {"nativeDisconnect", "(J)V", reinterpret_cast<void*>(nativeDisconnect)},
        {"nativeConfigure", "(JII)V", reinterpret_cast<void*>(nativeConfigure)},
        {"nativeQueue", "(JJJJ)I", reinterpret_cast<void*>(nativeQueue)},
        {"nativeSetFrameTracking", "(JZ)V", reinterpret_cast<void*>(nativeSetFrameTracking)},
        {"nativeDrainRenderedFrames", "(J)[J", reinterpret_cast<void*>(nativeDrainRenderedFrames)},
        {"nativeRelease", "(J)V", reinterpret_cast<void*>(nativeRelease)},
    };
    const int status = env->RegisterNatives(cls, methods, std::size(methods));
    env->DeleteLocalRef(cls);
    return status;
}
