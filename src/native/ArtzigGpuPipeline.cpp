#include "ArtzigGpuPipeline.h"

#include "HardwareBufferRender.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <SkImageInfo.h>
#include <android-base/unique_fd.h>
#include <include/gpu/GpuTypes.h>
#include <include/gpu/ganesh/GrBackendSurface.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include <include/gpu/ganesh/gl/GrGLAssembleInterface.h>
#include <include/gpu/ganesh/gl/GrGLBackendSurface.h>
#include <include/gpu/ganesh/gl/GrGLDirectContext.h>
#include <include/gpu/ganesh/gl/GrGLInterface.h>
#include <log/log.h>
#include <wayland-egl.h>

#include "DamageAccumulator.h"
#include "DeviceInfo.h"
#include "LightingInfo.h"
#include "Properties.h"
#include "RenderNode.h"
#include "renderthread/Frame.h"
#include "renderthread/RenderThread.h"

using android::uirenderer::renderthread::Frame;
using android::uirenderer::renderthread::IRenderPipeline;
using android::uirenderer::renderthread::MakeCurrentResult;

namespace android::uirenderer::skiapipeline {

namespace {

int gSwapInterval = 1;
bool gOverrideSwapInterval = false;
bool gTraceFramePacing = false;
uint32_t gSwapTraceSamples = 0;
uint64_t gSwapTraceTotalUs = 0;
uint64_t gSwapTraceMaxUs = 0;

GrGLFuncPtr getEglProc(void*, const char name[]) {
    return reinterpret_cast<GrGLFuncPtr>(eglGetProcAddress(name));
}

}  // namespace

ArtzigGpuPipeline::ArtzigGpuPipeline(renderthread::RenderThread& thread) : SkiaPipeline(thread) {}

ArtzigGpuPipeline::~ArtzigGpuPipeline() {
    setHardwareBuffer(nullptr);
    destroyContext();
}

bool ArtzigGpuPipeline::ensureContext() {
    if (mEglContext != EGL_NO_CONTEXT) return true;
    ALOGI("artzig: initializing %s HWUI EGL/OpenGL context",
          mNativeDisplay ? "Wayland" : "GBM offscreen");

    auto getPlatformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
            eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (!getPlatformDisplay) {
        ALOGE("artzig: EGL_EXT_platform_base is required for Wayland presentation");
        return false;
    }
    void* platformDisplay = mNativeDisplay;
    EGLenum platform = EGL_PLATFORM_WAYLAND_KHR;
    mUsingGbmDisplay = !platformDisplay;
    if (!mUsingGbmDisplay) {
        // Host presentation seam: unlike SurfaceFlinger, the synchronized Wayland parent owns
        // frame pacing. A Mesa swap interval of one throttles the child independently and fills
        // its buffer queue before the parent transaction can publish it. AOSP EglManager likewise
        // selects interval zero when HWUI's external pacing disables EGL vsync.
        gSwapInterval = 0;
        gOverrideSwapInterval = true;
    }
    if (mUsingGbmDisplay) {
        platformDisplay = artzig::hardwareBufferGbmDevice();
        platform = EGL_PLATFORM_GBM_KHR;
    }
    if (!platformDisplay) return false;
    mDisplay = getPlatformDisplay(platform, platformDisplay, nullptr);
    if (mDisplay == EGL_NO_DISPLAY || !eglInitialize(mDisplay, nullptr, nullptr)) {
        ALOGE("artzig: unable to initialize EGL display (0x%x)", eglGetError());
        destroyContext();
        return false;
    }
    if (!eglBindAPI(EGL_OPENGL_API)) {
        ALOGE("artzig: EGL does not expose desktop OpenGL (0x%x)", eglGetError());
        destroyContext();
        return false;
    }

    const EGLint configAttributes[] = {
            EGL_SURFACE_TYPE,
            mUsingGbmDisplay ? EGL_PBUFFER_BIT : EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE,
            EGL_OPENGL_BIT,
            EGL_RED_SIZE,
            8,
            EGL_GREEN_SIZE,
            8,
            EGL_BLUE_SIZE,
            8,
            EGL_ALPHA_SIZE,
            8,
            EGL_NONE,
    };
    EGLint configCount = 0;
    if (!eglChooseConfig(mDisplay, configAttributes, &mConfig, 1, &configCount) ||
        configCount == 0) {
        ALOGE("artzig: unable to choose an EGL OpenGL config (0x%x)", eglGetError());
        destroyContext();
        return false;
    }

    mEglContext = eglCreateContext(mDisplay, mConfig, EGL_NO_CONTEXT, nullptr);
    if (mEglContext == EGL_NO_CONTEXT) {
        ALOGE("artzig: unable to create the EGL render context (0x%x)", eglGetError());
        destroyContext();
        return false;
    }
    if (mUsingGbmDisplay) {
        const EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        mEglSurface = eglCreatePbufferSurface(mDisplay, mConfig, pbufferAttributes);
        if (mEglSurface == EGL_NO_SURFACE) {
            ALOGE("artzig: unable to create offscreen EGL surface (0x%x)", eglGetError());
            destroyContext();
            return false;
        }
    }
    if (const char* value = std::getenv("ARTZIG_EGL_SWAP_INTERVAL")) {
        gSwapInterval = std::atoi(value) == 0 ? 0 : 1;
        gOverrideSwapInterval = true;
    }
    gTraceFramePacing = std::getenv("ARTZIG_FRAME_PACING_TRACE") != nullptr;
    return true;
}

bool ArtzigGpuPipeline::ensureGrContext() {
    if (makeCurrent() == MakeCurrentResult::Failed) return false;
    if (mContext) return true;
    // nixpkgs' Skia selects GLX for GrGLMakeNativeInterface(). Artzig owns an EGL
    // context instead, so assemble the interface from that context's proc table.
    sk_sp<const GrGLInterface> gl = GrGLMakeAssembledInterface(nullptr, getEglProc);
    mContext = GrDirectContexts::MakeGL(std::move(gl));
    if (!mContext) {
        ALOGE("artzig: Skia could not create a Ganesh OpenGL context");
        destroyContext();
        return false;
    }
    mRenderThread.setGrContext(mContext);
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    ALOGI("artzig: HWUI Ganesh/OpenGL renderer initialized (%s; %s)",
          renderer ? renderer : "unknown renderer", version ? version : "unknown version");
    return true;
}

void ArtzigGpuPipeline::clearSurface() {
    mSurface.reset();
    if (mDisplay != EGL_NO_DISPLAY && mEglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(mDisplay, mEglSurface);
    }
    mEglSurface = EGL_NO_SURFACE;
    if (mWaylandWindow) wl_egl_window_destroy(mWaylandWindow);
    mWaylandWindow = nullptr;
    mNativeSurface = nullptr;
    mWidth = mHeight = 0;
}

void ArtzigGpuPipeline::destroyContext() {
    if (mDisplay != EGL_NO_DISPLAY && mEglSurface != EGL_NO_SURFACE &&
        mEglContext != EGL_NO_CONTEXT) {
        eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
    }
    mBufferSurface.reset();
    mBufferColorSpace.reset();
    mSurface.reset();
    if (mContext) {
        mContext->abandonContext();
        mContext.reset();
        mRenderThread.setGrContext(nullptr);
    }
    clearSurface();
    if (mDisplay != EGL_NO_DISPLAY) {
        if (mEglContext != EGL_NO_CONTEXT) eglDestroyContext(mDisplay, mEglContext);
        eglTerminate(mDisplay);
    }
    mConfig = nullptr;
    mEglContext = EGL_NO_CONTEXT;
    mDisplay = EGL_NO_DISPLAY;
    mNativeDisplay = nullptr;
    mUsingGbmDisplay = false;
}

sk_sp<SkSurface> ArtzigGpuPipeline::makeWindowSurface(int width, int height) {
    GrGLFramebufferInfo framebufferInfo;
    framebufferInfo.fFBOID = 0;
    framebufferInfo.fFormat = GL_RGBA8;
    const auto backendTarget = GrBackendRenderTargets::MakeGL(width, height, 0, 8, framebufferInfo);
    return SkSurfaces::WrapBackendRenderTarget(mContext.get(), backendTarget,
                                               kBottomLeft_GrSurfaceOrigin, mSurfaceColorType,
                                               mSurfaceColorSpace, nullptr);
}

sk_sp<SkSurface> ArtzigGpuPipeline::makeLayerSurface(int width, int height) {
    if (!ensureGrContext()) return nullptr;
    const SkImageInfo info = SkImageInfo::Make(width, height, mSurfaceColorType,
                                               kPremul_SkAlphaType, mSurfaceColorSpace);
    const SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
    // RenderNode snapshots can outlive the layer surface that produced them.
    // Keep these resources in Ganesh's budgeted cache, matching SkiaGpuPipeline,
    // so a snapshot release cannot notify an already-purged cache entry.
    return SkSurfaces::RenderTarget(mContext.get(), skgpu::Budgeted::kYes, info, 0,
                                    kTopLeft_GrSurfaceOrigin, &props);
}

bool ArtzigGpuPipeline::setSurface(ANativeWindow* surface, renderthread::SwapBehavior) {
    if (surface) {
        ALOGE("artzig: Android ANativeWindow presentation is unsupported on the host");
        return false;
    }
    clearSurface();
    return true;
}

bool ArtzigGpuPipeline::setHostSurface(void* nativeDisplay, void* nativeSurface, int32_t width,
                                       int32_t height) {
    if (!nativeDisplay || !nativeSurface || width <= 0 || height <= 0) {
        clearSurface();
        return false;
    }

    if (mUsingGbmDisplay || (mNativeDisplay && mNativeDisplay != nativeDisplay)) destroyContext();
    mNativeDisplay = nativeDisplay;
    if (!ensureContext()) return false;

    if (mNativeSurface != nativeSurface) {
        clearSurface();
        mNativeSurface = nativeSurface;
        mWaylandWindow =
                wl_egl_window_create(static_cast<wl_surface*>(nativeSurface), width, height);
        if (!mWaylandWindow) {
            ALOGE("artzig: wl_egl_window_create failed");
            clearSurface();
            return false;
        }
        mEglSurface = eglCreateWindowSurface(
                mDisplay, mConfig, reinterpret_cast<EGLNativeWindowType>(mWaylandWindow), nullptr);
        if (mEglSurface == EGL_NO_SURFACE) {
            ALOGE("artzig: eglCreateWindowSurface failed (0x%x)", eglGetError());
            clearSurface();
            return false;
        }
    } else if (width != mWidth || height != mHeight) {
        mSurface.reset();
        wl_egl_window_resize(mWaylandWindow, width, height, 0, 0);
    }

    mWidth = width;
    mHeight = height;
    if (!ensureGrContext()) {
        clearSurface();
        return false;
    }
    mSurface = makeWindowSurface(width, height);
    if (!mSurface) ALOGE("artzig: unable to wrap the Wayland EGL backbuffer");
    if (mSurface && gOverrideSwapInterval && !eglSwapInterval(mDisplay, gSwapInterval)) {
        ALOGW("artzig: eglSwapInterval(%d) failed (0x%x)", gSwapInterval, eglGetError());
    } else if (mSurface && gOverrideSwapInterval) {
        ALOGI("artzig: EGL swap interval %d", gSwapInterval);
    }
    return mSurface != nullptr;
}

MakeCurrentResult ArtzigGpuPipeline::makeCurrent() {
    if (!ensureContext() || mEglSurface == EGL_NO_SURFACE) return MakeCurrentResult::Failed;
    if (eglGetCurrentContext() == mEglContext && eglGetCurrentSurface(EGL_DRAW) == mEglSurface) {
        return MakeCurrentResult::AlreadyCurrent;
    }
    if (!eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext)) {
        ALOGE("artzig: eglMakeCurrent failed (0x%x)", eglGetError());
        return MakeCurrentResult::Failed;
    }
    return MakeCurrentResult::Succeeded;
}

