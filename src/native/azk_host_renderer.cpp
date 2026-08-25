#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <renderthread/RenderProxy.h>

#include <iterator>
#include <mutex>

namespace {

using android::uirenderer::renderthread::RenderProxy;

struct WaylandTarget {
    void* display = nullptr;
    void* surface = nullptr;
};

std::mutex gTargetMutex;
WaylandTarget gTarget;
jfieldID gNativeProxyField = nullptr;

RenderProxy* proxyFromRenderer(JNIEnv* env, jobject renderer) {
    return reinterpret_cast<RenderProxy*>(env->GetLongField(renderer, gNativeProxyField));
}

void configureTarget(JNIEnv* env, jobject renderer, jint width, jint height) {
    RenderProxy* proxy = proxyFromRenderer(env, renderer);
    if (!proxy || width <= 0 || height <= 0) return;

    WaylandTarget target;
    {
        std::lock_guard lock(gTargetMutex);
        target = gTarget;
    }
    if (!target.display || !target.surface) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Wayland presentation target is not bound");
        return;
    }
    proxy->setHostSurface(target.display, target.surface, width, height);
    proxy->setContentDrawBounds(0, 0, width, height);
    proxy->forceDrawNextFrame();
}

void destroyTarget(JNIEnv* env, jobject renderer) {
    RenderProxy* proxy = proxyFromRenderer(env, renderer);
    if (!proxy) return;
    proxy->setHostSurface(nullptr, nullptr, 0, 0);
    proxy->fence();
}

}  // namespace

extern "C" void azk_host_renderer_set_wayland(void* display, void* surface) {
    std::lock_guard lock(gTargetMutex);
    gTarget = {.display = display, .surface = surface};
}

extern "C" int azk_register_host_renderer(JNIEnv* env) {
    jclass hardwareRenderer = env->FindClass("android/graphics/HardwareRenderer");
    if (!hardwareRenderer) return -1;
    gNativeProxyField = env->GetFieldID(hardwareRenderer, "mNativeProxy", "J");
    if (!gNativeProxyField) return -1;

    jclass threadedRenderer = env->FindClass("android/view/ThreadedRenderer");
    if (!threadedRenderer) return -1;
    static const JNINativeMethod methods[] = {
            {"nConfigureHostSurface", "(II)V", reinterpret_cast<void*>(configureTarget)},
            {"nDestroyHostSurface", "()V", reinterpret_cast<void*>(destroyTarget)},
    };
    return env->RegisterNatives(threadedRenderer, methods, static_cast<jint>(std::size(methods)));
}
