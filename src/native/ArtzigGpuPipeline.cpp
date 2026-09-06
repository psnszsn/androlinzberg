#include "ArtzigGpuPipeline.h"
#include <azk/graphics_egl.h>

#include "HardwareBufferRender.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <SkImageInfo.h>
#include <android-base/unique_fd.h>
#include <private/android/AHardwareBufferHelpers.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>
#include <vndk/window.h>
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

// AOSP RenderThread owns one Ganesh context shared by its window pipelines.
// Keep that ownership for the GBM host platform: textures, hardware bitmaps and
// RenderNode layers may be consumed by more than one window in this process.
struct HostGpuContext {
  EGLDisplay display;
  EGLConfig config;
  EGLContext context;
  EGLSurface surface;
  sk_sp<GrDirectContext> ganesh;

  ~HostGpuContext() {
    eglMakeCurrent(display, surface, surface, context);
    if (ganesh) {
      ganesh->releaseResourcesAndAbandonContext();
      ganesh.reset();
    }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    azk_graphics_egl_terminate(display);
  }
};

namespace {

thread_local std::weak_ptr<HostGpuContext> gGbmContext;

int gSwapInterval = 1;
bool gOverrideSwapInterval = false;
bool gTraceFramePacing = false;
uint32_t gSwapTraceSamples = 0;
uint64_t gSwapTraceTotalUs = 0;
uint64_t gSwapTraceMaxUs = 0;

GrGLFuncPtr getEglProc(void *, const char name[]) {
  return reinterpret_cast<GrGLFuncPtr>(eglGetProcAddress(name));
}

} // namespace

ArtzigGpuPipeline::ArtzigGpuPipeline(renderthread::RenderThread &thread)
    : SkiaPipeline(thread) {}

ArtzigGpuPipeline::~ArtzigGpuPipeline() {
  clearAndroidWindow();
  setHardwareBuffer(nullptr);
  destroyContext();
}

bool ArtzigGpuPipeline::ensureContext() {
  if (mEglContext != EGL_NO_CONTEXT)
    return true;
  if (!mNativeDisplay) {
    if (auto shared = gGbmContext.lock()) {
      mGbmContext = std::move(shared);
      mDisplay = mGbmContext->display;
      mConfig = mGbmContext->config;
      mEglContext = mGbmContext->context;
      mEglSurface = mGbmContext->surface;
      mContext = mGbmContext->ganesh;
      mUsingGbmDisplay = true;
      return true;
    }
  }
  ALOGI("artzig: initializing %s HWUI EGL/OpenGL context",
        mNativeDisplay ? "Wayland" : "GBM offscreen");

  auto getPlatformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
      eglGetProcAddress("eglGetPlatformDisplayEXT"));
  if (!getPlatformDisplay) {
    ALOGE("artzig: EGL_EXT_platform_base is required for Wayland presentation");
    return false;
  }
  void *platformDisplay = mNativeDisplay;
  EGLenum platform = EGL_PLATFORM_WAYLAND_KHR;
  mUsingGbmDisplay = !platformDisplay;
  if (!mUsingGbmDisplay) {
    // Host presentation seam: unlike SurfaceFlinger, the synchronized Wayland
    // parent owns frame pacing. A Mesa swap interval of one throttles the child
    // independently and fills its buffer queue before the parent transaction
    // can publish it. AOSP EglManager likewise selects interval zero when
    // HWUI's external pacing disables EGL vsync.
    gSwapInterval = 0;
    gOverrideSwapInterval = true;
  }
  if (mUsingGbmDisplay) {
    platformDisplay = artzig::hardwareBufferGbmDevice();
    platform = EGL_PLATFORM_GBM_KHR;
  }
  if (!platformDisplay)
    return false;
  mDisplay = getPlatformDisplay(platform, platformDisplay, nullptr);
  if (mDisplay == EGL_NO_DISPLAY ||
      !azk_graphics_egl_initialize(mDisplay, nullptr, nullptr)) {
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
      mUsingGbmDisplay ? 0 : EGL_WINDOW_BIT,
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
    ALOGE("artzig: unable to choose an EGL OpenGL config (0x%x)",
          eglGetError());
    destroyContext();
    return false;
  }

  mEglContext = eglCreateContext(mDisplay, mConfig, EGL_NO_CONTEXT, nullptr);
  if (mEglContext == EGL_NO_CONTEXT) {
    ALOGE("artzig: unable to create the EGL render context (0x%x)",
          eglGetError());
    destroyContext();
    return false;
  }
  if (mUsingGbmDisplay) {
    const char* extensions = eglQueryString(mDisplay, EGL_EXTENSIONS);
    if (!extensions || !std::strstr(extensions, "EGL_KHR_surfaceless_context")) {
      ALOGE("artzig: GBM HWUI requires EGL_KHR_surfaceless_context");
      destroyContext();
      return false;
    }
  }
  if (const char *value = std::getenv("ARTZIG_EGL_SWAP_INTERVAL")) {
    gSwapInterval = std::atoi(value) == 0 ? 0 : 1;
    gOverrideSwapInterval = true;
  }
  gTraceFramePacing = std::getenv("ARTZIG_FRAME_PACING_TRACE") != nullptr;
  if (mUsingGbmDisplay) {
    mGbmContext = std::make_shared<HostGpuContext>();
    mGbmContext->display = mDisplay;
    mGbmContext->config = mConfig;
    mGbmContext->context = mEglContext;
    mGbmContext->surface = mEglSurface;
    gGbmContext = mGbmContext;
  }
  return true;
}

