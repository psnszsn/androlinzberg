#include "HostHardwareBuffer.h"

#define LOG_TAG "HostHardwareBuffer"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <android/hardware_buffer_jni.h>
#include <drm/drm_fourcc.h>
#include <gbm.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <graphics_jni_helpers.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iterator>
#include <mutex>
#include <new>
#include <poll.h>
#include <string>
#include <unistd.h>

#include <include/core/SkColorSpace.h>
#include <include/core/SkColorType.h>
#include <include/core/SkImage.h>
#include <include/core/SkSurface.h>
#include <include/gpu/GpuTypes.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <include/gpu/ganesh/GrBackendSurface.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include <include/gpu/ganesh/gl/GrGLBackendSurface.h>

namespace {

struct FormatInfo {
    uint32_t ahbFormat;
    uint32_t drmFormat;
    uint32_t bytesPerPixel;
    GrGLenum glFormat;
    SkColorType colorType;
    SkAlphaType alphaType;
};

constexpr FormatInfo kFormats[] = {
        {AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, DRM_FORMAT_ABGR8888, 4, GL_RGBA8,
         kRGBA_8888_SkColorType, kPremul_SkAlphaType},
        {AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM, DRM_FORMAT_XBGR8888, 4, GL_RGBA8,
         kRGBA_8888_SkColorType, kOpaque_SkAlphaType},
        {AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM, DRM_FORMAT_RGB565, 2, GL_RGB565,
         kRGB_565_SkColorType, kOpaque_SkAlphaType},
        {AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, DRM_FORMAT_ABGR16161616F, 8, GL_RGBA16F,
         kRGBA_F16_SkColorType, kPremul_SkAlphaType},
        {AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, DRM_FORMAT_ABGR2101010, 4, GL_RGB10_A2,
         kRGBA_1010102_SkColorType, kPremul_SkAlphaType},
};

const FormatInfo* formatInfo(uint32_t format) {
    for (const auto& candidate : kFormats) {
        if (candidate.ahbFormat == format) return &candidate;
    }
    return nullptr;
}

uint32_t gbmUsage(uint64_t usage) {
    uint32_t flags = 0;
    if (usage & (AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT)) {
        flags |= GBM_BO_USE_RENDERING;
    }
    if (usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) flags |= GBM_BO_USE_SCANOUT;
    if (usage & (AHARDWAREBUFFER_USAGE_CPU_READ_MASK |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK)) {
        flags |= GBM_BO_USE_LINEAR;
    }
    return flags;
}

struct GbmState {
    std::once_flag once;
    int fd = -1;
    gbm_device* device = nullptr;
};

GbmState gGbm;
std::atomic<uint64_t> gNextId{1};

void initializeGbm() {
    const char* configured = std::getenv("ARTZIG_DRM_DEVICE");
    if (!configured || configured[0] == '\0') configured = std::getenv("ARTZIG_VAAPI_DEVICE");
    if (!configured || configured[0] == '\0') configured = "/dev/dri/renderD128";

    gGbm.fd = open(configured, O_RDWR | O_CLOEXEC);
    if (gGbm.fd < 0) {
        ALOGE("unable to open GBM render node %s: %s", configured, strerror(errno));
        return;
    }
    gGbm.device = gbm_create_device(gGbm.fd);
    if (!gGbm.device) {
        ALOGE("gbm_create_device(%s) failed", configured);
        close(gGbm.fd);
        gGbm.fd = -1;
        return;
    }
    ALOGI("GBM hardware buffers use %s (%s)", configured,
          gbm_device_get_backend_name(gGbm.device));
}

gbm_device* getGbmDevice() {
    std::call_once(gGbm.once, initializeGbm);
    return gGbm.device;
}

bool validDescription(const AHardwareBuffer_Desc& desc) {
    if (!desc.width || !desc.height || desc.layers != 1 || !formatInfo(desc.format)) return false;
    if (desc.rfu0 != 0 || desc.rfu1 != 0) return false;
    if (desc.usage & (AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT |
                      AHARDWAREBUFFER_USAGE_GPU_CUBE_MAP |
                      AHARDWAREBUFFER_USAGE_GPU_MIPMAP_COMPLETE |
                      AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER)) {
        return false;
    }
    return true;
}

bool waitFence(int fd) {
    if (fd < 0) return true;
    pollfd item{fd, POLLIN, 0};
    int result;
    do {
        result = poll(&item, 1, -1);
    } while (result < 0 && errno == EINTR);
    close(fd);
    return result > 0;
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

struct HardwareBufferClassInfo {
    jclass clazz = nullptr;
    jfieldID nativeObject = nullptr;
    jmethodID constructor = nullptr;
} gHardwareBufferClass;

AHardwareBuffer* fromNativeObject(jlong value) {
    return reinterpret_cast<AHardwareBuffer*>(static_cast<uintptr_t>(value));
}

jlong nativeCreate(JNIEnv*, jclass, jint width, jint height, jint format, jint layers,
                   jlong usage) {
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = static_cast<uint32_t>(layers);
    desc.format = static_cast<uint32_t>(format);
    desc.usage = static_cast<uint64_t>(usage);
    AHardwareBuffer* buffer = nullptr;
    return AHardwareBuffer_allocate(&desc, &buffer) == 0
            ? static_cast<jlong>(reinterpret_cast<uintptr_t>(buffer))
            : 0;
}

jboolean nativeIsSupported(JNIEnv*, jclass, jint width, jint height, jint format, jint layers,
                           jlong usage) {
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = static_cast<uint32_t>(layers);
    desc.format = static_cast<uint32_t>(format);
    desc.usage = static_cast<uint64_t>(usage);
    return AHardwareBuffer_isSupported(&desc) != 0;
}

void nativeRelease(JNIEnv*, jclass, jlong value) {
    if (auto* buffer = fromNativeObject(value)) AHardwareBuffer_release(buffer);
}

jint nativeWidth(JNIEnv*, jclass, jlong value);
jint nativeHeight(JNIEnv*, jclass, jlong value);
jint nativeFormat(JNIEnv*, jclass, jlong value);
jint nativeLayers(JNIEnv*, jclass, jlong value);
jlong nativeUsage(JNIEnv*, jclass, jlong value);
jlong nativeId(JNIEnv*, jclass, jlong value);

}  // namespace

struct AHardwareBuffer {
    std::atomic<uint32_t> references{1};
    AHardwareBuffer_Desc description{};
    gbm_bo* bo = nullptr;
    int dmaBufFd = -1;
    uint32_t drmFormat = 0;
    uint32_t byteStride = 0;
    uint64_t modifier = DRM_FORMAT_MOD_INVALID;
    uint64_t id = 0;
    std::mutex mapMutex;
    void* mapData = nullptr;
    void* mapToken = nullptr;
};

extern "C" int AHardwareBuffer_allocate(const AHardwareBuffer_Desc* desc,
                                         AHardwareBuffer** outBuffer) {
    if (!desc || !outBuffer) return -EINVAL;
    *outBuffer = nullptr;
    if (!validDescription(*desc)) return -EINVAL;
    gbm_device* device = getGbmDevice();
    if (!device) return -ENODEV;
    const FormatInfo* info = formatInfo(desc->format);
    const uint32_t flags = gbmUsage(desc->usage);
    if (!gbm_device_is_format_supported(device, info->drmFormat, flags)) return -EINVAL;

    gbm_bo* bo = gbm_bo_create(device, desc->width, desc->height, info->drmFormat, flags);
    if (!bo) return -ENOMEM;
    const int fd = gbm_bo_get_fd(bo);
    if (fd < 0) {
        gbm_bo_destroy(bo);
        return -errno;
    }

    auto* buffer = new (std::nothrow) AHardwareBuffer;
    if (!buffer) {
        close(fd);
        gbm_bo_destroy(bo);
        return -ENOMEM;
    }
    buffer->description = *desc;
    buffer->description.stride = gbm_bo_get_stride(bo) / info->bytesPerPixel;
    buffer->description.rfu0 = 0;
    buffer->description.rfu1 = 0;
    buffer->bo = bo;
    buffer->dmaBufFd = fd;
    buffer->drmFormat = info->drmFormat;
    buffer->byteStride = gbm_bo_get_stride(bo);
    buffer->modifier = gbm_bo_get_modifier(bo);
    buffer->id = gNextId.fetch_add(1, std::memory_order_relaxed);
    *outBuffer = buffer;
    return 0;
}

extern "C" void AHardwareBuffer_acquire(AHardwareBuffer* buffer) {
    if (buffer) buffer->references.fetch_add(1, std::memory_order_relaxed);
}

extern "C" void AHardwareBuffer_release(AHardwareBuffer* buffer) {
    if (!buffer || buffer->references.fetch_sub(1, std::memory_order_acq_rel) != 1) return;
    if (buffer->mapToken && buffer->bo) gbm_bo_unmap(buffer->bo, buffer->mapToken);
    if (buffer->dmaBufFd >= 0) close(buffer->dmaBufFd);
    if (buffer->bo) gbm_bo_destroy(buffer->bo);
    delete buffer;
}

extern "C" void AHardwareBuffer_describe(const AHardwareBuffer* buffer,
                                          AHardwareBuffer_Desc* outDesc) {
    if (outDesc) *outDesc = buffer ? buffer->description : AHardwareBuffer_Desc{};
}

extern "C" int AHardwareBuffer_isSupported(const AHardwareBuffer_Desc* desc) {
    if (!desc || !validDescription(*desc)) return 0;
    gbm_device* device = getGbmDevice();
    const FormatInfo* info = formatInfo(desc->format);
    return device && gbm_device_is_format_supported(device, info->drmFormat,
                                                     gbmUsage(desc->usage));
}

extern "C" int AHardwareBuffer_lock(AHardwareBuffer* buffer, uint64_t usage, int32_t fence,
                                     const ARect* rect, void** outAddress) {
    if (!buffer || !outAddress || buffer->description.layers != 1) return -EINVAL;
    constexpr uint64_t cpuMask = AHARDWAREBUFFER_USAGE_CPU_READ_MASK |
                                 AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK;
    if (!(usage & cpuMask) || (usage & ~cpuMask) ||
        (usage & buffer->description.usage) != usage) {
        return -EINVAL;
    }
    if (!waitFence(fence)) return -EIO;
    std::lock_guard lock(buffer->mapMutex);
    if (buffer->mapToken) return -EBUSY;

    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = buffer->description.width;
    uint32_t height = buffer->description.height;
    if (rect) {
        if (rect->left < 0 || rect->top < 0 || rect->right <= rect->left ||
            rect->bottom <= rect->top ||
            static_cast<uint32_t>(rect->right) > width ||
            static_cast<uint32_t>(rect->bottom) > height) {
            return -EINVAL;
        }
        x = rect->left;
        y = rect->top;
        width = rect->right - rect->left;
        height = rect->bottom - rect->top;
    }
    uint32_t transfer = 0;
    if (usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK) transfer |= GBM_BO_TRANSFER_READ;
    if (usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK) transfer |= GBM_BO_TRANSFER_WRITE;
    uint32_t stride = 0;
    buffer->mapData = gbm_bo_map(buffer->bo, x, y, width, height, transfer, &stride,
                                 &buffer->mapToken);
    if (!buffer->mapData) {
        buffer->mapToken = nullptr;
        return -ENOMEM;
    }
    *outAddress = buffer->mapData;
    return 0;
}

extern "C" int AHardwareBuffer_unlock(AHardwareBuffer* buffer, int32_t* fence) {
    if (!buffer) return -EINVAL;
    std::lock_guard lock(buffer->mapMutex);
    if (!buffer->mapToken) return -EINVAL;
    gbm_bo_unmap(buffer->bo, buffer->mapToken);
    buffer->mapData = nullptr;
    buffer->mapToken = nullptr;
    if (fence) *fence = -1;
    return 0;
}

extern "C" int AHardwareBuffer_lockPlanes(AHardwareBuffer* buffer, uint64_t usage,
                                           int32_t fence, const ARect* rect,
                                           AHardwareBuffer_Planes* outPlanes) {
    if (!outPlanes) return -EINVAL;
    std::memset(outPlanes, 0, sizeof(*outPlanes));
    void* address = nullptr;
    const int result = AHardwareBuffer_lock(buffer, usage, fence, rect, &address);
    if (result != 0) return result;
    const FormatInfo* info = formatInfo(buffer->description.format);
    outPlanes->planeCount = 1;
    outPlanes->planes[0].data = address;
    outPlanes->planes[0].pixelStride = info->bytesPerPixel;
    outPlanes->planes[0].rowStride = buffer->byteStride;
    return 0;
}

extern "C" int AHardwareBuffer_lockAndGetInfo(AHardwareBuffer* buffer, uint64_t usage,
                                               int32_t fence, const ARect* rect,
                                               void** outAddress, int32_t* outBytesPerPixel,
                                               int32_t* outBytesPerStride) {
    const int result = AHardwareBuffer_lock(buffer, usage, fence, rect, outAddress);
    if (result != 0) return result;
    const FormatInfo* info = formatInfo(buffer->description.format);
    if (outBytesPerPixel) *outBytesPerPixel = info->bytesPerPixel;
    if (outBytesPerStride) *outBytesPerStride = buffer->byteStride;
    return 0;
}

extern "C" int AHardwareBuffer_getId(const AHardwareBuffer* buffer, uint64_t* outId) {
    if (!buffer || !outId) return -EINVAL;
    *outId = buffer->id;
    return 0;
}

extern "C" int AHardwareBuffer_sendHandleToUnixSocket(const AHardwareBuffer*, int) {
    return -ENOSYS;
}

extern "C" int AHardwareBuffer_recvHandleFromUnixSocket(int, AHardwareBuffer**) {
    return -ENOSYS;
}

namespace artzig {

bool describeDmabuf(const AHardwareBuffer* buffer, DmabufDescription* outDescription) {
    if (!buffer || !outDescription || buffer->dmaBufFd < 0) return false;
    *outDescription = {};
    outDescription->width = buffer->description.width;
    outDescription->height = buffer->description.height;
    outDescription->drmFormat = buffer->drmFormat;
    outDescription->planeCount = 1;
    outDescription->planes[0] = {
            .fd = buffer->dmaBufFd,
            .offset = gbm_bo_get_offset(buffer->bo, 0),
            .stride = buffer->byteStride,
            .modifier = buffer->modifier,
    };
    return true;
}

gbm_device* hardwareBufferGbmDevice() {
    return getGbmDevice();
}

sk_sp<SkSurface> makeHardwareBufferSurface(GrDirectContext* context,
                                           AHardwareBuffer* buffer,
                                           sk_sp<SkColorSpace> colorSpace) {
    if (!context || !buffer) return nullptr;
    DmabufDescription dmaBuf;
    if (!describeDmabuf(buffer, &dmaBuf) || dmaBuf.planeCount != 1) return nullptr;
    const FormatInfo* info = formatInfo(buffer->description.format);
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
        ALOGE("EGL DMA-BUF import failed for AHardwareBuffer %" PRIu64 " (0x%x)",
              buffer->id, eglGetError());
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
              buffer->id, glError);
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

