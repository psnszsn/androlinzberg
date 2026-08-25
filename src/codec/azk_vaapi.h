#pragma once

#include <stdint.h>

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
} AzkHardwareFrame;

/* Returns 1 when VA-API was selected, 0 when software fallback was selected. */
int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref);

/* Export directly when safe, otherwise scale/convert through cached VA VPP. */
AzkHardwareFrame *azk_vaapi_export(const AVFrame *frame, int target_width,
                                   int target_height, AzkVaapiVpp **vpp);
void azk_vaapi_vpp_free(AzkVaapiVpp **vpp);
void azk_hardware_frame_release(AzkHardwareFrame *frame);