bool ArtzigGpuPipeline::ensureGrContext() {
  if (makeCurrent() == MakeCurrentResult::Failed)
    return false;
  if (mGbmContext)
    mContext = mGbmContext->ganesh;
  if (mContext) {
    mRenderThread.setGrContext(mContext);
    return true;
  }
  // The Linux Skia native interface selects GLX. Artzig owns an EGL
  // context instead, so assemble the interface from that context's proc table.
  sk_sp<const GrGLInterface> gl =
      GrGLMakeAssembledInterface(nullptr, getEglProc);
  mContext = GrDirectContexts::MakeGL(std::move(gl));
  if (!mContext) {
    ALOGE("artzig: Skia could not create a Ganesh OpenGL context");
    destroyContext();
    return false;
  }
  mRenderThread.setGrContext(mContext);
  if (mGbmContext)
    mGbmContext->ganesh = mContext;
  const char *renderer =
      reinterpret_cast<const char *>(glGetString(GL_RENDERER));
  const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
  ALOGI("artzig: HWUI Ganesh/OpenGL renderer initialized (%s; %s)",
        renderer ? renderer : "unknown renderer",
        version ? version : "unknown version");
  return true;
}

sk_sp<GrDirectContext> ArtzigGpuPipeline::ensureThreadContext(renderthread::RenderThread &thread) {
  // The thread owns this offscreen context, as AOSP RenderThread/EglManager do.
  // ensureContext joins the existing shared GBM context when windows own it.
  thread_local std::unique_ptr<ArtzigGpuPipeline> offscreen;
  if (!offscreen) offscreen = std::make_unique<ArtzigGpuPipeline>(thread);
  if (!offscreen->ensureGrContext()) return nullptr;
  return offscreen->mContext;
}

void ArtzigGpuPipeline::clearSurface() {
  mSurface.reset();
  if (mGbmContext) {
    mWidth = mHeight = 0;
    return;
  }
  if (mDisplay != EGL_NO_DISPLAY && mEglSurface != EGL_NO_SURFACE) {
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(mDisplay, mEglSurface);
  }
  mEglSurface = EGL_NO_SURFACE;
  if (mWaylandWindow)
    wl_egl_window_destroy(mWaylandWindow);
  mWaylandWindow = nullptr;
  mNativeSurface = nullptr;
  mWidth = mHeight = 0;
}

