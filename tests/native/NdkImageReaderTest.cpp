// Observable public NDK image contracts against the original BufferQueue reader.
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#define __ANDROID_VNDK__
#include <media/NdkImageReader.h>
#undef __ANDROID_VNDK__
#include <sync/sync.h>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <system/window.h>
#include "GpuBufferRenderer.h"

namespace {
struct Callbacks {
    std::mutex mutex;
    std::condition_variable changed;
    int frames = 0;
    std::thread::id thread;
    static void available(void* context, AImageReader*) {
        auto& self = *static_cast<Callbacks*>(context);
        std::lock_guard lock(self.mutex);
        ++self.frames;
        self.thread = std::this_thread::get_id();
        self.changed.notify_all();
    }
    void await(int count) {
        std::unique_lock lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(5), [&] { return frames >= count; }));
        assert(thread != std::this_thread::get_id());
    }
};
using Image = std::unique_ptr<AImage, decltype(&AImage_delete)>;
void paint(ANativeWindow* window, uint32_t color) {
    ANativeWindow_Buffer buffer{};
    assert(ANativeWindow_lock(window, &buffer, nullptr) == 0);
    assert(buffer.width == 16 && buffer.height == 12 && buffer.format == WINDOW_FORMAT_RGBA_8888);
    for (int y = 0; y < buffer.height; ++y) {
        auto* row = static_cast<uint32_t*>(buffer.bits) + y * buffer.stride;
        for (int x = 0; x < buffer.width; ++x) row[x] = color;
    }
    assert(ANativeWindow_unlockAndPost(window) == 0);
}
Image acquire(AImageReader* reader, bool latest, bool async) {
    AImage* image = nullptr;
    int fence = -1;
    auto status = async ? (latest ? AImageReader_acquireLatestImageAsync(reader, &image, &fence)
                                  : AImageReader_acquireNextImageAsync(reader, &image, &fence))
                        : (latest ? AImageReader_acquireLatestImage(reader, &image)
                                  : AImageReader_acquireNextImage(reader, &image));
    assert(status == AMEDIA_OK && image != nullptr);
    if (fence >= 0) {
        assert(sync_wait(fence, 5000) == 0);
        close(fence);
    }
    return Image(image, AImage_delete);
}
void pixels(AImage* image, uint32_t color) {
    int32_t width = 0, height = 0, format = 0, planes = 0, pixelStride = 0, rowStride = 0;
    assert(AImage_getWidth(image, &width) == AMEDIA_OK && width == 16);
    assert(AImage_getHeight(image, &height) == AMEDIA_OK && height == 12);
    assert(AImage_getFormat(image, &format) == AMEDIA_OK && format == AIMAGE_FORMAT_RGBA_8888);
    assert(AImage_getNumberOfPlanes(image, &planes) == AMEDIA_OK && planes == 1);
    assert(AImage_getPlanePixelStride(image, 0, &pixelStride) == AMEDIA_OK && pixelStride == 4);
    assert(AImage_getPlaneRowStride(image, 0, &rowStride) == AMEDIA_OK && rowStride >= width * 4);
    AImageCropRect crop{};
    assert(AImage_getCropRect(image, &crop) == AMEDIA_OK);
    assert(crop.left == 0 && crop.top == 0 && crop.right == width && crop.bottom == height);
    int64_t timestamp = 0;
    assert(AImage_getTimestamp(image, &timestamp) == AMEDIA_OK && timestamp > 0);
    uint8_t* data = nullptr;
    int length = 0;
    assert(AImage_getPlaneData(image, 0, &data, &length) == AMEDIA_OK);
    assert(length >= (height - 1) * rowStride + width * pixelStride);
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        uint32_t observed;
        std::memcpy(&observed, data + y * rowStride + x * pixelStride, sizeof(observed));
        assert(observed == color);
    }
}
void gpuImage() {
    AImageReader* raw = nullptr;
    assert(AImageReader_newWithDataSpace(16, 12, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        2, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, ADATASPACE_SRGB, &raw) == AMEDIA_OK);
    std::unique_ptr<AImageReader, decltype(&AImageReader_delete)> reader(raw, AImageReader_delete);
    ANativeWindow* window = nullptr;
    assert(AImageReader_getWindow(raw, &window) == AMEDIA_OK);
    assert(native_window_api_connect(window, NATIVE_WINDOW_API_EGL) == 0);
    assert(native_window_set_usage(window, android::GraphicBuffer::USAGE_HW_RENDER) == 0);
    // Supply the producer dataspace, as a real EGL producer does.
    assert(native_window_set_buffers_data_space(window, HAL_DATASPACE_V0_SRGB) == 0);
    ANativeWindowBuffer* buffer = nullptr;
    int fence = -1;
    assert(window->dequeueBuffer(window, &buffer, &fence) == 0);
    if (fence >= 0) { assert(sync_wait(fence, 5000) == 0); close(fence); }
    GpuBufferRenderer renderer;
    auto ready = renderer.render(android::sp<android::GraphicBuffer>(
        android::GraphicBuffer::from(buffer)), 0xff39a76b);
    assert(ready->isValid());
    assert(window->queueBuffer(window, buffer, ready->dup()) == 0);
    AImage* image = nullptr;
    fence = -1;
    assert(AImageReader_acquireNextImageAsync(raw, &image, &fence) == AMEDIA_OK);
    assert(image && fence >= 0);
    assert(sync_wait(fence, 5000) == 0);
    pixels(image, 0xff39a76b);
    int32_t dataSpace = 0;
    // Pinned AImage::AImage derives this from PublicFormat, not BufferItem;
    // RGBA_8888 maps to UNKNOWN even when the producer supplied sRGB.
    assert(AImage_getDataSpace(image, &dataSpace) == AMEDIA_OK && dataSpace == ADATASPACE_UNKNOWN);
    // Transfer a real sync_file back to the reader for asynchronous release.
    AImage_deleteAsync(image, fence);
    assert(native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL) == 0);
}

}

