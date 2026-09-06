#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <cstdint>
#include <cstring>
#include <private/android/AHardwareBufferHelpers.h>
#include "GpuBufferRenderer.h"

extern "C" uint64_t azk_graphics_live_buffers();

extern "C" JNIEXPORT jlong JNICALL
Java_dev_artzig_BufferJniSmoke_liveBuffers(JNIEnv *, jclass) {
  return azk_graphics_live_buffers();
}

extern "C" JNIEXPORT jint JNICALL Java_dev_artzig_BufferJniSmoke_pixel(
    JNIEnv *env, jclass, jobject object, jint x, jint y) {
  AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, object);
  AHardwareBuffer_Desc desc{};
  AHardwareBuffer_describe(buffer, &desc);
  if (x < 0 || y < 0 || uint32_t(x) >= desc.width ||
      uint32_t(y) >= desc.height ||
      desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM)
    return 0;
  void *pixels = nullptr;
  if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1,
                           nullptr, &pixels) != 0)
    return 0;
  uint8_t *rgba = static_cast<uint8_t *>(pixels) + (y * desc.stride + x) * 4;
  uint32_t color = (uint32_t(rgba[3]) << 24) | (uint32_t(rgba[0]) << 16) |
                   (uint32_t(rgba[1]) << 8) | rgba[2];
  AHardwareBuffer_unlock(buffer, nullptr);
  return color;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_artzig_BufferJniSmoke_nativeCopy(JNIEnv *env, jclass, jobject object) {
  return AHardwareBuffer_toHardwareBuffer(
      env, AHardwareBuffer_fromHardwareBuffer(env, object));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_artzig_BufferJniSmoke_gpuWrite(JNIEnv *env, jclass, jobject object) {
  GpuBufferRenderer renderer;
  auto buffer = android::AHardwareBuffer_to_GraphicBuffer(
      AHardwareBuffer_fromHardwareBuffer(env, object));
  return renderer
      .render(android::sp<android::GraphicBuffer>(buffer), 0xff6bb319)
      ->dup();
}
