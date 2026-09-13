// Stock JNI owns Java's native objects. This seam adapts only its C++ objects
// to the shared AZKB NDK storage; it does not implement Java Binder methods.
#include <azk/binder_jni.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManagerFFI.h>
#include "android_util_Binder.h"
#include "android_os_Parcel.h"

int register_android_os_Binder(JNIEnv *);
namespace android {
int register_android_os_Parcel(JNIEnv *);
int register_android_os_ServiceManager(JNIEnv *);
int register_android_os_ServiceManagerNative(JNIEnv *);
jint android_os_Debug_getLocalObjectCount(JNIEnv *, jobject);
jint android_os_Debug_getProxyObjectCount(JNIEnv *, jobject);
jint android_os_Debug_getDeathObjectCount(JNIEnv *, jobject);

namespace impl {
// Pinned IServiceManagerFFI returns the backend's typed service-manager proxy.
// The host backend is the existing AZKB context object instead of handle zero.
sp<android::os::IServiceManager>
getJavaServicemanagerImplPrivateDoNotUseExceptInTheOnePlaceItIsUsed() {
  return interface_cast<android::os::IServiceManager>(
      ProcessState::self()->getContextObject(nullptr));
}
} // namespace impl
} // namespace android

extern "C" void azk_install_stock_binder_jni_bridge() {
  using namespace android;
  static const AzkBinderJavaBridge bridge{
    [](void *env_) {
      auto *env = static_cast<JNIEnv *>(env_);
      // Same relative ordering as pinned AndroidRuntime's registration table.
      if (register_android_os_Binder(env) != 0 || register_android_os_Parcel(env) != 0 ||
          register_android_os_ServiceManager(env) != 0 || register_android_os_ServiceManagerNative(env) != 0) return -1;
      return 0;
    },
    [](void *env, void *object) -> AIBinder * {
      auto binder = ibinderForJavaObject(static_cast<JNIEnv *>(env), static_cast<jobject>(object));
      return binder ? binder->acquireNative() : nullptr;
    },
    [](void *env, AIBinder *binder) -> void * {
      if (!binder) return nullptr;
      AIBinder_incStrong(binder);
      auto owner = IBinder::fromNative(binder);
      auto *jni = static_cast<JNIEnv *>(env);
      jobject object = javaObjectForIBinder(jni, owner);
      // JavaBBinder returns its borrowed global reference. The NDK result
      // must survive release of the caller's last native reference.
      jobject result = jni->NewLocalRef(object);
      if (object && jni->GetObjectRefType(object) == JNILocalRefType) jni->DeleteLocalRef(object);
      return result;
    },
    [](void *env, void *object) -> AParcel * {
      auto *parcel = parcelForJavaObject(static_cast<JNIEnv *>(env), static_cast<jobject>(object));
      return parcel ? parcel->native() : nullptr;
    },
    [](void *env_, int kind) -> int32_t {
      auto *env = static_cast<JNIEnv *>(env_);
      if (kind == 0) return android_os_Debug_getLocalObjectCount(env, nullptr);
      if (kind == 1) return android_os_Debug_getProxyObjectCount(env, nullptr);
      return android_os_Debug_getDeathObjectCount(env, nullptr);
    },
  };
  azk_binder_installJavaBridge(&bridge);
}
