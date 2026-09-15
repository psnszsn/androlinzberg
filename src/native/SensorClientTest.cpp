// Public NDK sensor contracts over the same service exercised by the Java smoke.
#include <android/looper.h>
#include <android/sensor.h>
#include <android/sharedmem.h>
#include <android-base/unique_fd.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Errors.h>
#include <fcntl.h>

static jint verifyNdk(JNIEnv* env, jobject, jstring packageName) {
    ScopedUtfChars name(env, packageName);
    if (!name.c_str()) return 1;
    auto* manager = ASensorManager_getInstanceForPackage(name.c_str());
    ASensorList sensors = nullptr;
    if (!manager || ASensorManager_getSensorList(manager, &sensors) != 0 ||
        ASensorManager_getDynamicSensorList(manager, &sensors) != 0 ||
        ASensorManager_getDefaultSensor(manager, ASENSOR_TYPE_ACCELEROMETER) != nullptr) return 2;
    if (ASensorManager_getSensorList(nullptr, &sensors) != android::BAD_VALUE) return 3;

    auto* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    if (!looper) return 4;
    auto* queue = ASensorManager_createEventQueue(manager, looper, 31415, nullptr, nullptr);
    if (!queue) return 5;
    ASensorEvent event{};
    const bool empty = ASensorEventQueue_getEvents(queue, &event, 1) == 0;
    const bool rejectsNull = ASensorEventQueue_enableSensor(queue, nullptr) == android::BAD_VALUE;
    const bool closed = ASensorManager_destroyEventQueue(manager, queue) == 0;
    if (!empty || !rejectsNull || !closed) return 6;

    android::base::unique_fd fd(ASharedMemory_create("sensor-ndk-channel", sizeof(ASensorEvent)));
    if (fd < 0) return 7;
    if (ASensorManager_createSharedMemoryDirectChannel(manager, fd, sizeof(ASensorEvent)) !=
        android::NO_MEMORY) return 8;
    if (fcntl(fd, F_GETFD) < 0) return 9;
    if (ASensorManager_createSharedMemoryDirectChannel(manager, -1, sizeof(ASensorEvent)) !=
        android::BAD_VALUE) return 10;
    return 0;
}

int azk_register_sensor_smoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativeVerifyNdk", "(Ljava/lang/String;)I", reinterpret_cast<void*>(verifyNdk)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockSystemSensorSmoke", methods, 1);
}
