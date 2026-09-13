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
#include <gbm.h>
#include <drm/drm_fourcc.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <algorithm>
#include <array>
#include <new>
#include <vector>

namespace {
struct Window {
    ANativeWindow* native;
    EGLContext copyContext = EGL_NO_CONTEXT;
    EGLDisplay display = EGL_NO_DISPLAY;
    ~Window() {
        if (copyContext != EGL_NO_CONTEXT) eglDestroyContext(display, copyContext);
        native_window_set_buffers_format(native, 0);
        native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL);
        ANativeWindow_release(native);
    }
};
struct Current {
    EGLDisplay display = eglGetCurrentDisplay();
    EGLSurface draw = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface read = eglGetCurrentSurface(EGL_READ);
    EGLContext context = eglGetCurrentContext();
    EGLenum api = eglQueryAPI();
    EGLDisplay target;
    ~Current() {
        eglMakeCurrent(display == EGL_NO_DISPLAY ? target : display, draw, read, context);
        eglBindAPI(api);
    }
};
EGLImageKHR imageFromBo(EGLDisplay display, gbm_bo* bo) {
    auto create = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    if (!create) return EGL_NO_IMAGE_KHR;
    const int count = gbm_bo_get_plane_count(bo);
    if (count < 1 || count > 4) return EGL_NO_IMAGE_KHR;
    std::array<android::base::unique_fd, 4> fds;
    std::vector<EGLint> attrs{EGL_WIDTH, EGLint(gbm_bo_get_width(bo)), EGL_HEIGHT,
        EGLint(gbm_bo_get_height(bo)), EGL_LINUX_DRM_FOURCC_EXT, EGLint(gbm_bo_get_format(bo))};
    constexpr EGLint keys[4][5] = {
        {EGL_DMA_BUF_PLANE0_FD_EXT, EGL_DMA_BUF_PLANE0_OFFSET_EXT, EGL_DMA_BUF_PLANE0_PITCH_EXT,
         EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT},
        {EGL_DMA_BUF_PLANE1_FD_EXT, EGL_DMA_BUF_PLANE1_OFFSET_EXT, EGL_DMA_BUF_PLANE1_PITCH_EXT,
         EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT},
        {EGL_DMA_BUF_PLANE2_FD_EXT, EGL_DMA_BUF_PLANE2_OFFSET_EXT, EGL_DMA_BUF_PLANE2_PITCH_EXT,
         EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT},
        {EGL_DMA_BUF_PLANE3_FD_EXT, EGL_DMA_BUF_PLANE3_OFFSET_EXT, EGL_DMA_BUF_PLANE3_PITCH_EXT,
         EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT}};
    const uint64_t modifier = gbm_bo_get_modifier(bo);
    for (int i = 0; i < count; ++i) {
        fds[i].reset(gbm_bo_get_fd_for_plane(bo, i));
        if (fds[i].get() < 0) return EGL_NO_IMAGE_KHR;
        attrs.insert(attrs.end(), {keys[i][0], fds[i].get(), keys[i][1], EGLint(gbm_bo_get_offset(bo, i)),
            keys[i][2], EGLint(gbm_bo_get_stride_for_plane(bo, i))});
        if (modifier != DRM_FORMAT_MOD_INVALID) attrs.insert(attrs.end(), {
            keys[i][3], EGLint(modifier), keys[i][4], EGLint(modifier >> 32)});
    }
    attrs.push_back(EGL_NONE);
    return create(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attrs.data());
}
// The independent ES3 context allows both ES1 and ES2 applications to present,
// without changing their GL objects or bindings.
bool makeCopyCurrent(Window* window, EGLDisplay display) {
    eglBindAPI(EGL_OPENGL_ES_API);
    if (window->copyContext == EGL_NO_CONTEXT) {
        EGLConfig config;
        EGLint count = 0;
        const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE};
        if (!eglChooseConfig(display, attrs, &config, 1, &count) || count != 1) return false;
        const EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        window->copyContext = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttrs);
        window->display = display;
        if (window->copyContext == EGL_NO_CONTEXT) return false;
    }
    return eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, window->copyContext);
}
bool copy(Window* window, EGLDisplay display, gbm_bo* bo, ANativeWindowBuffer* buffer, int acquire,
          int* release) {
    Current current{.target = display};
    if (!makeCopyCurrent(window, display) || artzig::waitOnGpuFence(acquire) != 0) return false;
    auto targetImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    auto destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    if (!targetImage || !destroy) return false;
    EGLImageKHR images[] = {imageFromBo(display, bo), EGL_NO_IMAGE_KHR};
    if (images[0] == EGL_NO_IMAGE_KHR) return false;
    if (azk_graphics_import_egl_image(display, buffer->handle, &images[1]) != 0) {
        destroy(display, images[0]);
        return false;
    }
    GLuint textures[2], framebuffers[2];
    glGenTextures(2, textures);
    glGenFramebuffers(2, framebuffers);
    bool valid = true;
    for (int i = 0; i < 2; ++i) {
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        targetImage(GL_TEXTURE_2D, images[i]);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0);
        valid &= glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }
    if (valid) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffers[0]);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[1]);
        glBlitFramebuffer(0, 0, gbm_bo_get_width(bo), gbm_bo_get_height(bo),
            0, 0, buffer->width, buffer->height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        valid = glGetError() == GL_NO_ERROR;
        // GBM's release_buffer has no fence argument. Complete its GPU reads
        // before returning the source BO to the driver's reusable pool.
        glFinish();
        EGLSyncKHR fence = EGL_NO_SYNC_KHR;
        EGLDisplay fenceDisplay = EGL_NO_DISPLAY;
        if (valid) valid = artzig::createGpuReleaseFence(false, &fence, &fenceDisplay, release) == 0;
    }
    glDeleteFramebuffers(2, framebuffers);
    glDeleteTextures(2, textures);
    destroy(display, images[0]);
    destroy(display, images[1]);
    return valid;
}
}