void ArtzigGpuPipeline::destroyContext() {
  clearAndroidWindow();
  if (mDisplay != EGL_NO_DISPLAY && mEglContext != EGL_NO_CONTEXT &&
      (mUsingGbmDisplay || mEglSurface != EGL_NO_SURFACE)) {
    eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
  }
  mBufferSurface.reset();
  mBufferColorSpace.reset();
  mSurface.reset();
  if (mGbmContext) {
    if (mGbmContext.use_count() == 1 &&
        mRenderThread.getGrContext() == mContext.get()) {
      mRenderThread.setGrContext(nullptr);
    }
    mContext.reset();
    mGbmContext.reset();
    mConfig = nullptr;
    mEglContext = EGL_NO_CONTEXT;
    mEglSurface = EGL_NO_SURFACE;
    mDisplay = EGL_NO_DISPLAY;
    mNativeDisplay = nullptr;
    mUsingGbmDisplay = false;
    mWidth = mHeight = 0;
    return;
  }
  if (mContext) {
    mContext->abandonContext();
    if (mRenderThread.getGrContext() == mContext.get())
      mRenderThread.setGrContext(nullptr);
    mContext.reset();
  }
  clearSurface();
  if (mDisplay != EGL_NO_DISPLAY) {
    if (mEglContext != EGL_NO_CONTEXT)
      eglDestroyContext(mDisplay, mEglContext);
    azk_graphics_egl_terminate(mDisplay);
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
  const auto backendTarget =
      GrBackendRenderTargets::MakeGL(width, height, 0, 8, framebufferInfo);
  return SkSurfaces::WrapBackendRenderTarget(
      mContext.get(), backendTarget, kBottomLeft_GrSurfaceOrigin,
      mSurfaceColorType, mSurfaceColorSpace, nullptr);
}

sk_sp<SkSurface> ArtzigGpuPipeline::makeLayerSurface(int width, int height) {
  if (!ensureGrContext())
    return nullptr;
  const SkImageInfo info =
      SkImageInfo::Make(width, height, mSurfaceColorType, kPremul_SkAlphaType,
                        mSurfaceColorSpace);
  const SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
  // RenderNode snapshots can outlive the layer surface that produced them.
  // Keep these resources in Ganesh's budgeted cache, matching SkiaGpuPipeline,
  // so a snapshot release cannot notify an already-purged cache entry.
  return SkSurfaces::RenderTarget(mContext.get(), skgpu::Budgeted::kYes, info,
                                  0, kTopLeft_GrSurfaceOrigin, &props);
}

void ArtzigGpuPipeline::cancelWindowBuffer() {
  if (!mDequeuedBuffer)
    return;
  // Skia may have submitted writes before a later draw failure. Return their
  // fence with cancellation so another producer never overwrites live work.
  auto completion = flush();
  if (completion.get() < 0 && mWindowBufferSurface &&
      makeCurrent() != MakeCurrentResult::Failed) {
    // Export failed: finish actual GPU work before returning an unfenced
    // slot. This is an error path, not normal frame synchronization.
    glFinish();
  }
  mWindowBufferSurface.reset();
  mAndroidWindow->cancelBuffer(mAndroidWindow, mDequeuedBuffer,
                               completion.release());
  mDequeuedBuffer = nullptr;
}

void ArtzigGpuPipeline::clearAndroidWindow() {
  if (!mAndroidWindow)
    return;
  cancelWindowBuffer();
  mWindowBufferSurface.reset();
  native_window_api_disconnect(mAndroidWindow, NATIVE_WINDOW_API_EGL);
  ANativeWindow_release(mAndroidWindow);
  mAndroidWindow = nullptr;
}

bool ArtzigGpuPipeline::setSurface(ANativeWindow *surface,
                                   renderthread::SwapBehavior) {
  if (surface && surface == mAndroidWindow)
    return true;
  clearAndroidWindow();
  if (!surface) {
    if (!mUsingGbmDisplay)
      clearSurface();
    return true;
  }
  // Mesa has no Android EGL native-window platform. Follow EglManager's
  // producer connect/dequeue/queue contract, importing the stock buffer into
  // the offscreen EGL context instead of asking Mesa to own the swapchain.
  if (mNativeDisplay)
    destroyContext();
  if (!ensureGrContext())
    return false;
  int status = native_window_api_connect(surface, NATIVE_WINDOW_API_EGL);
  if (status != OK) {
    ALOGE("artzig: Surface connect failed: %d", status);
    return false;
  }
  status = native_window_set_usage(surface,
                                   AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                                       AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE);
  if (status == OK)
    status = native_window_set_buffers_format(surface, PIXEL_FORMAT_RGBA_8888);
  if (status != OK) {
    native_window_api_disconnect(surface, NATIVE_WINDOW_API_EGL);
    ALOGE("artzig: Surface buffer configuration failed: %d", status);
    return false;
  }
  ANativeWindow_acquire(surface);
  mAndroidWindow = surface;
  mWidth = std::max(1, ANativeWindow_getWidth(surface));
  mHeight = std::max(1, ANativeWindow_getHeight(surface));
  return true;
}

bool ArtzigGpuPipeline::setHostSurface(void *nativeDisplay, void *nativeSurface,
                                       int32_t width, int32_t height) {
  clearAndroidWindow();
  if (!nativeDisplay || !nativeSurface || width <= 0 || height <= 0) {
    clearSurface();
    return false;
  }

  if (mUsingGbmDisplay || (mNativeDisplay && mNativeDisplay != nativeDisplay))
    destroyContext();
  mNativeDisplay = nativeDisplay;
  if (!ensureContext())
    return false;

  if (mNativeSurface != nativeSurface) {
    clearSurface();
    mNativeSurface = nativeSurface;
    mWaylandWindow = wl_egl_window_create(
        static_cast<wl_surface *>(nativeSurface), width, height);
    if (!mWaylandWindow) {
      ALOGE("artzig: wl_egl_window_create failed");
      clearSurface();
      return false;
    }
    mEglSurface = eglCreateWindowSurface(
        mDisplay, mConfig,
        reinterpret_cast<EGLNativeWindowType>(mWaylandWindow), nullptr);
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
  if (!mSurface)
    ALOGE("artzig: unable to wrap the Wayland EGL backbuffer");
  if (mSurface && gOverrideSwapInterval &&
      !eglSwapInterval(mDisplay, gSwapInterval)) {
    ALOGW("artzig: eglSwapInterval(%d) failed (0x%x)", gSwapInterval,
          eglGetError());
  } else if (mSurface && gOverrideSwapInterval) {
    ALOGI("artzig: EGL swap interval %d", gSwapInterval);
  }
  return mSurface != nullptr;
}

MakeCurrentResult ArtzigGpuPipeline::makeCurrent() {
  if (!ensureContext() || (!mUsingGbmDisplay && mEglSurface == EGL_NO_SURFACE))
    return MakeCurrentResult::Failed;
  if (eglGetCurrentContext() == mEglContext &&
      eglGetCurrentSurface(EGL_DRAW) == mEglSurface) {
    if (mContext)
      mRenderThread.setGrContext(mContext);
    return MakeCurrentResult::AlreadyCurrent;
  }
  if (!eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext)) {
    ALOGE("artzig: eglMakeCurrent failed (0x%x)", eglGetError());
    return MakeCurrentResult::Failed;
  }
  if (mContext)
    mRenderThread.setGrContext(mContext);
  return MakeCurrentResult::Succeeded;
}

Frame ArtzigGpuPipeline::getFrame() {
  if (mAndroidWindow) {
    cancelWindowBuffer();
    int fd = -1;
    int status =
        ANativeWindow_dequeueBuffer(mAndroidWindow, &mDequeuedBuffer, &fd);
    auto acquire = sp<Fence>::make(fd);
    if (status != OK || !mDequeuedBuffer) {
      ALOGE("artzig: Surface dequeue failed: %d", status);
      mDequeuedBuffer = nullptr;
      return Frame(mWidth, mHeight, 0);
    }
    // This is HWUI's render thread, never the Wayland or Java event loop.
    // Bound a broken consumer's fence wait and preserve ownership on error.
    status = acquire->wait(3000);
    if (status != OK) {
      mAndroidWindow->cancelBuffer(mAndroidWindow, mDequeuedBuffer,
                                   acquire->dup());
      mDequeuedBuffer = nullptr;
      ALOGE("artzig: Surface acquire fence failed: %d", status);
      return Frame(mWidth, mHeight, 0);
    }
    mWidth = mDequeuedBuffer->width;
    mHeight = mDequeuedBuffer->height;
    if (gTraceFramePacing) ALOGI("artzig pacing: Surface %p dequeue %dx%d", mAndroidWindow, mWidth, mHeight);
    if (ensureGrContext()) {
      auto buffer = GraphicBuffer::from(mDequeuedBuffer);
      mWindowBufferSurface = artzig::makeHardwareBufferSurface(
          mContext.get(), AHardwareBuffer_from_GraphicBuffer(buffer.get()),
          mSurfaceColorSpace);
    }
  }
  return Frame(mWidth, mHeight, 0);
}

IRenderPipeline::DrawResult ArtzigGpuPipeline::draw(
    const Frame &, const SkRect &, const SkRect &dirty,
    const LightGeometry &lightGeometry, LayerUpdateQueue *layerUpdateQueue,
    const Rect &contentDrawBounds, bool opaque, const LightInfo &lightInfo,
    const std::vector<sp<RenderNode>> &renderNodes, FrameInfoVisualizer *,
    const renderthread::HardwareBufferRenderParams &bufferParams,
    std::mutex &profilerLock) {
  if (makeCurrent() == MakeCurrentResult::Failed || !ensureGrContext()) {
    return {false, IRenderPipeline::DrawResult::kUnknownTime,
            android::base::unique_fd{}};
  }
  sk_sp<SkSurface> target = mAndroidWindow ? mWindowBufferSurface : mSurface;
  SkMatrix preTransform = SkMatrix::I();
  if (mHardwareBuffer) {
    auto colorSpace = bufferParams.getColorSpace();
    if (!mBufferSurface ||
        !SkColorSpace::Equals(mBufferColorSpace.get(), colorSpace.get())) {
      mBufferSurface = artzig::makeHardwareBufferSurface(
          mContext.get(), mHardwareBuffer, colorSpace);
      mBufferColorSpace = std::move(colorSpace);
    }
    target = mBufferSurface;
    preTransform = bufferParams.getTransform();
  }
  if (!target) {
    return {false, IRenderPipeline::DrawResult::kUnknownTime,
            android::base::unique_fd{}};
  }
  SkPoint lightCenter =
      preTransform.mapPoint({lightGeometry.center.x, lightGeometry.center.y});
  LightGeometry localGeometry = lightGeometry;
  localGeometry.center.x = lightCenter.fX;
  localGeometry.center.y = lightCenter.fY;
  LightingInfo::updateLighting(localGeometry, lightInfo);
  renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds,
              target, preTransform);
  mContext->flushAndSubmit(target.get(), GrSyncCpu::kNo);
  layerUpdateQueue->clear();
  return {true, IRenderPipeline::DrawResult::kUnknownTime,
          android::base::unique_fd{}};
}