extern "C" JNIEXPORT void JNICALL Java_dev_artzig_BufferJniSmoke_ndkImageQueues(JNIEnv*, jclass) {
    gpuImage();
    Callbacks callbacks;
    AImageReader* raw = nullptr;
    assert(AImageReader_new(16, 12, AIMAGE_FORMAT_RGBA_8888, 2, &raw) == AMEDIA_OK);
    std::unique_ptr<AImageReader, decltype(&AImageReader_delete)> reader(raw, AImageReader_delete);
    int32_t value = 0;
    assert(AImageReader_getWidth(raw, &value) == AMEDIA_OK && value == 16);
    assert(AImageReader_getHeight(raw, &value) == AMEDIA_OK && value == 12);
    assert(AImageReader_getMaxImages(raw, &value) == AMEDIA_OK && value == 2);
    assert(AImageReader_getFormat(raw, &value) == AMEDIA_OK && value == AIMAGE_FORMAT_RGBA_8888);
    // The deprecated HIDL token service is absent on this host. The original
    // implementation must report that failure, while the normal window/queue
    // operations below keep working and retain their ownership contracts.
    native_handle_t* legacyHandle = nullptr;
    assert(AImageReader_getWindowNativeHandle(nullptr, &legacyHandle) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AImageReader_getWindowNativeHandle(raw, nullptr) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AImageReader_getWindowNativeHandle(raw, &legacyHandle) == AMEDIA_ERROR_UNKNOWN);
    assert(legacyHandle == nullptr);
    assert(AImageReader_getWidth(nullptr, &value) == AMEDIA_ERROR_INVALID_PARAMETER);
    AImageReader_ImageListener listener{&callbacks, Callbacks::available};
    assert(AImageReader_setImageListener(raw, &listener) == AMEDIA_OK);
    ANativeWindow* window = nullptr;
    assert(AImageReader_getWindow(raw, &window) == AMEDIA_OK && window != nullptr);
    ANativeWindow_acquire(window);
    std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)> heldWindow(window, ANativeWindow_release);
    AImage* empty = nullptr;
    assert(AImageReader_acquireNextImage(raw, &empty) == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE && !empty);
    paint(window, 0xff336699);
    callbacks.await(1);
    auto first = acquire(raw, false, true);
    pixels(first.get(), 0xff336699);
    paint(window, 0xff19b36b);
    callbacks.await(2);
    auto second = acquire(raw, false, false);
    pixels(second.get(), 0xff19b36b);
    assert(AImageReader_acquireNextImage(raw, &empty) == AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED);
    first.reset();
    AImage_deleteAsync(second.release(), -1);
    paint(window, 0xff123456);
    callbacks.await(3);
    paint(window, 0xffabcdef);
    // Stock BufferQueue may replace the pending frame; FrameListener does not
    // forward onFrameReplaced as another image-available callback.
    auto latest = acquire(raw, true, true);
    pixels(latest.get(), 0xffabcdef);
    AHardwareBuffer* buffer = nullptr;
    assert(AImage_getHardwareBuffer(latest.get(), &buffer) == AMEDIA_OK && buffer);
    AHardwareBuffer_acquire(buffer);
    std::unique_ptr<AHardwareBuffer, decltype(&AHardwareBuffer_release)> heldBuffer(buffer, AHardwareBuffer_release);
    assert(AImageReader_setImageListener(raw, nullptr) == AMEDIA_OK);
    reader.reset();
    // Reader teardown invalidates outstanding images while independently held
    // hardware buffers and native windows retain their own references.
    assert(AImage_getWidth(latest.get(), &value) == AMEDIA_ERROR_INVALID_OBJECT);
    latest.reset();
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    assert(desc.width == 16 && desc.height == 12);
    ANativeWindow_Buffer abandoned{};
    assert(ANativeWindow_lock(window, &abandoned, nullptr) != 0);
}