Frame ArtzigGpuPipeline::getFrame() {
    return Frame(mWidth, mHeight, 0);
}

IRenderPipeline::DrawResult ArtzigGpuPipeline::draw(
        const Frame&, const SkRect&, const SkRect& dirty, const LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue, const Rect& contentDrawBounds, bool opaque,
        const LightInfo& lightInfo, const std::vector<sp<RenderNode>>& renderNodes,
        FrameInfoVisualizer*, const renderthread::HardwareBufferRenderParams& bufferParams,
        std::mutex& profilerLock) {
    if (makeCurrent() == MakeCurrentResult::Failed || !ensureGrContext()) {
        return {false, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd{}};
    }
    sk_sp<SkSurface> target = mSurface;
    SkMatrix preTransform = SkMatrix::I();
    if (mHardwareBuffer) {
        auto colorSpace = bufferParams.getColorSpace();
        if (!mBufferSurface || !SkColorSpace::Equals(mBufferColorSpace.get(), colorSpace.get())) {
            mBufferSurface = artzig::makeHardwareBufferSurface(mContext.get(), mHardwareBuffer,
                                                               colorSpace);
            mBufferColorSpace = std::move(colorSpace);
        }
        target = mBufferSurface;
        preTransform = bufferParams.getTransform();
    }
    if (!target) {
        return {false, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd{}};
    }
    SkPoint lightCenter = preTransform.mapPoint({lightGeometry.center.x, lightGeometry.center.y});
    LightGeometry localGeometry = lightGeometry;
    localGeometry.center.x = lightCenter.fX;
    localGeometry.center.y = lightCenter.fY;
    LightingInfo::updateLighting(localGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, target,
                preTransform);
    mContext->flushAndSubmit(target.get(), GrSyncCpu::kNo);
    layerUpdateQueue->clear();
    return {true, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd{}};
}