    GrGLTextureInfo textureInfo{GL_TEXTURE_2D, texture, info->glFormat};
    const auto backend = GrBackendTextures::MakeGL(
            buffer->description.width, buffer->description.height, skgpu::Mipmapped::kNo,
            textureInfo, "ArtzigAHardwareBuffer");
    return SkSurfaces::WrapBackendTexture(context, backend, kTopLeft_GrSurfaceOrigin, 0,
                                          info->colorType, std::move(colorSpace), nullptr,
                                          releaseImportedTexture, imported);
}

sk_sp<SkImage> makeHardwareBufferImage(GrDirectContext* context,
                                       AHardwareBuffer* buffer,
                                       sk_sp<SkColorSpace> colorSpace) {
    sk_sp<SkSurface> surface = makeHardwareBufferSurface(context, buffer, std::move(colorSpace));
    return surface ? surface->makeImageSnapshot() : nullptr;
}

}  // namespace artzig

namespace {

jint nativeWidth(JNIEnv*, jclass, jlong value) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(fromNativeObject(value), &desc);
    return desc.width;
}

jint nativeHeight(JNIEnv*, jclass, jlong value) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(fromNativeObject(value), &desc);
    return desc.height;
}

jint nativeFormat(JNIEnv*, jclass, jlong value) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(fromNativeObject(value), &desc);
    return desc.format;
}

