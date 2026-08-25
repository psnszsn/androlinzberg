// HWUI/Skia side of the AHardwareBuffer seam. Storage, lifetime, mapping, and
// JNI live in platform_abi.zig; this file only imports DMA-BUFs into EGL/Skia
// and supplies the two C++-namespace wrappers required by AOSP.
#include "HardwareBufferRender.h"

#define LOG_TAG "HardwareBufferRender"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <android/hardware_buffer_jni.h>
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

extern "C" bool azk_hardware_buffer_describe_dmabuf(
        const AHardwareBuffer*, artzig::DmabufDescription*);
extern "C" gbm_device* azk_hardware_buffer_gbm_device();
extern "C" AHardwareBuffer* azk_hardware_buffer_get_native(JNIEnv*, jobject);
extern "C" jobject azk_hardware_buffer_create_java(JNIEnv*, AHardwareBuffer*);

}  // namespace

namespace artzig {

bool describeDmabuf(const AHardwareBuffer* buffer, DmabufDescription* description) {
    return azk_hardware_buffer_describe_dmabuf(buffer, description);
}

gbm_device* hardwareBufferGbmDevice() {
    return azk_hardware_buffer_gbm_device();
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

    auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!createImage || !imageTarget) return nullptr;

    EGLint attributes[32];
    size_t count = 0;
    auto append = [&](EGLint key, EGLint value) {
        attributes[count++] = key;
        attributes[count++] = value;
    };
    append(EGL_WIDTH, static_cast<EGLint>(dmaBuf.width));
    append(EGL_HEIGHT, static_cast<EGLint>(dmaBuf.height));
    append(EGL_LINUX_DRM_FOURCC_EXT, static_cast<EGLint>(dmaBuf.drmFormat));
    append(EGL_DMA_BUF_PLANE0_FD_EXT, dmaBuf.planes[0].fd);
    append(EGL_DMA_BUF_PLANE0_OFFSET_EXT, static_cast<EGLint>(dmaBuf.planes[0].offset));
    append(EGL_DMA_BUF_PLANE0_PITCH_EXT, static_cast<EGLint>(dmaBuf.planes[0].stride));
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (dmaBuf.planes[0].modifier != DRM_FORMAT_MOD_INVALID && extensions &&
        std::strstr(extensions, "EGL_EXT_image_dma_buf_import_modifiers")) {
        append(EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,
               static_cast<EGLint>(dmaBuf.planes[0].modifier & 0xffffffffu));
        append(EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,
               static_cast<EGLint>(dmaBuf.planes[0].modifier >> 32));
    }
    attributes[count++] = EGL_NONE;

    EGLImageKHR image = createImage(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr,
                                    attributes);
    if (image == EGL_NO_IMAGE_KHR) {
        ALOGE("EGL DMA-BUF import failed for AHardwareBuffer %" PRIu64 " (0x%x)", id,
              eglGetError());
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

namespace android {

AHardwareBuffer* android_hardware_HardwareBuffer_getNativeHardwareBuffer(
        JNIEnv* env, jobject object) {
    return azk_hardware_buffer_get_native(env, object);
}

jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* buffer) {
    return azk_hardware_buffer_create_java(env, buffer);
}

}  // namespace android
