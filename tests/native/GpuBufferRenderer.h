#pragma once
#include <azk/graphics_buffer.h>
#include <EGL/egl.h>
#include <azk/graphics_egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <ui/GraphicBuffer.h>
#include <ui/Fence.h>
#include <cassert>

// Native graphics regression helper. Render directly into the host allocation
// and return a real sync_file, without a CPU write or glFinish shortcut.
class GpuBufferRenderer {
  EGLDisplay display = EGL_NO_DISPLAY;
  EGLContext context = EGL_NO_CONTEXT;
  PFNEGLCREATEIMAGEKHRPROC createImage;
  PFNEGLDESTROYIMAGEKHRPROC destroyImage;
  PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTarget;
  PFNEGLCREATESYNCKHRPROC createSync;
  PFNEGLDESTROYSYNCKHRPROC destroySync;
  PFNEGLDUPNATIVEFENCEFDANDROIDPROC duplicateFence;

public:
  GpuBufferRenderer() {
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
        eglGetProcAddress("eglGetPlatformDisplayEXT"));
    assert(getDisplay);
    auto *device = azk_graphics_gbm_device();
    assert(device);
    display = getDisplay(EGL_PLATFORM_GBM_KHR, device, nullptr);
    assert(display != EGL_NO_DISPLAY &&
           azk_graphics_egl_initialize(display, nullptr, nullptr));
    assert(eglBindAPI(EGL_OPENGL_ES_API));
    const EGLint attrs[] = {EGL_SURFACE_TYPE,
                            0,
                            EGL_RENDERABLE_TYPE,
                            EGL_OPENGL_ES3_BIT,
                            EGL_RED_SIZE,
                            8,
                            EGL_GREEN_SIZE,
                            8,
                            EGL_BLUE_SIZE,
                            8,
                            EGL_NONE};
    EGLConfig config;
    EGLint count;
    assert(eglChooseConfig(display, attrs, &config, 1, &count) && count == 1);
    const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx);
    assert(context != EGL_NO_CONTEXT);
    assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context));
    createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
        eglGetProcAddress("eglCreateImageKHR"));
    destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
        eglGetProcAddress("eglDestroyImageKHR"));
    imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    createSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR"));
    destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR"));
    duplicateFence = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
        eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    assert(createImage && destroyImage && imageTarget && createSync &&
           destroySync && duplicateFence);
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    assert(extensions &&
           std::strstr(extensions, "EGL_ANDROID_native_fence_sync"));
  }
  ~GpuBufferRenderer() {
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    azk_graphics_egl_terminate(display);
  }
  android::sp<android::Fence>
  render(const android::sp<android::GraphicBuffer> &buffer, uint32_t rgba) {
    AzkGraphicsBufferInfo info{};
    assert(azk_graphics_describe(buffer->handle, &info) == 0);
    const EGLint attrs[] = {EGL_WIDTH,
                            EGLint(info.width),
                            EGL_HEIGHT,
                            EGLint(info.height),
                            EGL_LINUX_DRM_FOURCC_EXT,
                            EGLint(info.drm_format),
                            EGL_DMA_BUF_PLANE0_FD_EXT,
                            info.fd,
                            EGL_DMA_BUF_PLANE0_OFFSET_EXT,
                            EGLint(info.offset),
                            EGL_DMA_BUF_PLANE0_PITCH_EXT,
                            EGLint(info.stride),
                            EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,
                            EGLint(info.modifier),
                            EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,
                            EGLint(info.modifier >> 32),
                            EGL_NONE};
    EGLImage image = createImage(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT,
                                 nullptr, attrs);
    assert(image != EGL_NO_IMAGE);
    GLuint texture, fbo;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    imageTarget(GL_TEXTURE_2D, image);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           texture, 0);
    assert(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    glViewport(0, 0, info.width, info.height);
    glClearColor((rgba & 255) / 255.f, ((rgba >> 8) & 255) / 255.f,
                 ((rgba >> 16) & 255) / 255.f, ((rgba >> 24) & 255) / 255.f);
    glClear(GL_COLOR_BUFFER_BIT);
    assert(glGetError() == GL_NO_ERROR);
    EGLSyncKHR sync =
        createSync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
    assert(sync != EGL_NO_SYNC_KHR);
    glFlush();
    const int fd = duplicateFence(display, sync);
    assert(fd >= 0);
    destroySync(display, sync);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    destroyImage(display, image);
    return android::sp<android::Fence>::make(fd);
  }
};
