#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <include/android/SkImageAndroid.h>
#include <include/core/SkBitmap.h>
#include <include/core/SkColorSpace.h>
#include <include/core/SkImage.h>
#include "renderthread/RenderProxy.h"

namespace {
jint pixel(JNIEnv* env, jclass, jobject object) {
    using android::uirenderer::renderthread::RenderProxy;
    auto* buffer = AHardwareBuffer_fromHardwareBuffer(env, object);
    if (!buffer) return 0;
    SkBitmap bitmap;
    bitmap.allocN32Pixels(1, 1);
    // Exercise the stock lazy generator: creating an image alone does not
    // instantiate its GPU texture. Readback must import and sample the buffer.
    auto image = SkImages::DeferredFromAHardwareBuffer(buffer, kPremul_SkAlphaType,
                                                     SkColorSpace::MakeSRGB());
    if (!image || RenderProxy::copyImageInto(image, &bitmap) != 0) return 0;
    return bitmap.getColor(0, 0);
}
}

int register_dev_artzig_StockReadbackSmoke(JNIEnv* env) {
    jclass cls = env->FindClass("dev/artzig/StockReadbackSmoke");
    if (!cls) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"pixel", "(Landroid/hardware/HardwareBuffer;)I", reinterpret_cast<void*>(pixel)},
    };
    int result = env->RegisterNatives(cls, methods, 1);
    env->DeleteLocalRef(cls);
    return result;
}
