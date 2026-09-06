#pragma once
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <cutils/native_handle.h>
#ifdef __cplusplus
extern "C" {
#endif
// The graphics device's display; each caller balances EGL initialization and
// termination. No Wayland EGL native window or implicit default device.
EGLDisplay azk_graphics_egl_display(void);
// All hosted context owners and stock image/probe users use these balanced
// references. Mesa's eglInitialize/eglTerminate pair is not reference-counted.
EGLBoolean azk_graphics_egl_initialize(EGLDisplay, EGLint *major,
                                       EGLint *minor);
EGLBoolean azk_graphics_egl_terminate(EGLDisplay);
// Import registered GBM storage, preserving its explicit layout. The caller
// retains the GraphicBuffer while importing and owns the returned EGLImage.
// Fences are deliberately separate: callers must order access through EGL or
// Fence before using the image. Errors use Android status_t values.
int azk_graphics_import_egl_image(EGLDisplay display,
                                  const native_handle_t *handle,
                                  EGLImageKHR *output);
#ifdef __cplusplus
}
#endif