bool ArtzigGpuPipeline::swapBuffers(const Frame &,
                                    IRenderPipeline::DrawResult &drawResult,
                                    const SkRect &, FrameInfo *,
                                    bool *requireSwap) {
  *requireSwap = false;
  if (mHardwareBuffer)
    return false;
  if (mAndroidWindow) {
    *requireSwap = true;
    if (!drawResult.success || !mDequeuedBuffer) {
      cancelWindowBuffer();
      return false;
    }
    auto completion = flush();
    if (completion.get() < 0) {
      ALOGE("artzig: Surface completion fence export failed");
      cancelWindowBuffer();
      return false;
    }
    drawResult.presentFence.reset(dup(completion.get()));
    const int status = mAndroidWindow->queueBuffer(
        mAndroidWindow, mDequeuedBuffer, completion.release());
    if (status != OK) {
      ALOGE("artzig: Surface queue failed: %d", status);
      cancelWindowBuffer();
      return false;
    }
    mWindowBufferSurface.reset();
    mDequeuedBuffer = nullptr;
    if (gTraceFramePacing) ALOGI("artzig pacing: Surface %p queued", mAndroidWindow);
    return true;
  }
  if (!drawResult.success || mEglSurface == EGL_NO_SURFACE)
    return false;
  const auto before = gTraceFramePacing
                          ? std::chrono::steady_clock::now()
                          : std::chrono::steady_clock::time_point{};
  if (!eglSwapBuffers(mDisplay, mEglSurface)) {
    ALOGE("artzig: eglSwapBuffers failed (0x%x)", eglGetError());
    return false;
  }
  if (gTraceFramePacing) {
    const uint64_t elapsedUs =
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - before)
            .count();
    gSwapTraceSamples++;
    gSwapTraceTotalUs += elapsedUs;
    gSwapTraceMaxUs = std::max(gSwapTraceMaxUs, elapsedUs);
    if (gSwapTraceSamples == 120) {
      ALOGI("artzig pacing: eglSwapBuffers interval=%d avg=%.3fms max=%.3fms "
            "n=%u",
            gSwapInterval, gSwapTraceTotalUs / 120000.0,
            gSwapTraceMaxUs / 1000.0, gSwapTraceSamples);
      gSwapTraceSamples = 0;
      gSwapTraceTotalUs = 0;
      gSwapTraceMaxUs = 0;
    }
  }
  return true;
}

