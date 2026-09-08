// HWUI/Skia side of the stock AHardwareBuffer/GraphicBuffer seam. GBM storage
// lives in graphics_buffer.zig; Java handles use the stock framework JNI.
#include "HardwareBufferRender.h"

#define LOG_TAG "HardwareBufferRender"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <android/hardware_buffer_jni.h>
#include <vndk/hardware_buffer.h>
#include <azk/graphics_buffer.h>
#include <azk/graphics_egl.h>
#include <drm/drm_fourcc.h>
#include <log/log.h>

#include <cinttypes>
#include <cstring>
#include <new>
#include <utility>
#include <cerrno>
#include <unistd.h>
#include <android-base/unique_fd.h>
#include <sync/sync.h>
#include <utils/Errors.h>

#include <include/core/SkColorSpace.h>
#include <include/core/SkColorType.h>
#include <include/core/SkImage.h>
#include <include/core/SkSurface.h>
#include <include/gpu/GpuTypes.h>
#include <include/gpu/ganesh/GrBackendSurface.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include <include/gpu/ganesh/gl/GrGLBackendSurface.h>

namespace {

struct RenderFormat {
    uint32_t ahbFormat;
    GrGLenum glFormat;
    SkColorType colorType;
};

constexpr RenderFormat kFormats[] = {
        {AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, GL_RGBA8, kRGBA_8888_SkColorType},
        {AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM, GL_RGBA8, kRGBA_8888_SkColorType},
        {AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM, GL_RGB565, kRGB_565_SkColorType},
        {AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, GL_RGBA16F, kRGBA_F16_SkColorType},
        {AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, GL_RGB10_A2, kRGBA_1010102_SkColorType},
};

const RenderFormat* renderFormat(uint32_t format) {
    for (const auto& candidate : kFormats) {
        if (candidate.ahbFormat == format) return &candidate;
    }
    return nullptr;
}

struct ImportedTexture {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    AHardwareBuffer* buffer = nullptr;
};

void releaseImportedTexture(void* context) {
    auto* imported = static_cast<ImportedTexture*>(context);
    if (imported->texture) glDeleteTextures(1, &imported->texture);
    if (imported->image != EGL_NO_IMAGE_KHR) {
        auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                eglGetProcAddress("eglDestroyImageKHR"));
        if (destroyImage) destroyImage(imported->display, imported->image);
    }
    if (imported->buffer) AHardwareBuffer_release(imported->buffer);
    delete imported;
}


}  // namespace

namespace artzig {

// EglManager::fenceWait/createReleaseFence over the context owned by our host
// pipeline. Android EglManager/VulkanManager cannot own that GBM context.
int waitOnGpuFence(int fence) {
    if (fence == -1) return android::OK;
    const EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT)
        return android::INVALID_OPERATION;
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    auto create = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto destroy = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    auto wait = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(eglGetProcAddress("eglWaitSyncKHR"));
    if (create && destroy && wait && extensions &&
        std::strstr(extensions, "EGL_ANDROID_native_fence_sync") &&
        std::strstr(extensions, "EGL_KHR_wait_sync")) {
        android::base::unique_fd copy(::dup(fence));
        if (copy.get() < 0) return -errno;
        const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, copy.get(), EGL_NONE};
        EGLSyncKHR sync = create(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
        if (sync == EGL_NO_SYNC_KHR) return android::UNKNOWN_ERROR;
        copy.release(); // EGL owns a successfully imported descriptor.
        wait(display, sync, 0);
        const EGLint error = eglGetError();
        destroy(display, sync);
        return error == EGL_SUCCESS ? android::OK : android::UNKNOWN_ERROR;
    }
    int result = sync_wait(fence, 3000);
    if (result < 0 && errno == ETIME) {
        ALOGE("Host GPU acquire fence %d did not signal in 3000 ms", fence);
        result = sync_wait(fence, -1);
    }
    return result < 0 ? -errno : android::OK;
}

int createGpuReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence,
                          EGLDisplay* display, int* nativeFence) {
    *nativeFence = -1;
    *display = eglGetCurrentDisplay();
    if (*display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT)
        return android::INVALID_OPERATION;
    const char* extensions = eglQueryString(*display, EGL_EXTENSIONS);
    auto create = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto destroy = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    auto duplicate = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
            eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    if (create && destroy && duplicate && extensions &&
        std::strstr(extensions, "EGL_ANDROID_native_fence_sync")) {
        EGLSyncKHR sync = create(*display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
        if (sync == EGL_NO_SYNC_KHR) return android::UNKNOWN_ERROR;
        glFlush();
        const int fd = duplicate(*display, sync);
        destroy(*display, sync);
        if (fd == EGL_NO_NATIVE_FENCE_FD_ANDROID) return android::UNKNOWN_ERROR;
        *nativeFence = fd;
        *eglFence = EGL_NO_SYNC_KHR;
        return android::OK;
    }
    auto wait = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
            eglGetProcAddress("eglClientWaitSyncKHR"));
    if (useFenceSync && create && destroy && wait && extensions &&
        std::strstr(extensions, "EGL_KHR_fence_sync")) {
        if (*eglFence != EGL_NO_SYNC_KHR) {
            const EGLint result = wait(*display, *eglFence, 0, 1000000000);
            if (result == EGL_FALSE) return android::UNKNOWN_ERROR;
            if (result == EGL_TIMEOUT_EXPIRED_KHR) return android::TIMED_OUT;
            destroy(*display, *eglFence);
        }
        *eglFence = create(*display, EGL_SYNC_FENCE_KHR, nullptr);
        if (*eglFence == EGL_NO_SYNC_KHR) return android::UNKNOWN_ERROR;
        glFlush();
        return android::OK;
    }
    // Host EGL implementations without transferable fences must complete writes
    // before returning a buffer to its producer.
    glFinish();
    *eglFence = EGL_NO_SYNC_KHR;
    return android::OK;
}

