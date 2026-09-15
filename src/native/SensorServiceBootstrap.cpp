// Construct the stock service with the same Android ABI flags as its implementation.
// In particular, utils::Thread has an Android-only data member.
#include <SensorService.h>
#include "android_util_Binder.h"

jobject azkCreateSensorService(JNIEnv* env, jobject) {
    return android::javaObjectForIBinder(env, android::sp<android::SensorService>::make());
}
