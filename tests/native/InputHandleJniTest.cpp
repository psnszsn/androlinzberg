// Exercise the production AOSP conversion functions without adding testing JNI
// to the application island. Window metadata subsequently travels as
// WindowInfo.
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_hardware_input_InputWindowHandle.h"
#include <jni.h>

extern "C" JNIEXPORT jobject JNICALL
Java_dev_artzig_InputHandleSmoke_application(JNIEnv *env, jclass,
                                             jobject object) {
  auto handle =
      android::android_view_InputApplicationHandle_getHandle(env, object);
  if (!handle || !handle->updateInfo())
    return nullptr;
  return android::android_view_InputApplicationHandle_fromInputApplicationInfo(
      env, *handle->getInfo());
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_artzig_InputHandleSmoke_window(JNIEnv *env, jclass, jobject object) {
  auto handle = android::android_view_InputWindowHandle_getHandle(env, object);
  // Alpha is supplied by SF and is not initialized by the WMS input conversion.
  handle->editInfo()->alpha = 1.f;
  return android::android_view_InputWindowHandle_fromWindowInfo(
      env, *handle->getInfo());
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_artzig_InputHandleSmoke_composedWindow(JNIEnv *env, jclass,
                                                jobject object) {
  auto handle = android::android_view_InputWindowHandle_getHandle(env, object);
  handle->editInfo()->alpha = 0.625f;
  auto info = *handle->getInfo();
  // These fields are supplied by SF's composition, not accepted from Java WMS.
  info.contentSize = android::ui::Size(640, 480);
  info.transform.set(0.5f, 0.f, 0.f, 0.25f);
  info.transform.set(-40.f, -30.f);
  info.alpha = 0.625f;
  info.canOccludePresentation = true;
  return android::android_view_InputWindowHandle_fromWindowInfo(env, info);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_artzig_InputHandleSmoke_hasFocusTransfer(JNIEnv *env, jclass,
                                                  jobject object,
                                                  jobject expected) {
  auto handle = android::android_view_InputWindowHandle_getHandle(env, object);
  auto target =
      android::android_view_InputWindowHandle_getHandle(env, expected);
  return handle->getInfo()->focusTransferTarget == target->getInfo()->token;
}