bool ArtzigGpuPipeline::createOrUpdateLayer(
    RenderNode *node, const DamageAccumulator &damageAccumulator,
    ErrorHandler *errorHandler) {
  // HWUI layers are bucketed to avoid replacing their SkSurface for every
  // small layout/animation size change.
  const int nodeWidth = std::max(1, node->getWidth());
  const int nodeHeight = std::max(1, node->getHeight());
  const int width = ((nodeWidth + LAYER_SIZE - 1) / LAYER_SIZE) * LAYER_SIZE;
  const int height = ((nodeHeight + LAYER_SIZE - 1) / LAYER_SIZE) * LAYER_SIZE;
  SkSurface *current = node->getLayerSurface();
  if (current && current->width() == width && current->height() == height)
    return false;
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

void ArtzigGpuPipeline::renderLayersImpl(const LayerUpdateQueue &layers, bool) {
  for (const auto &entry : layers.entries()) {
    RenderNode *node = entry.renderNode.get();
    if (node->getLayerSurface() && !renderLayerImpl(node, entry.damage))
      return;
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
  const char *extensions = eglQueryString(mDisplay, EGL_EXTENSIONS);
  if (createSync && destroySync && dupFence && extensions &&
      std::strstr(extensions, "EGL_ANDROID_native_fence_sync")) {
    const EGLint attributes[] = {EGL_NONE};
    EGLSyncKHR sync =
        createSync(mDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (sync != EGL_NO_SYNC_KHR) {
      glFlush();
      const int fd = dupFence(mDisplay, sync);
      destroySync(mDisplay, sync);
      return android::base::unique_fd(fd);
    }
  }
  return android::base::unique_fd(-1);
}

void ArtzigGpuPipeline::setHardwareBuffer(AHardwareBuffer *buffer) {
  if (buffer == mHardwareBuffer)
    return;
  mBufferSurface.reset();
  mBufferColorSpace.reset();
  if (mHardwareBuffer)
    AHardwareBuffer_release(mHardwareBuffer);
  mHardwareBuffer = buffer;
  if (mHardwareBuffer) {
    AHardwareBuffer_acquire(mHardwareBuffer);
    ensureGrContext();
  }
}

} // namespace android::uirenderer::skiapipeline
