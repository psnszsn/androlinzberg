// Public NDK transactions observed through the hosted stock SurfaceFlinger output.
#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>
#include <sync/sync.h>
#include <private/android/AHardwareBufferHelpers.h>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <unistd.h>
#include "GpuBufferRenderer.h"

namespace {
struct Completion {
    std::mutex mutex;
    std::condition_variable changed;
    ASurfaceControl* layer;
    bool committed = false;
    bool completed = false;
    int64_t latch = -1;
    explicit Completion(ASurfaceControl* value) : layer(value) {}
    static void commit(void* context, ASurfaceTransactionStats*) {
        auto& self = *static_cast<Completion*>(context);
        std::lock_guard lock(self.mutex);
        assert(!self.committed && !self.completed);
        self.committed = true;
    }
    static void complete(void* context, ASurfaceTransactionStats* stats) {
        auto& self = *static_cast<Completion*>(context);
        std::lock_guard lock(self.mutex);
        assert(self.committed && !self.completed);
        self.latch = ASurfaceTransactionStats_getLatchTime(stats);
        assert(self.latch > 0);
        ASurfaceControl** controls = nullptr;
        size_t count = 0;
        ASurfaceTransactionStats_getASurfaceControls(stats, &controls, &count);
        bool found = false;
        for (size_t i = 0; i < count; ++i) if (controls[i] == self.layer) found = true;
        assert(found);
        ASurfaceTransactionStats_releaseASurfaceControls(controls);
        assert(ASurfaceTransactionStats_getAcquireTime(stats, self.layer) > 0);
        for (int fd : {ASurfaceTransactionStats_getPresentFenceFd(stats),
                       ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, self.layer)}) {
            if (fd >= 0) { assert(sync_wait(fd, 5000) == 0); close(fd); }
        }
        self.completed = true;
        self.changed.notify_all();
    }
    int64_t await() {
        std::unique_lock lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(5), [&] { return completed; }));
        return latch;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_artzig_SurfaceBufferSmoke_ndkCreateLayer(
        JNIEnv* env, jclass, jobject parentObject, jobject surfaceObject) {
    ASurfaceControl* layer = nullptr;
    if (surfaceObject) {
        auto* window = ANativeWindow_fromSurface(env, surfaceObject);
        assert(window);
        layer = ASurfaceControl_createFromWindow(window, "ndk-window-child");
        ANativeWindow_release(window);
    } else {
        auto* parent = ASurfaceControl_fromJava(env, parentObject);
        assert(parent);
        layer = ASurfaceControl_create(parent, "ndk-java-child");
        ASurfaceControl_release(parent);
    }
    assert(layer);
    ASurfaceControl_acquire(layer);
    ASurfaceControl_release(layer);
    return reinterpret_cast<jlong>(layer);
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_artzig_SurfaceBufferSmoke_ndkPaint(
        JNIEnv* env, jclass, jlong handle, jobject bufferObject, jint color) {
    auto* layer = reinterpret_cast<ASurfaceControl*>(handle);
    auto* buffer = AHardwareBuffer_fromHardwareBuffer(env, bufferObject);
    assert(layer && buffer);
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    GpuBufferRenderer renderer;
    const uint32_t rgba = (uint32_t(color) & 0xff00ff00) |
        ((uint32_t(color) & 0x00ff0000) >> 16) | ((uint32_t(color) & 0xff) << 16);
    auto fence = renderer.render(android::sp<android::GraphicBuffer>(
        android::AHardwareBuffer_to_GraphicBuffer(buffer)), rgba);
    Completion completion(layer);
    auto* transaction = ASurfaceTransaction_create();
    assert(transaction);
    ASurfaceTransaction_setOnCommit(transaction, &completion, Completion::commit);
    ASurfaceTransaction_setOnComplete(transaction, &completion, Completion::complete);
    ASurfaceTransaction_setZOrder(transaction, layer, 100);
    ASurfaceTransaction_setVisibility(transaction, layer, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    ASurfaceTransaction_setBufferTransparency(transaction, layer, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ARect rect{0, 0, int32_t(desc.width), int32_t(desc.height)};
    ASurfaceTransaction_setGeometry(transaction, layer, rect, rect, 0);
    ASurfaceTransaction_setBuffer(transaction, layer, buffer, fence->dup());
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    return completion.await();
}

extern "C" JNIEXPORT void JNICALL Java_dev_artzig_SurfaceBufferSmoke_ndkHide(
        JNIEnv* env, jclass, jobject transactionObject, jlong handle) {
    // fromJava borrows the original transaction; its Java owner applies/closes it.
    auto* transaction = ASurfaceTransaction_fromJava(env, transactionObject);
    ASurfaceTransaction_setVisibility(transaction, reinterpret_cast<ASurfaceControl*>(handle),
        ASURFACE_TRANSACTION_VISIBILITY_HIDE);
}

extern "C" JNIEXPORT void JNICALL Java_dev_artzig_SurfaceBufferSmoke_ndkDestroyLayer(
        JNIEnv*, jclass, jlong handle) {
    auto* layer = reinterpret_cast<ASurfaceControl*>(handle);
    auto* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_reparent(transaction, layer, nullptr);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    ASurfaceControl_release(layer);
}