extern "C" void* azk_egl_window_connect(ANativeWindow* native, EGLDisplay display,
        EGLConfig config, const EGLint* attrs, int* width, int* height, int* error) {
    *error = EGL_BAD_NATIVE_WINDOW;
    if (!native || native->common.magic != ANDROID_NATIVE_WINDOW_MAGIC) return nullptr;
    int valid = 0;
    native->query(native, NATIVE_WINDOW_IS_VALID, &valid);
    if (!valid) return nullptr;
    if (native_window_api_connect(native, NATIVE_WINDOW_API_EGL) != 0) {
        *error = EGL_BAD_ALLOC;
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
    auto window = new (std::nothrow) Window{native};
    if (!window) { native_window_api_disconnect(native, NATIVE_WINDOW_API_EGL); return nullptr; }
    ANativeWindow_acquire(native);
    *width = std::max(1, ANativeWindow_getWidth(native));
    *height = std::max(1, ANativeWindow_getHeight(native));
    return window;
}
extern "C" void azk_egl_window_disconnect(void* opaque, bool displayAlive) {
    auto window = static_cast<Window*>(opaque);
    if (!displayAlive) window->copyContext = EGL_NO_CONTEXT; // EGL termination owns its destruction.
    delete window;
}
extern "C" int azk_egl_window_timestamp(void* opaque, int64_t time) {
    return native_window_set_buffers_timestamp(static_cast<Window*>(opaque)->native, time);
}
extern "C" int azk_egl_window_present(void* opaque, EGLDisplay display, gbm_surface* surface,
        int* width, int* height) {
    auto window = static_cast<Window*>(opaque);
    gbm_bo* bo = gbm_surface_lock_front_buffer(surface);
    if (!bo) return EGL_BAD_ALLOC;
    ANativeWindowBuffer* buffer = nullptr;
    int fd = -1;
    int status = ANativeWindow_dequeueBuffer(window->native, &buffer, &fd);
    android::base::unique_fd acquire(fd);
    if (status != 0 || !buffer) { gbm_surface_release_buffer(surface, bo); return EGL_BAD_NATIVE_WINDOW; }
    *width = buffer->width;
    *height = buffer->height;
    int release = -1;
    const bool copied = copy(window, display, bo, buffer, acquire.get(), &release);
    gbm_surface_release_buffer(surface, bo);
    if (!copied) {
        window->native->cancelBuffer(window->native, buffer, acquire.release());
        return EGL_BAD_ALLOC;
    }
    status = window->native->queueBuffer(window->native, buffer, release);
    if (status != 0) window->native->cancelBuffer(window->native, buffer, -1);
    return status == 0 ? EGL_SUCCESS : EGL_BAD_NATIVE_WINDOW;
}

// Pinned EGLImpl uses this private libEGL API for its initialization counter.
extern "C" int azk_egl_initCount(EGLDisplay);
namespace android {
int egl_get_init_count(EGLDisplay display) { return azk_egl_initCount(display); }
}
