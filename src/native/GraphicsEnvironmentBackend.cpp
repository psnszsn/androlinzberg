#include "GraphicsEnvironmentBackend.h"

#include <cerrno>
#include <graphicsenv/GraphicsEnv.h>
#include <nativeloader/dlext_namespaces.h>

namespace {
JavaVM* vm;
jclass diagnostics;
jmethodID reportMissing;

void unsupportedNamespace(const char* member) {
    // Preserve the Android linker API's failure result while recording the unsupported operation.
    // No namespace exists in the hosted loader; ordinary library loading remains unchanged.
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK;
    }
    if (env && diagnostics && !env->ExceptionCheck()) {
        jstring owner = env->NewStringUTF("android.os.GraphicsEnvironment");
        jstring method = env->NewStringUTF(member);
        jstring detail = env->NewStringUTF(
                "The hosted dynamic loader has no isolated driver or ANGLE linker namespaces");
        if (owner && method && detail) {
            env->CallStaticVoidMethod(diagnostics, reportMissing, owner, method, detail);
        }
        env->DeleteLocalRef(owner);
        env->DeleteLocalRef(method);
        env->DeleteLocalRef(detail);
    }
    if (attached) vm->DetachCurrentThread();
    errno = ENOTSUP;
}

jstring nativeState(JNIEnv* env, jclass) {
    auto& graphics = android::GraphicsEnv::getInstance();
    const std::string state = graphics.getLayerPaths() + "\n" + graphics.getDebugLayers()
            + "\n" + graphics.getDebugLayersGLES() + "\n"
            + (graphics.getAppNamespace() == nullptr ? "no-namespace" : "namespace");
    return env->NewStringUTF(state.c_str());
}
}  // namespace

namespace android {
extern "C" NativeLoaderNamespace* FindNativeLoaderNamespaceByClassLoader(JNIEnv*, jobject) {
    return nullptr;
}
}  // namespace android

// Pinned libnativeloader/dlext_namespaces.h: exported lookup returns null for an unknown
// namespace; creation/linking fail explicitly when requested on this non-namespaced loader.
extern "C" android_namespace_t* android_get_exported_namespace(const char*) {
    return nullptr;
}
extern "C" android_namespace_t* android_create_namespace(const char*, const char*, const char*,
        uint64_t, const char*, android_namespace_t*) {
    unsupportedNamespace("android_create_namespace");
    return nullptr;
}
extern "C" bool android_link_namespaces(android_namespace_t*, android_namespace_t*, const char*) {
    unsupportedNamespace("android_link_namespaces");
    return false;
}

extern "C" int azk_register_graphics_environment_backend(JNIEnv* env) {
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("dev/artzig/ArtzigTestDiagnostics");
    if (!local) return JNI_ERR;
    diagnostics = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!diagnostics) return JNI_ERR;
    reportMissing = env->GetStaticMethodID(diagnostics, "reportMissing",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (!reportMissing) return JNI_ERR;
    jclass probe = env->FindClass("dev/artzig/StockGraphicsEnvironmentSmoke");
    if (!probe) return JNI_ERR;
    const JNINativeMethod methods[] = {
            {"nativeState", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeState)},
    };
    const int result = env->RegisterNatives(probe, methods, 1);
    env->DeleteLocalRef(probe);
    return result;
}
