#pragma once

#include <stdint.h>

#include <libavcodec/avcodec.h>
#include <libavutil/buffer.h>

#define AZK_DMABUF_MAX_PLANES 4

typedef struct AzkHardwareFrame {
    AVFrame *frame;
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
} AzkHardwareFrame;

/* Returns 1 when VA-API was selected, 0 when software fallback was selected. */
int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref);

/* Export one VA-API frame as a composed DRM DMA-BUF. */
AzkHardwareFrame *azk_vaapi_export(const AVFrame *frame);
void azk_hardware_frame_release(AzkHardwareFrame *frame);

