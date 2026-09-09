// Exercise the original HWUI functor/SurfaceControl contract on real task outputs.
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <private/hwui/WebViewFunctor.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <mutex>

using namespace android::uirenderer;
namespace {
struct Fixture {
    int id = -1;
    std::atomic<bool> expectLayer{false};
    bool synced = false, drawingLayer = false;
    std::atomic<int> roots{0}, layers{0};
    std::mutex mutex;
    std::condition_variable finished;
    bool destroyed = false;
    ASurfaceControl* overlay = nullptr;
    AHardwareBuffer* buffer = nullptr;

    void remove(void (*merge)(ASurfaceTransaction*)) {
        if (!overlay) return;
        auto* transaction = ASurfaceTransaction_create();
        ASurfaceTransaction_reparent(transaction, overlay, nullptr);
        merge(transaction);
        ASurfaceTransaction_delete(transaction);
        ASurfaceControl_release(overlay);
        overlay = nullptr;
        AHardwareBuffer_release(buffer);
        buffer = nullptr;
    }
    void create(const DrawGlInfo& info, const WebViewOverlayData& overlays) {
        if (overlay) return;
        auto* parent = overlays.getSurfaceControl();
        assert(parent);
        overlay = ASurfaceControl_create(parent, "functor-contract-underlay");
        assert(overlay);
        AHardwareBuffer_Desc desc{};
        desc.width = info.width;
        desc.height = info.height;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        assert(AHardwareBuffer_allocate(&desc, &buffer) == 0);
        AHardwareBuffer_describe(buffer, &desc);
        void* pixels = nullptr;
        assert(AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1, nullptr, &pixels) == 0);
        for (uint32_t y = 0; y < desc.height; ++y) for (uint32_t x = 0; x < desc.width; ++x) {
            auto* pixel = static_cast<uint8_t*>(pixels) + 4 * (y * desc.stride + x);
            pixel[0] = 0xcc; pixel[1] = 0x33; pixel[2] = 0x99; pixel[3] = 0xff;
        }
        assert(AHardwareBuffer_unlock(buffer, nullptr) == 0);
        auto* transaction = ASurfaceTransaction_create();
        ARect bounds{0, 0, info.width, info.height};
        ASurfaceTransaction_setGeometry(transaction, overlay, bounds, bounds, 0);
        ASurfaceTransaction_setVisibility(transaction, overlay, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setBufferTransparency(transaction, overlay, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setBuffer(transaction, overlay, buffer, -1);
        overlays.mergeTransaction(transaction);
        ASurfaceTransaction_delete(transaction);
    }
    static void draw(int, void* data, const DrawGlInfo& info, const WebViewOverlayData& overlays) {
        auto& self = *static_cast<Fixture*>(data);
        assert(self.synced && info.isLayer == self.drawingLayer);
        assert((overlays.overlaysMode == OverlaysMode::Disabled) == info.isLayer);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        if (info.isLayer) {
            self.remove(overlays.mergeTransaction);
            glScissor(info.clipLeft, info.height - info.clipBottom,
                info.clipRight - info.clipLeft, info.clipBottom - info.clipTop);
            glClearColor(0, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            self.layers++;
        } else {
            // Java records a translated, clipped functor. Root coordinates must
            // survive origin conversion; overlays use that same coordinate space.
            assert(info.clipLeft == 7 && info.clipTop == 5);
            assert(info.clipRight == info.width - 7 && info.clipBottom == info.height - 5);
            assert(info.transform[12] == 7 && info.transform[13] == 5);
            self.create(info, overlays);
            const int bottom = info.height - info.clipBottom;
            const int height = info.clipBottom - info.clipTop;
            glScissor(info.width / 4, bottom, info.width / 4, height);
            glClearColor(0, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glScissor(info.width / 2, bottom, info.width / 4, height);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            self.roots++;
        }
        assert(glGetError() == GL_NO_ERROR);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_artzig_SurfaceBufferSmoke_createFunctor(JNIEnv*, jclass) {
    auto* fixture = new Fixture;
    WebViewFunctorCallbacks callbacks{};
    callbacks.onSync = [](int, void* data, const WebViewSyncData&) {
        auto& self = *static_cast<Fixture*>(data);
        self.drawingLayer = self.expectLayer.load();
        self.synced = true;
    };
    callbacks.onContextDestroyed = [](int, void*) {};
    callbacks.onDestroyed = [](int, void* data) {
        auto& self = *static_cast<Fixture*>(data);
        std::lock_guard lock(self.mutex);
        assert(!self.overlay && !self.buffer);
        self.destroyed = true;
        self.finished.notify_all();
    };
    callbacks.removeOverlays = [](int, void* data, void (*merge)(ASurfaceTransaction*)) {
        static_cast<Fixture*>(data)->remove(merge);
    };
    callbacks.gles.draw = Fixture::draw;
    fixture->id = WebViewFunctor_create(fixture, callbacks, RenderMode::OpenGL_ES);
    assert(fixture->id > 0);
    return reinterpret_cast<jlong>(fixture);
}
extern "C" JNIEXPORT jint JNICALL Java_dev_artzig_SurfaceBufferSmoke_configureFunctor(
        JNIEnv*, jclass, jlong handle, jboolean layer) {
    auto& fixture = *reinterpret_cast<Fixture*>(handle);
    fixture.expectLayer = layer;
    return fixture.id;
}
extern "C" JNIEXPORT void JNICALL Java_dev_artzig_SurfaceBufferSmoke_destroyFunctor(
        JNIEnv*, jclass, jlong handle) {
    auto* fixture = reinterpret_cast<Fixture*>(handle);
    WebViewFunctor_release(fixture->id);
    {
        std::unique_lock lock(fixture->mutex);
        assert(fixture->finished.wait_for(lock, std::chrono::seconds(5), [&] { return fixture->destroyed; }));
        assert(fixture->roots >= 2 && fixture->layers >= 1);
    }
    delete fixture;
}