bool ArtzigGpuPipeline::swapBuffers(const Frame&, IRenderPipeline::DrawResult& drawResult,
                                    const SkRect&, FrameInfo*, bool* requireSwap) {
    *requireSwap = false;
    if (mHardwareBuffer) return false;
    if (!drawResult.success || mEglSurface == EGL_NO_SURFACE) return false;
    const auto before = gTraceFramePacing ? std::chrono::steady_clock::now()
                                          : std::chrono::steady_clock::time_point{};
    if (!eglSwapBuffers(mDisplay, mEglSurface)) {
        ALOGE("artzig: eglSwapBuffers failed (0x%x)", eglGetError());
        return false;
    }
    if (gTraceFramePacing) {
        const uint64_t elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(
                                           std::chrono::steady_clock::now() - before)
                                           .count();
        gSwapTraceSamples++;
        gSwapTraceTotalUs += elapsedUs;
        gSwapTraceMaxUs = std::max(gSwapTraceMaxUs, elapsedUs);
        if (gSwapTraceSamples == 120) {
            ALOGI("artzig pacing: eglSwapBuffers interval=%d avg=%.3fms max=%.3fms n=%u",
                  gSwapInterval, gSwapTraceTotalUs / 120000.0, gSwapTraceMaxUs / 1000.0,
                  gSwapTraceSamples);
            gSwapTraceSamples = 0;
            gSwapTraceTotalUs = 0;
            gSwapTraceMaxUs = 0;
        }
    }
    return true;
}