bool describeDmabuf(const AHardwareBuffer* buffer, DmabufDescription* description) {
    if (!buffer || !description) return false;
    AzkGraphicsBufferInfo info{};
    if (azk_graphics_describe(AHardwareBuffer_getNativeHandle(buffer), &info) != 0) return false;
    *description = {};
    description->width = info.width;
    description->height = info.height;
    description->drmFormat = info.drm_format;
    description->planeCount = 1;
    description->planes[0] = {info.fd, info.offset, info.stride, info.modifier};
    return true;
}

gbm_device* hardwareBufferGbmDevice() {
    return static_cast<gbm_device*>(azk_graphics_gbm_device());
}

sk_sp<SkSurface> makeHardwareBufferSurface(GrDirectContext* context,
                                           AHardwareBuffer* buffer,
                                           sk_sp<SkColorSpace> colorSpace) {
    if (!context || !buffer) return nullptr;
    DmabufDescription dmaBuf;
    if (!describeDmabuf(buffer, &dmaBuf) || dmaBuf.planeCount != 1) return nullptr;
    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(buffer, &description);
    const RenderFormat* format = renderFormat(description.format);
    if (!format) return nullptr;
    uint64_t id = 0;
    AHardwareBuffer_getId(buffer, &id);
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) return nullptr;

    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!imageTarget) return nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    const int status = azk_graphics_import_egl_image(
            display, AHardwareBuffer_getNativeHandle(buffer), &image);
    if (status != 0) {
        ALOGE("EGL DMA-BUF import failed for AHardwareBuffer %" PRIu64 " (status %d, EGL 0x%x)",
              id, status, eglGetError());
        return nullptr;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    imageTarget(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(image));
    const GLenum glError = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    // As in pinned GrAHardwareBufferUtils::MakeGLBackendTexture, importing a
    // foreign EGL image changes GL bindings outside Ganesh. The next window
    // buffer can otherwise sample texture 0 while Ganesh believes the previous
    // TextureView image is still bound. Readback happens to disturb that cache
    // again, which can conceal the missing onscreen pixels.
    context->resetContext(kTextureBinding_GrGLBackendState);
    if (glError != GL_NO_ERROR) {
        glDeleteTextures(1, &texture);
        auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                eglGetProcAddress("eglDestroyImageKHR"));
        if (destroyImage) destroyImage(display, image);
        ALOGE("glEGLImageTargetTexture2DOES failed for AHardwareBuffer %" PRIu64 " (0x%x)",
              id, glError);
        return nullptr;
    }

    auto* imported = new (std::nothrow) ImportedTexture;
    if (!imported) {
        glDeleteTextures(1, &texture);
        auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                eglGetProcAddress("eglDestroyImageKHR"));
        if (destroyImage) destroyImage(display, image);
        return nullptr;
    }
    AHardwareBuffer_acquire(buffer);
    imported->display = display;
    imported->image = image;
    imported->texture = texture;
    imported->buffer = buffer;

    GrGLTextureInfo textureInfo{GL_TEXTURE_2D, texture, format->glFormat};
    const auto backend = GrBackendTextures::MakeGL(
            description.width, description.height, skgpu::Mipmapped::kNo, textureInfo,
            "ArtzigAHardwareBuffer");
    return SkSurfaces::WrapBackendTexture(context, backend, kTopLeft_GrSurfaceOrigin, 0,
                                          format->colorType, std::move(colorSpace), nullptr,
                                          releaseImportedTexture, imported);
}

sk_sp<SkImage> makeHardwareBufferImage(GrDirectContext* context,
                                       AHardwareBuffer* buffer,
                                       sk_sp<SkColorSpace> colorSpace) {
    sk_sp<SkSurface> surface = makeHardwareBufferSurface(context, buffer, std::move(colorSpace));
    return surface ? surface->makeImageSnapshot() : nullptr;
}

}  // namespace artzig

// NDK Java conversions use the stock JNI wrapper and its GraphicBuffer owner.
extern "C" AHardwareBuffer* AHardwareBuffer_fromHardwareBuffer(JNIEnv* env, jobject object) {
    return android::android_hardware_HardwareBuffer_getNativeHardwareBuffer(env, object);
}

extern "C" jobject AHardwareBuffer_toHardwareBuffer(JNIEnv* env, AHardwareBuffer* buffer) {
    return android::android_hardware_HardwareBuffer_createFromAHardwareBuffer(env, buffer);
}
