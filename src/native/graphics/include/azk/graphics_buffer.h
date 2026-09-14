#pragma once
#include <azk/graphics_buffer_info.h>
#include <cutils/native_handle.h>
#ifdef __cplusplus
extern "C" {
#endif
// Host native-handle schema v2 (v1 imports remain supported). Borrowed FDs in descriptions are valid while
// the registered handle remains alive. All import/export operations own copies.

int32_t azk_graphics_allocate(uint32_t width, uint32_t height, uint32_t format,
                              uint32_t layers, uint64_t usage,
                              native_handle_t **output);
int32_t azk_graphics_import(const native_handle_t *raw,
                            native_handle_t **output);
// Import a borrowed foreign RGB DMA-BUF. Assigns a fresh host buffer ID;
// the returned registered handle owns its descriptor. No input FD is consumed.
int32_t azk_graphics_import_external(const AzkGraphicsBufferInfo *,
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
// Sum of retained handle charges; aliased imports are charged independently.
uint64_t azk_graphics_live_bytes(void);
void *azk_graphics_gbm_device(void);
#ifdef __cplusplus
}
#endif