jint nativeLayers(JNIEnv*, jclass, jlong value) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(fromNativeObject(value), &desc);
    return desc.layers;
}

jlong nativeUsage(JNIEnv*, jclass, jlong value) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(fromNativeObject(value), &desc);
    return static_cast<jlong>(desc.usage);
}

jlong nativeId(JNIEnv*, jclass, jlong value) {
    uint64_t id = 0;
    AHardwareBuffer_getId(fromNativeObject(value), &id);
    return static_cast<jlong>(id);
}

const JNINativeMethod kHardwareBufferMethods[] = {
        {"nCreateHardwareBuffer", "(IIIIJ)J", reinterpret_cast<void*>(nativeCreate)},
        {"nIsSupported", "(IIIIJ)Z", reinterpret_cast<void*>(nativeIsSupported)},
        {"nRelease", "(J)V", reinterpret_cast<void*>(nativeRelease)},
        {"nGetWidth", "(J)I", reinterpret_cast<void*>(nativeWidth)},
        {"nGetHeight", "(J)I", reinterpret_cast<void*>(nativeHeight)},
        {"nGetFormat", "(J)I", reinterpret_cast<void*>(nativeFormat)},
        {"nGetLayers", "(J)I", reinterpret_cast<void*>(nativeLayers)},
        {"nGetUsage", "(J)J", reinterpret_cast<void*>(nativeUsage)},
        {"nGetId", "(J)J", reinterpret_cast<void*>(nativeId)},
};

}  // namespace

