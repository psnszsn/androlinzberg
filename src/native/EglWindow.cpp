// Native-window driver boundary for the pinned EGL JNI. Mesa's GBM platform
// renders its own drawables; Android consumers receive frames through the stock
// ANativeWindow producer API, with acquire/release fences and buffer ownership.
// Follows eglCreateWindowSurfaceTmpl/getNativePixelFormat/dataSpaceFromEGLColorSpace
// in frameworks/native/opengl/libs/EGL/egl_platform_entries.cpp. Its Android
// vendor-loader implementation cannot drive Mesa's GBM native-window ABI.
#include "HardwareBufferRender.h"
#include <android/native_window.h>
#include <android-base/unique_fd.h>
#include <azk/graphics_egl.h>
#include <system/window.h>
#include <ui/Fence.h>
#include <algorithm>
#include <new>

#include <azk/graphics_buffer.h>
#include <gui/Surface.h>
#include <ui/GraphicBuffer.h>

namespace {
struct Window {
    ANativeWindow* native;
    int format;
    ~Window() {
        native_window_set_buffers_format(native, 0);
        native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL);
        ANativeWindow_release(native);
    }
    android::Surface* surface() { return static_cast<android::Surface*>(native); }
};
}

extern "C" void* azk_egl_window_connect(ANativeWindow* native, EGLDisplay display,
        EGLConfig config, const EGLint* attrs, int* width, int* height, int* error) {
    *error = EGL_BAD_NATIVE_WINDOW;
    if (!native || native->common.magic != ANDROID_NATIVE_WINDOW_MAGIC) return nullptr;
    int type = 0;
    native->query(native, NATIVE_WINDOW_CONCRETE_TYPE, &type);
    if (type != NATIVE_WINDOW_SURFACE) return nullptr;
    int valid = 0;
    native->query(native, NATIVE_WINDOW_IS_VALID, &valid);
    if (!valid) return nullptr;
    if (native_window_api_connect(native, NATIVE_WINDOW_API_EGL) != 0) {
        *error = EGL_BAD_ALLOC;
        return nullptr;
    }
    // Reserve enough producer slots for Mesa's bounded pool. The Zig owner
    // detaches released buffers before attaching; attach must not replace a
    // tracked free slot and thereby discard its release fence.
    auto* surface = static_cast<android::Surface*>(native);
    if (surface->setMaxDequeuedBufferCount(4) != android::OK) {
        native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL);
        return nullptr;
    }
    EGLint r = 0, g = 0, b = 0, a = 0;
    eglGetConfigAttrib(display, config, EGL_RED_SIZE, &r);
    eglGetConfigAttrib(display, config, EGL_GREEN_SIZE, &g);
    eglGetConfigAttrib(display, config, EGL_BLUE_SIZE, &b);
    eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &a);
    const int depth = r + g + b;
    const int format = r == 16 ? HAL_PIXEL_FORMAT_RGBA_FP16 :
        (a == 0 && r == 8 && g == 0 && b == 0) ? AHARDWAREBUFFER_FORMAT_R8_UNORM :
        (a == 0 && depth <= 16) ? HAL_PIXEL_FORMAT_RGB_565 :
        depth > 24 ? HAL_PIXEL_FORMAT_RGBA_1010102 :
        a == 0 ? HAL_PIXEL_FORMAT_RGBX_8888 : HAL_PIXEL_FORMAT_RGBA_8888;
    int color = EGL_GL_COLORSPACE_LINEAR_KHR;
    for (int i = 0; attrs && attrs[i] != EGL_NONE; i += 2)
        if (attrs[i] == EGL_GL_COLORSPACE_KHR) color = attrs[i + 1];
    android_dataspace space = HAL_DATASPACE_UNKNOWN;
    switch (color) {
        case EGL_GL_COLORSPACE_SRGB_KHR: space = HAL_DATASPACE_V0_SRGB; break;
        case EGL_GL_COLORSPACE_DISPLAY_P3_EXT:
        case EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT: space = HAL_DATASPACE_DISPLAY_P3; break;
        case EGL_GL_COLORSPACE_DISPLAY_P3_LINEAR_EXT: space = HAL_DATASPACE_DISPLAY_P3_LINEAR; break;
        case EGL_GL_COLORSPACE_SCRGB_EXT: space = HAL_DATASPACE_V0_SCRGB; break;
        case EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT: space = HAL_DATASPACE_V0_SCRGB_LINEAR; break;
        case 0x3540: // EGL_GL_COLORSPACE_BT2020_HLG_EXT (pinned Android EGL extension)
 space = static_cast<android_dataspace>(HAL_DATASPACE_BT2020_HLG); break;
        case EGL_GL_COLORSPACE_BT2020_PQ_EXT: space = HAL_DATASPACE_BT2020_PQ; break;
        case EGL_GL_COLORSPACE_BT2020_LINEAR_EXT:
            space = format == HAL_PIXEL_FORMAT_RGBA_FP16 ? static_cast<android_dataspace>(
                HAL_DATASPACE_STANDARD_BT2020 | HAL_DATASPACE_TRANSFER_LINEAR | HAL_DATASPACE_RANGE_EXTENDED) :
                HAL_DATASPACE_BT2020_LINEAR;
            break;
    }
    if (native_window_set_usage(native, AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) != 0 ||
        native_window_set_buffers_format(native, format) != 0 ||
        native_window_set_buffers_data_space(native, space) != 0) {
        native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL);
        return nullptr;
    }
    native->setSwapInterval(native, 1);
    *error = EGL_BAD_ALLOC;
    auto window = new (std::nothrow) Window{native, format};
    if (!window) { native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL); return nullptr; }
    ANativeWindow_acquire(native);
    *width = std::max(1, ANativeWindow_getWidth(native));
    *height = std::max(1, ANativeWindow_getHeight(native));
    return window;
}
extern "C" void azk_egl_window_disconnect(void* opaque, bool) {
    delete static_cast<Window*>(opaque);
}
extern "C" int azk_egl_window_timestamp(void* opaque, int64_t time) {
    return native_window_set_buffers_timestamp(static_cast<Window*>(opaque)->native, time);
}
// Surface owns generation numbers, slot selection, crop, timestamps and callbacks.
// The Zig producer supplies existing storage; no allocation or pixel rendering here.
extern "C" int azk_egl_window_requirements(void* opaque, int* width, int* height,
        uint32_t* format, uint64_t* usage, int* capacity) {
    auto* w = static_cast<Window*>(opaque);
    int valid = 0;
    w->native->query(w->native, NATIVE_WINDOW_IS_VALID, &valid);
    if (!valid) return android::NO_INIT;
    int undequeued = 0;
    if (w->native->query(w->native, NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &undequeued) != 0)
        return android::NO_INIT;
    *capacity = undequeued + 4;
    *format = w->format;
    auto result = w->surface()->getConsumerUsage(usage);
    if (result != android::OK) return result;
    *usage |= AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    if (w->native->query(w->native, NATIVE_WINDOW_DEFAULT_WIDTH, width) != 0 ||
        w->native->query(w->native, NATIVE_WINDOW_DEFAULT_HEIGHT, height) != 0)
        return android::NO_INIT;
    *width = std::max(1, *width);
    *height = std::max(1, *height);
    return android::OK;
}
extern "C" int azk_egl_window_submit(void* opaque, const AzkGraphicsBufferInfo* source,
        int renderFence, uint64_t* id) {
    using namespace android;
    auto* w = static_cast<Window*>(opaque);
    native_handle_t* handle = nullptr;
    auto status = azk_graphics_import_external(source, &handle);
    if (status != OK) return status;
    sp<GraphicBuffer> buffer = sp<GraphicBuffer>::make(handle, GraphicBuffer::CLONE_HANDLE,
        source->width, source->height, PixelFormat(source->format), 1, source->usage,
        source->stride / bytesPerPixel(source->format));
    azk_graphics_free(handle);
    if ((status = buffer->initCheck()) != OK) return status;
    android::base::unique_fd fence(renderFence < 0 ? -1 : dup(renderFence));
    if (renderFence >= 0 && fence.get() < 0) return -errno;
    const Rect crop(source->width, source->height);
    if ((status = w->surface()->setCrop(&crop)) != OK) return status;
    if ((status = w->surface()->attachBuffer(buffer->getNativeBuffer())) != OK) return status;
    *id = buffer->getId();
    status = w->native->queueBuffer(w->native, buffer->getNativeBuffer(), fence.release());
    if (status != OK) w->native->cancelBuffer(w->native, buffer->getNativeBuffer(), renderFence < 0 ? -1 : dup(renderFence));
    return status;
}
extern "C" int azk_egl_window_reclaim(void* opaque, uint64_t* id, int* fd) {
    android::sp<android::GraphicBuffer> buffer;
    android::sp<android::Fence> fence;
    auto status = static_cast<Window*>(opaque)->surface()->detachNextBuffer(&buffer, &fence);
    if (status != android::OK) return status;
    *id = buffer->getId();
    *fd = fence->dup();
    if (fence->isValid() && *fd < 0) return -errno;
    return android::OK;
}
extern "C" int azk_egl_render_fence(int* fd) {
    EGLSyncKHR fence = EGL_NO_SYNC_KHR;
    EGLDisplay display = EGL_NO_DISPLAY;
    return artzig::createGpuReleaseFence(false, &fence, &display, fd);
}

// Pinned EGLImpl uses this private libEGL API for its initialization counter.
extern "C" int azk_egl_initCount(EGLDisplay);
namespace android {
int egl_get_init_count(EGLDisplay display) { return azk_egl_initCount(display); }
}
