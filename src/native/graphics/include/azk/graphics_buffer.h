#pragma once
#include <stdint.h>
#include <cutils/native_handle.h>
#ifdef __cplusplus
extern "C" {
#endif
// Host native-handle schema v1. Borrowed FDs in descriptions are valid while
// the registered handle remains alive. All import/export operations own copies.
typedef struct AzkGraphicsBufferInfo {
  uint32_t width, height, format, layers;
  uint64_t usage;
  uint32_t drm_format, stride, offset, reserved;
  uint64_t modifier, id;
  int32_t fd, dataspace;
} AzkGraphicsBufferInfo;
int32_t azk_graphics_allocate(uint32_t width, uint32_t height, uint32_t format,
                              uint32_t layers, uint64_t usage,
                              native_handle_t **output);
int32_t azk_graphics_import(const native_handle_t *raw,
                            native_handle_t **output);
int32_t azk_graphics_free(const native_handle_t *handle);
int32_t azk_graphics_describe(const native_handle_t *, AzkGraphicsBufferInfo *);
int32_t azk_graphics_supported(uint32_t, uint32_t, uint32_t, uint32_t,
                               uint64_t);
int32_t azk_graphics_lock(const native_handle_t *, uint64_t usage, int32_t left,
                          int32_t top, int32_t right, int32_t bottom,
                          void **address, int32_t *pixel_stride,
                          int32_t *row_stride);
int32_t azk_graphics_unlock(const native_handle_t *);
int32_t azk_graphics_dataspace(const native_handle_t *, int32_t);
uint64_t azk_graphics_live_buffers(void);
void *azk_graphics_gbm_device(void);
#ifdef __cplusplus
}
#endif