namespace android {

int register_android_hardware_HardwareBuffer(JNIEnv* env) {
    const int result = android::RegisterMethodsOrDie(env, "android/hardware/HardwareBuffer",
                                                     kHardwareBufferMethods,
                                                     std::size(kHardwareBufferMethods));
    jclass local = android::FindClassOrDie(env, "android/hardware/HardwareBuffer");
    gHardwareBufferClass.clazz = android::MakeGlobalRefOrDie(env, local);
    gHardwareBufferClass.nativeObject =
            android::GetFieldIDOrDie(env, gHardwareBufferClass.clazz, "mNativeObject", "J");
    gHardwareBufferClass.constructor =
            android::GetMethodIDOrDie(env, gHardwareBufferClass.clazz, "<init>", "(J)V");
    return result;
}

AHardwareBuffer* android_hardware_HardwareBuffer_getNativeHardwareBuffer(
        JNIEnv* env, jobject object) {
    if (!object || !env->IsInstanceOf(object, gHardwareBufferClass.clazz)) return nullptr;
    return fromNativeObject(env->GetLongField(object, gHardwareBufferClass.nativeObject));
}

jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* buffer) {
    if (!buffer) return nullptr;
    AHardwareBuffer_acquire(buffer);
    jobject result = env->NewObject(gHardwareBufferClass.clazz,
                                    gHardwareBufferClass.constructor,
                                    static_cast<jlong>(reinterpret_cast<uintptr_t>(buffer)));
    if (!result) AHardwareBuffer_release(buffer);
    return result;
}

}  // namespace android

extern "C" AHardwareBuffer* AHardwareBuffer_fromHardwareBuffer(JNIEnv* env, jobject object) {
    return android::android_hardware_HardwareBuffer_getNativeHardwareBuffer(env, object);
}

extern "C" jobject AHardwareBuffer_toHardwareBuffer(JNIEnv* env, AHardwareBuffer* buffer) {
    return android::android_hardware_HardwareBuffer_createFromAHardwareBuffer(env, buffer);
}