bool ArtzigGpuPipeline::createOrUpdateLayer(RenderNode* node,
                                            const DamageAccumulator& damageAccumulator,
                                            ErrorHandler* errorHandler) {
    // HWUI layers are bucketed to avoid replacing their SkSurface for every
    // small layout/animation size change.
    const int nodeWidth = std::max(1, node->getWidth());
    const int nodeHeight = std::max(1, node->getHeight());
    const int width = ((nodeWidth + LAYER_SIZE - 1) / LAYER_SIZE) * LAYER_SIZE;
    const int height = ((nodeHeight + LAYER_SIZE - 1) / LAYER_SIZE) * LAYER_SIZE;
    SkSurface* current = node->getLayerSurface();
    if (current && current->width() == width && current->height() == height) return false;
    node->setLayerSurface(makeLayerSurface(width, height));
    if (!node->getLayerSurface() && errorHandler) {
        errorHandler->onError("artzig: unable to allocate GPU layer");
    }
    if (node->getLayerSurface()) {
        Matrix4 windowTransform;
        damageAccumulator.computeCurrentTransform(&windowTransform);
        node->getSkiaLayer()->inverseTransformInWindow.loadInverse(windowTransform);
    }
    return true;
}

void ArtzigGpuPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool) {
    for (const auto& entry : layers.entries()) {
        RenderNode* node = entry.renderNode.get();
        if (node->getLayerSurface() && !renderLayerImpl(node, entry.damage)) return;
    }
}

android::base::unique_fd ArtzigGpuPipeline::flush() {
    if (!mContext || makeCurrent() == MakeCurrentResult::Failed) {
        return android::base::unique_fd(-1);
    }
    mContext->flushAndSubmit(GrSyncCpu::kNo);
    auto createSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
            eglGetProcAddress("eglCreateSyncKHR"));
    auto destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
            eglGetProcAddress("eglDestroySyncKHR"));
    auto dupFence = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
            eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    const char* extensions = eglQueryString(mDisplay, EGL_EXTENSIONS);
    if (createSync && destroySync && dupFence && extensions &&
        std::strstr(extensions, "EGL_ANDROID_native_fence_sync")) {
        const EGLint attributes[] = {EGL_NONE};
        EGLSyncKHR sync = createSync(mDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
        if (sync != EGL_NO_SYNC_KHR) {
            glFlush();
            const int fd = dupFence(mDisplay, sync);
            destroySync(mDisplay, sync);
            return android::base::unique_fd(fd);
        }
    }
    return android::base::unique_fd(-1);
}

void ArtzigGpuPipeline::setHardwareBuffer(AHardwareBuffer* buffer) {
    if (buffer == mHardwareBuffer) return;
    mBufferSurface.reset();
    mBufferColorSpace.reset();
    if (mHardwareBuffer) AHardwareBuffer_release(mHardwareBuffer);
    mHardwareBuffer = buffer;
    if (mHardwareBuffer) {
        AHardwareBuffer_acquire(mHardwareBuffer);
        ensureGrContext();
    }
}

}  // namespace android::uirenderer::skiapipeline
