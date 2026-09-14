#pragma once

#include <stdint.h>
#include <azk/graphics_buffer_info.h>

#include <libavcodec/avcodec.h>
#include <libavutil/buffer.h>

#define AZK_DMABUF_MAX_PLANES 4

typedef struct AzkVaapiVpp AzkVaapiVpp;

typedef struct AzkHardwareFrame {
    AVFrame *frame;
    AVBufferRef *device_ref;
    int width;
    int height;
    uint32_t format;
    int plane_count;
    int fds[AZK_DMABUF_MAX_PLANES];
    uint32_t offsets[AZK_DMABUF_MAX_PLANES];
    uint32_t strides[AZK_DMABUF_MAX_PLANES];
    uint64_t modifiers[AZK_DMABUF_MAX_PLANES];
    int object_count;
    int object_fds[AZK_DMABUF_MAX_PLANES];
    void *va_display;
    uint32_t va_surface;
    int owns_va_surface;
    int32_t dataspace;
} AzkHardwareFrame;

/* Returns 1 when VA-API is ready, 0 when hardware video is unavailable. */
int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref);
/* Supported 8-bit decode profiles. AVC: baseline/main/high bits 0/1/2;
 * VP9: profile 0 bit 0; MPEG-4: simple/advanced-simple bits 0/1. */
uint32_t azk_vaapi_decoder_profiles(enum AVCodecID codec);

/* Convert into an owned RGB DMA-BUF through cached VA VPP. */
AzkHardwareFrame *azk_vaapi_export(const AVFrame *frame, int target_width,
                                   int target_height, AzkVaapiVpp **vpp);
/* Validate supported color/crop and describe the visible RGB output. */
int azk_vaapi_frame_info(const AVFrame *, int *width, int *height, int *dataspace);
/* Convert directly into borrowed allocator-owned storage. The caller waits
 * its acquire fence first and retains the buffer through this synchronous call.
 * No destination allocation, staging copy, or descriptor ownership transfer. */
int azk_vaapi_render_into(const AVFrame *, const AzkGraphicsBufferInfo *, AzkVaapiVpp **);
void azk_vaapi_vpp_free(AzkVaapiVpp **vpp);
void azk_hardware_frame_release(AzkHardwareFrame *frame);
