// Java Parcel and C++ Parcel share the same Zig storage and lifetime.
#include <binder/Parcel.h>
#include <jni.h>
#include <mutex>
namespace android {
namespace {
jfieldID nativePtrField(JNIEnv *env) {
  static std::once_flag once;
  static jfieldID field = nullptr;
  std::call_once(once, [env] {
    jclass cls = env->FindClass("android/os/Parcel");
    if (cls == nullptr)
      return;
    field = env->GetFieldID(cls, "mNativePtr", "J");
    env->DeleteLocalRef(cls);
  });
  if (field == nullptr && env->ExceptionCheck())
    env->ExceptionClear();
  return field;
}

} // namespace
Parcel *parcelForJavaObject(JNIEnv *env, jobject object) {
  if (!object)
    return nullptr;
  auto field = nativePtrField(env);
  if (!field)
    return nullptr;
  auto ptr = env->GetLongField(object, field);
  return Parcel::forNative(
      reinterpret_cast<AParcel *>(static_cast<intptr_t>(ptr)));
}
bool android_util_Log_isVerboseLogEnabled(const char *) { return false; }
} // namespace android
