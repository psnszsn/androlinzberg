// Embed pinned AndroidRuntime in the launcher's existing ART VM. AndroidRuntime
// retains JNI registration, Java-capable thread attachment and process exit;
// this adapter supplies AppRuntime.onStarted for hosted exec children.
#include <android_runtime/AndroidRuntime.h>
#include "RuntimeEmbedding.h"
#include <nativehelper/ScopedLocalRef.h>
#include <utils/AndroidThreads.h>
#include <log/log.h>
#include <new>

extern void android_os_Process_setArgV0(JNIEnv*, jobject, jstring);

namespace android {
int register_com_android_internal_os_RuntimeInit(JNIEnv*);

class HostedAndroidRuntime final : public AndroidRuntime {
public:
    using AndroidRuntime::AndroidRuntime;

    static int bindVm(JNIEnv* env) {
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || (mJavaVM && mJavaVM != vm)) return -1;
        mJavaVM = vm;
        androidSetCreateThreadFunc(javaCreateThreadEtc);
        return 0;
    }

    int registerEntry(JNIEnv* env) {
        const JNINativeMethod process_methods[] = {
            {"setArgV0Native", "(Ljava/lang/String;)V", reinterpret_cast<void*>(android_os_Process_setArgV0)},
        };
        if (registerNativeMethods(env, "android/os/Process", process_methods, 1) != 0) return -1;
        if (register_com_android_internal_os_RuntimeInit(env) != 0) return -1;
        ScopedLocalRef<jclass> bootstrap(env, env->FindClass("dev/artzig/RuntimeBootstrap"));
        if (!bootstrap.get()) return -1;
        on_started_ = env->GetStaticMethodID(bootstrap.get(), "onStarted", "()V");
        if (!on_started_) return -1;
        bootstrap_ = reinterpret_cast<jclass>(env->NewGlobalRef(bootstrap.get()));
        if (!bootstrap_) return -1;
        return 0;
    }

    void onStarted() override {
        // BinderSession claims the hosted process identity during entry startup;
        // its first IPC starts readers with that authenticated identity.
        JNIEnv* env = getJNIEnv();
        LOG_ALWAYS_FATAL_IF(!env || !bootstrap_ || !on_started_, "Runtime entry not registered");
        env->CallStaticVoidMethod(bootstrap_, on_started_);
    }

private:
    jclass bootstrap_ = nullptr;
    jmethodID on_started_ = nullptr;
};
}

extern "C" int azk_android_runtime_initialize(char* argument_block, size_t length) {
    if (!argument_block || !length || android::AndroidRuntime::getRuntime()) return -1;
    // One runtime owns this process until AndroidRuntime::_exit. Its argument
    // storage is the original writable exec argv block, borrowed for that lifetime.
    return new (std::nothrow) android::HostedAndroidRuntime(argument_block, length) ? 0 : -1;
}
extern "C" int azk_android_runtime_bind(JNIEnv* env) {
    return android::AndroidRuntime::getRuntime() ? android::HostedAndroidRuntime::bindVm(env) : -1;
}
extern "C" int azk_register_runtime_init(JNIEnv* env) {
    auto* runtime = static_cast<android::HostedAndroidRuntime*>(android::AndroidRuntime::getRuntime());
    return runtime ? runtime->registerEntry(env) : -1;
}
extern "C" [[noreturn]] void azk_android_runtime_exit(int status) {
    android::AndroidRuntime::getRuntime()->exit(status);
    __builtin_unreachable();
}
