#pragma once

#include <stdint.h>
#include <azk/graphics_buffer_info.h>

#include <libavcodec/avcodec.h>
#include <libavutil/buffer.h>

typedef struct AzkVaapiVpp AzkVaapiVpp;

/* Returns 1 when VA-API is ready, 0 when hardware video is unavailable. */
int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref);
/* Supported 8-bit decode profiles. AVC: baseline/main/high bits 0/1/2;
 * VP9: profile 0 bit 0; MPEG-4: simple/advanced-simple bits 0/1. */
uint32_t azk_vaapi_decoder_profiles(enum AVCodecID codec);

/* Validate supported color/crop and describe the visible RGB output. */
int azk_vaapi_frame_info(const AVFrame *, int *width, int *height, int *dataspace);
/* Convert directly into borrowed allocator-owned storage. The caller waits
 * its acquire fence first and retains the buffer through this synchronous call.
 * No destination allocation, staging copy, or descriptor ownership transfer. */
int azk_vaapi_render_into(const AVFrame *, const AzkGraphicsBufferInfo *, AzkVaapiVpp **);
void azk_vaapi_vpp_free(AzkVaapiVpp **vpp);
