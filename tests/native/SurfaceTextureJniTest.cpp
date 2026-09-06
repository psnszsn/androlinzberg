#include <jni.h>
#include <cstring>
#include "GpuBufferRenderer.h"
#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>
#include <android/native_window_aidl.h>
#include <android/native_window_jni.h>
#include <android_runtime/android_view_Surface.h>
#include <gui/Surface.h>
#include <binder/IPCThreadState.h>
#include <cstdint>
#include <cstring>

namespace {
// An offscreen EGL context and a shader sampling the exact external texture
// updated by Java SurfaceTexture. Readback is only a regression observation.
struct TextureReader {
  GpuBufferRenderer context;
  EGLDisplay display = eglGetCurrentDisplay();
  EGLContext eglContext = eglGetCurrentContext();
  GLuint texture = 0, output = 0, fbo = 0, program = 0, vao = 0;
  TextureReader() {
    glGenTextures(1, &texture);
    glGenTextures(1, &output);
    glBindTexture(GL_TEXTURE_2D, output);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                 nullptr);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           output, 0);
    assert(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    const char *vertex =
        "#version 300 es\n"
        "void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
        "gl_Position = vec4(p * 2.0 - 1.0, 0, 1); }";
    const char *fragment =
        "#version 300 es\n"
        "#extension GL_OES_EGL_image_external_essl3 : require\n"
        "precision highp float; uniform samplerExternalOES source;"
        "out vec4 color; void main() { color = texture(source, vec2(0.5)); }";
    auto shader = [](GLenum type, const char *source) {
      GLuint s = glCreateShader(type);
      glShaderSource(s, 1, &source, nullptr);
      glCompileShader(s);
      GLint ok;
      glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
      assert(ok);
      return s;
    };
    GLuint vs = shader(GL_VERTEX_SHADER, vertex),
           fs = shader(GL_FRAGMENT_SHADER, fragment);
    program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint ok;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    assert(ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    glGenVertexArrays(1, &vao);
  }
  ~TextureReader() {
    activate();
    glDeleteVertexArrays(1, &vao);
    glDeleteProgram(program);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &output);
    glDeleteTextures(1, &texture);
  }
  void activate() {
    assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, eglContext));
  }
  jint pixel() {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, 1, 1);
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    glUniform1i(glGetUniformLocation(program, "source"), 0);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    uint8_t rgba[4]{};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    assert(glGetError() == GL_NO_ERROR);
    return (uint32_t(rgba[3]) << 24) | (uint32_t(rgba[0]) << 16) |
           (uint32_t(rgba[1]) << 8) | rgba[2];
  }
};
} // namespace
extern "C" uint64_t azk_graphics_live_buffers();
extern "C" JNIEXPORT jlong JNICALL
Java_dev_artzig_SurfaceBufferSmoke_liveBuffers(JNIEnv *, jclass) {
  return azk_graphics_live_buffers();
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_artzig_SurfaceBufferSmoke_nativeBinderUid(JNIEnv *, jclass) {
  return android::IPCThreadState::self()->getCallingUid();
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_artzig_SurfaceBufferSmoke_createReader(JNIEnv *, jclass) {
  return reinterpret_cast<jlong>(new TextureReader);
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_artzig_SurfaceBufferSmoke_textureName(JNIEnv *, jclass, jlong ptr) {
  reinterpret_cast<TextureReader *>(ptr)->activate();
  return reinterpret_cast<TextureReader *>(ptr)->texture;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_artzig_SurfaceBufferSmoke_activateReader(JNIEnv *, jclass, jlong ptr) {
  reinterpret_cast<TextureReader *>(ptr)->activate();
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_artzig_SurfaceBufferSmoke_pixel(JNIEnv *, jclass, jlong ptr) {
  return reinterpret_cast<TextureReader *>(ptr)->pixel();
}
extern "C" JNIEXPORT void JNICALL
Java_dev_artzig_SurfaceBufferSmoke_destroyReader(JNIEnv *, jclass, jlong ptr) {
  delete reinterpret_cast<TextureReader *>(ptr);
}
// Independent NDK wrapper lifetime must preserve the producer and texture.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_artzig_SurfaceBufferSmoke_ndkRoundTrip(JNIEnv *env, jclass,
                                                jobject object) {
  ASurfaceTexture *texture = ASurfaceTexture_fromSurfaceTexture(env, object);
  if (!texture)
    return false;
  ANativeWindow *window = ASurfaceTexture_acquireANativeWindow(texture);
  ASurfaceTexture_release(texture);
  const bool valid = window && ANativeWindow_getWidth(window) > 0;
  if (window)
    ANativeWindow_release(window);
  return valid;
}

extern "C" JNIEXPORT jobject JNICALL Java_dev_artzig_SurfaceBufferSmoke_ndkCopy(
    JNIEnv *env, jclass, jobject object) {
  ANativeWindow *source = ANativeWindow_fromSurface(env, object);
  assert(source);
  AParcel *parcel = AParcel_create();
  assert(ANativeWindow_writeToParcel(source, parcel) == STATUS_OK);
  ANativeWindow_release(source);
  assert(AParcel_setDataPosition(parcel, 0) == STATUS_OK);
  ANativeWindow *restored = nullptr;
  assert(ANativeWindow_readFromParcel(parcel, &restored) == STATUS_OK);
  AParcel_delete(parcel);
  auto surface = android::sp<android::Surface>::fromExisting(
      static_cast<android::Surface *>(restored));
  jobject result =
      android::android_view_Surface_createFromSurface(env, surface);
  ANativeWindow_release(restored);
  return result;
}

// Only the WMS lifetime endpoint is a fixture. Inputflinger and its policy
// bridge are the production HostInputDispatcher; this does not prove WMS boot.
#include <binder/IServiceManager.h>
#include <gui/SurfaceComposerClient.h>
extern "C" JNIEXPORT void JNICALL
Java_dev_artzig_HostInputDispatcherSmoke_startInputPublication(JNIEnv *,
                                                               jclass) {
  using namespace android;
  assert(defaultServiceManager()->addService(String16("window"),
                                             sp<BBinder>::make()) == OK);
  assert(SurfaceComposerClient::bootFinished() == OK);
}
