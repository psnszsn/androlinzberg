// HWUI/Skia side of the stock AHardwareBuffer/GraphicBuffer seam. GBM storage
// lives in graphics_buffer.zig; opaque Java handles remain in platform_abi.zig.
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
