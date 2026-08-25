#pragma once

#include <EGL/egl.h>
#include <SkSurface.h>
#include <include/gpu/ganesh/GrDirectContext.h>

#include "pipeline/skia/SkiaPipeline.h"

struct wl_egl_window;

namespace android::uirenderer::skiapipeline {

// Ganesh/OpenGL HWUI pipeline for the host runtime. It owns the EGL window
// surface and presents Android display lists directly to the Wayland surface.
class ArtzigGpuPipeline final : public SkiaPipeline {
public:
    explicit ArtzigGpuPipeline(renderthread::RenderThread& thread);
    ~ArtzigGpuPipeline() override;

    bool pinImages(std::vector<SkImage*>&) override { return false; }
    bool pinImages(LsaVector<sk_sp<Bitmap>>&) override { return false; }
    void unpinImages() override {}

    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                             ErrorHandler* errorHandler) override;
    void renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) override;
    void setHardwareBuffer(AHardwareBuffer*) override;
    bool hasHardwareBuffer() override { return mHardwareBuffer != nullptr; }

    renderthread::MakeCurrentResult makeCurrent() override;
    renderthread::Frame getFrame() override;
    renderthread::IRenderPipeline::DrawResult draw(
            const renderthread::Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
            const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
            const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
            const std::vector<sp<RenderNode>>& renderNodes, FrameInfoVisualizer* profiler,
            const renderthread::HardwareBufferRenderParams& bufferParams,
            std::mutex& profilerLock) override;
    bool swapBuffers(const renderthread::Frame&, IRenderPipeline::DrawResult&, const SkRect&,
                     FrameInfo*, bool* requireSwap) override;
    DeferredLayerUpdater* createTextureLayer() override { return nullptr; }
    bool setSurface(ANativeWindow* surface, renderthread::SwapBehavior swapBehavior) override;
    bool setHostSurface(void* nativeDisplay, void* nativeSurface, int32_t width,
                        int32_t height) override;
    [[nodiscard]] android::base::unique_fd flush() override;
    void onStop() override {}
    bool isSurfaceReady() override { return mSurface != nullptr || mHardwareBuffer != nullptr; }
    bool isContextReady() override { return mContext != nullptr; }

    const SkM44& getPixelSnapMatrix() const override {
        static const SkM44 identity;
        return identity;
    }

private:
    bool ensureContext();
    bool ensureGrContext();
    void clearSurface();
    void destroyContext();
    sk_sp<SkSurface> makeWindowSurface(int width, int height);
    sk_sp<SkSurface> makeLayerSurface(int width, int height);

    void* mNativeDisplay = nullptr;
    void* mNativeSurface = nullptr;
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
    EGLConfig mConfig = nullptr;
    EGLContext mEglContext = EGL_NO_CONTEXT;
    EGLSurface mEglSurface = EGL_NO_SURFACE;
    bool mUsingGbmDisplay = false;
    wl_egl_window* mWaylandWindow = nullptr;
    sk_sp<GrDirectContext> mContext;
    sk_sp<SkSurface> mSurface;
    AHardwareBuffer* mHardwareBuffer = nullptr;
    sk_sp<SkSurface> mBufferSurface;
    sk_sp<SkColorSpace> mBufferColorSpace;
    int mWidth = 0;
    int mHeight = 0;
};

}  // namespace android::uirenderer::skiapipeline
