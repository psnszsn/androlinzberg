#include "azk_vaapi.h"
#include <azk/graphics_buffer_info.h>

#include <errno.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vaapi.h>
#include <va/va.h>
#include <va/va_drmcommon.h>
#include <va/va_vpp.h>

struct AzkVaapiVpp {
    VADisplay display;
    VAConfigID config;
    VAContextID context;
    int width;
    int height;
};

// Pinned ColorUtils::setDefaultCodecColorAspectsIfNeeded. This C VA-API
// adapter cannot call stagefright's codec stack; keep its size defaults here.
static int default_primaries(const AVFrame *source) {
    const int w = source->width, h = source->height;
    if (w >= 3840 || h >= 3840 || (int64_t)w * h >= 3840 * 1634)
        return AVCOL_PRI_BT2020;
    if ((w <= 720 && h > 480 && h <= 576) ||
        (h <= 720 && w > 480 && w <= 576)) return AVCOL_PRI_BT470BG;
    if ((w <= 720 && h <= 480) || (h <= 720 && w <= 480))
        return AVCOL_PRI_SMPTE170M;
    return AVCOL_PRI_BT709;
}

static enum AVPixelFormat choose_vaapi_format(AVCodecContext *context,
                                               const enum AVPixelFormat *formats) {
    (void)context;
    for (const enum AVPixelFormat *format = formats;
         *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == AV_PIX_FMT_VAAPI) return *format;
    }
    return formats[0];
}

// Query the gralloc boundary directly; no Java video-output bridge is involved.
static int graphics_available(void) {
    typedef int32_t (*SupportedFn)(uint32_t, uint32_t, uint32_t, uint32_t, uint64_t);
    SupportedFn supported = (SupportedFn)dlsym(RTLD_DEFAULT, "azk_graphics_supported");
    return supported && supported(16, 16, 2, 1, 0x100) == 1; // RGBX, HW_TEXTURE
}

uint32_t azk_vaapi_decoder_profiles(enum AVCodecID codec) {
    if (!graphics_available()) return 0;
    const char *path = getenv("ARTZIG_VAAPI_DEVICE");
    AVBufferRef *ref = NULL;
    if (av_hwdevice_ctx_create(&ref, AV_HWDEVICE_TYPE_VAAPI,
                               path && *path ? path : NULL, NULL, 0) < 0) return 0;
    AVHWDeviceContext *context = (AVHWDeviceContext *)ref->data;
    VADisplay display = ((AVVAAPIDeviceContext *)context->hwctx)->display;
    int capacity = vaMaxNumProfiles(display), count = 0;
    int entry_capacity = vaMaxNumEntrypoints(display);
    VAProfile *profiles = capacity > 0 ? calloc(capacity, sizeof(*profiles)) : NULL;
    VAEntrypoint *entries = entry_capacity > 0 ? calloc(entry_capacity, sizeof(*entries)) : NULL;
    uint32_t result = 0;
    if (profiles && entries && vaQueryConfigProfiles(display, profiles, &count) == VA_STATUS_SUCCESS) {
        for (int i = 0; i < count; ++i) {
            uint32_t bit = 0;
            if (codec == AV_CODEC_ID_H264) {
                if (profiles[i] == VAProfileH264ConstrainedBaseline) bit = 1;
                else if (profiles[i] == VAProfileH264Main) bit = 2;
                else if (profiles[i] == VAProfileH264High) bit = 4;
            } else if (codec == AV_CODEC_ID_VP9 && profiles[i] == VAProfileVP9Profile0) bit = 1;
            else if (codec == AV_CODEC_ID_MPEG4) {
                if (profiles[i] == VAProfileMPEG4Simple) bit = 1;
                else if (profiles[i] == VAProfileMPEG4AdvancedSimple) bit = 2;
            }
            int entry_count = 0;
            if (bit && vaQueryConfigEntrypoints(display, profiles[i], entries, &entry_count) == VA_STATUS_SUCCESS)
                for (int j = 0; j < entry_count; ++j)
                    if (entries[j] == VAEntrypointVLD) result |= bit;
        }
    }
    free(entries); free(profiles); av_buffer_unref(&ref);
    return result;
}

int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref) {
    if (!graphics_available()) {
        fprintf(stderr,
                "azk: hardware video unavailable: compositor has no compatible DMA-BUF path\n");
        return 0;
    }

    int supported = 0;
    for (int index = 0;; ++index) {
        const AVCodecHWConfig *config = avcodec_get_hw_config(context->codec, index);
        if (!config) break;
        if (config->device_type == AV_HWDEVICE_TYPE_VAAPI &&
            (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX)) {
            supported = 1;
            break;
        }
    }
    if (!supported) {
        fprintf(stderr, "azk: hardware video unavailable: codec has no VA-API path\n");
        return 0;
    }

    const char *device = getenv("ARTZIG_VAAPI_DEVICE");
    if (device && device[0] == '\0') device = NULL;
    int result = av_hwdevice_ctx_create(device_ref, AV_HWDEVICE_TYPE_VAAPI,
                                        device, NULL, 0);
    if (result < 0) {
        fprintf(stderr, "azk: hardware video unavailable: VA-API initialization failed (%d)\n",
                result);
        return 0;
    }

    context->hw_device_ctx = av_buffer_ref(*device_ref);
    if (!context->hw_device_ctx) {
        av_buffer_unref(device_ref);
        return 0;
    }
    context->get_format = choose_vaapi_format;
    // libavcodec cannot apply left/top cropping to opaque VA surfaces and may
    // discard those offsets. Retain the full crop rectangle for our GPU VPP.
    context->apply_cropping = 0;
    fprintf(stderr, "azk: video decoder: vaapi ready, allocator output%s%s\n",
            device ? " device=" : "", device ? device : "");
    return 1;
}

static atomic_flag vpp_failure_logged = ATOMIC_FLAG_INIT;

void azk_vaapi_vpp_free(AzkVaapiVpp **slot) {
    if (!slot || !*slot) return;
    AzkVaapiVpp *vpp = *slot;
    if (vpp->context != VA_INVALID_ID)
        vaDestroyContext(vpp->display, vpp->context);
    if (vpp->config != VA_INVALID_ID)
        vaDestroyConfig(vpp->display, vpp->config);
    free(vpp);
    *slot = NULL;
}

static int prepare_vpp(AzkVaapiVpp **slot, VADisplay display,
                       int width, int height) {
    if (*slot && (*slot)->display == display && (*slot)->width == width &&
        (*slot)->height == height)
        return 1;

    azk_vaapi_vpp_free(slot);
    AzkVaapiVpp *vpp = calloc(1, sizeof(*vpp));
    if (!vpp) return 0;
    vpp->display = display;
    vpp->config = VA_INVALID_ID;
    vpp->context = VA_INVALID_ID;
    vpp->width = width;
    vpp->height = height;

    VAConfigAttrib attribute = {
        .type = VAConfigAttribRTFormat,
        .value = VA_RT_FORMAT_RGB32,
    };
    VAStatus status = vaCreateConfig(display, VAProfileNone,
                                     VAEntrypointVideoProc,
                                     &attribute, 1, &vpp->config);
    if (status == VA_STATUS_SUCCESS) {
        status = vaCreateContext(display, vpp->config, width, height,
                                 VA_PROGRESSIVE, NULL, 0, &vpp->context);
    }
    if (status != VA_STATUS_SUCCESS) {
        if (!atomic_flag_test_and_set(&vpp_failure_logged))
            fprintf(stderr, "azk: VAAPI RGB VPP setup failed: %s (%d)\n",
                    vaErrorStr(status), status);
        *slot = vpp;
        azk_vaapi_vpp_free(slot);
        return 0;
    }
    *slot = vpp;
    return 1;
}

static void log_vpp_failure(const char *stage, VAStatus status) {
    if (!atomic_flag_test_and_set(&vpp_failure_logged))
        fprintf(stderr, "azk: VAAPI RGB VPP %s failed: %s (%d); frame rejected\n",
                stage, vaErrorStr(status), status);
}

static int source_crop(const AVFrame *source, VARectangle *crop) {
    if (source->width <= 0 || source->height <= 0 ||
        source->width > UINT16_MAX || source->height > UINT16_MAX ||
        source->crop_left > INT16_MAX || source->crop_top > INT16_MAX ||
        source->crop_left >= (size_t)source->width ||
        source->crop_top >= (size_t)source->height ||
        source->crop_right >= (size_t)source->width - source->crop_left ||
        source->crop_bottom >= (size_t)source->height - source->crop_top) return 0;
    *crop = (VARectangle) {
        .x = source->crop_left, .y = source->crop_top,
        .width = source->width - source->crop_left - source->crop_right,
        .height = source->height - source->crop_top - source->crop_bottom,
    };
    return 1;
}

static int render_vpp_rgb(const AVFrame *source, VADisplay display,
        VASurfaceID input, VASurfaceID output, int target_width,
        int target_height, AzkVaapiVpp **vpp_slot) {
    VARectangle source_region;
    if (!source_crop(source, &source_region) ||
        !prepare_vpp(vpp_slot, display, target_width, target_height)) return 0;
    AzkVaapiVpp *vpp = *vpp_slot;
    VABufferID pipeline_buffer = VA_INVALID_ID;
    VAStatus status;
    VARectangle output_region = {
        .x = 0, .y = 0,
        .width = (uint16_t)target_width,
        .height = (uint16_t)target_height,
    };
    // Preserve declared matrix/range. Size is only a fallback for streams with
    // unspecified metadata, as in pinned ColorUtils' default-color policy.
    const int bt709 = source->colorspace == AVCOL_SPC_BT709 ||
            (source->colorspace == AVCOL_SPC_UNSPECIFIED && default_primaries(source) == AVCOL_PRI_BT709);
    const VAProcColorStandardType color_standard = bt709
            ? VAProcColorStandardBT709 : VAProcColorStandardBT601;
    VAProcPipelineParameterBuffer pipeline;
    memset(&pipeline, 0, sizeof(pipeline));
    pipeline.surface = input;
    pipeline.surface_region = &source_region;
    pipeline.surface_color_standard = color_standard;
    pipeline.output_region = &output_region;
    pipeline.output_background_color = 0xff000000;
    pipeline.output_color_standard = color_standard;
    pipeline.input_color_properties.color_range = source->color_range == AVCOL_RANGE_JPEG
            ? VA_SOURCE_RANGE_FULL : VA_SOURCE_RANGE_REDUCED;
    pipeline.output_color_properties.color_range = VA_SOURCE_RANGE_FULL;
    pipeline.filter_flags = VA_FILTER_SCALING_FAST |
                            VA_FILTER_INTERPOLATION_BILINEAR;

    status = vaCreateBuffer(display, vpp->context,
                            VAProcPipelineParameterBufferType,
                            sizeof(pipeline), 1, &pipeline, &pipeline_buffer);
    int picture_begun = 0;
    if (status == VA_STATUS_SUCCESS) {
        status = vaBeginPicture(display, vpp->context, output);
        picture_begun = status == VA_STATUS_SUCCESS;
    }
    if (status == VA_STATUS_SUCCESS)
        status = vaRenderPicture(display, vpp->context, &pipeline_buffer, 1);
    if (picture_begun) {
        const VAStatus end_status = vaEndPicture(display, vpp->context);
        if (status == VA_STATUS_SUCCESS) status = end_status;
    }
    if (pipeline_buffer != VA_INVALID_ID)
        vaDestroyBuffer(display, pipeline_buffer);
    if (status == VA_STATUS_SUCCESS)
        status = vaSyncSurface(display, output);
    if (status != VA_STATUS_SUCCESS) {
        log_vpp_failure("processing", status);
        return 0;
    }
    return 1;
}

int azk_vaapi_frame_info(const AVFrame *source, int *width, int *height, int *dataspace) {
    VARectangle crop;
    if (!source || source->format != AV_PIX_FMT_VAAPI ||
        !source->hw_frames_ctx || !source_crop(source, &crop)) return -EINVAL;
    // Never export a reusable decoder-pool allocation into a queue that may
    // outlive MediaCodec. Unsupported color conversion must fail visibly.
    if (((source->colorspace == AVCOL_SPC_UNSPECIFIED ||
          source->color_primaries == AVCOL_PRI_UNSPECIFIED) &&
          default_primaries(source) == AVCOL_PRI_BT2020) ||
        (source->colorspace != AVCOL_SPC_UNSPECIFIED &&
         source->colorspace != AVCOL_SPC_BT709 &&
         source->colorspace != AVCOL_SPC_BT470BG &&
         source->colorspace != AVCOL_SPC_SMPTE170M) ||
        (source->color_primaries != AVCOL_PRI_UNSPECIFIED &&
         source->color_primaries != AVCOL_PRI_BT709 &&
         source->color_primaries != AVCOL_PRI_BT470BG &&
         source->color_primaries != AVCOL_PRI_SMPTE170M) ||
        (source->color_trc != AVCOL_TRC_UNSPECIFIED &&
         source->color_trc != AVCOL_TRC_BT709 &&
         source->color_trc != AVCOL_TRC_SMPTE170M &&
         source->color_trc != AVCOL_TRC_IEC61966_2_1 &&
         source->color_trc != AVCOL_TRC_GAMMA22)) {
        fprintf(stderr, "azk: Surface video color conversion unsupported: matrix=%d primaries=%d transfer=%d\n",
                source->colorspace, source->color_primaries, source->color_trc);
        return -ENOTSUP;
    }
    // Pinned android_dataspace fields: VPP expands SDR YUV to full-range RGB.
    const int standard = source->color_primaries == AVCOL_PRI_BT709 ? 1 :
            source->color_primaries == AVCOL_PRI_BT470BG ? 2 :
            source->color_primaries == AVCOL_PRI_SMPTE170M ? 4 :
            default_primaries(source) == AVCOL_PRI_BT709 ? 1 :
            default_primaries(source) == AVCOL_PRI_BT470BG ? 2 : 4;
    const int transfer = source->color_trc == AVCOL_TRC_IEC61966_2_1 ? 2 :
            source->color_trc == AVCOL_TRC_GAMMA22 ? 4 : 3;
    *width = crop.width;
    *height = crop.height;
    *dataspace = (standard << 16) | (transfer << 22) | (1 << 27);
    return 0;
}

int azk_vaapi_render_into(const AVFrame *source, const AzkGraphicsBufferInfo *target,
                          AzkVaapiVpp **vpp_slot) {
    int width, height, dataspace;
    int result = azk_vaapi_frame_info(source, &width, &height, &dataspace);
    if (result) return result;
    if (!target || !vpp_slot || target->fd < 0 || target->format != 2 ||
        target->drm_format != 0x34324258 || target->layers != 1 ||
        target->width != (uint32_t)width || target->height != (uint32_t)height ||
        target->stride < (uint32_t)width * 4) return -EINVAL;
    // VA requires the object's real allocation size, including tiled padding.
    // The caller owns this descriptor; neither import nor teardown closes it.
    struct stat statbuf;
    if (fstat(target->fd, &statbuf) || statbuf.st_size <= 0 ||
        (uint64_t)statbuf.st_size > UINT32_MAX || target->offset >= statbuf.st_size)
        return -EINVAL;
    if (target->modifier == 0 && (uint64_t)target->offset +
        (uint64_t)target->stride * (height - 1) + (uint64_t)width * 4 > (uint64_t)statbuf.st_size)
        return -EINVAL;
    VADRMPRIMESurfaceDescriptor descriptor = {
        .fourcc = VA_FOURCC_RGBX, .width = width, .height = height,
        .num_objects = 1,
        .objects = {{ .fd = target->fd, .size = statbuf.st_size,
                      .drm_format_modifier = target->modifier }},
        .num_layers = 1,
        .layers = {{ .drm_format = target->drm_format, .num_planes = 1,
                     .object_index = {0}, .offset = {target->offset}, .pitch = {target->stride} }},
    };
    VASurfaceAttrib attrs[] = {
        { .type = VASurfaceAttribMemoryType, .flags = VA_SURFACE_ATTRIB_SETTABLE,
          .value = { .type = VAGenericValueTypeInteger,
                     .value.i = VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2 } },
        { .type = VASurfaceAttribExternalBufferDescriptor, .flags = VA_SURFACE_ATTRIB_SETTABLE,
          .value = { .type = VAGenericValueTypePointer, .value.p = &descriptor } },
    };
    AVHWFramesContext *frames = (AVHWFramesContext *)source->hw_frames_ctx->data;
    AVVAAPIDeviceContext *device = (AVVAAPIDeviceContext *)frames->device_ctx->hwctx;
    VASurfaceID output = VA_INVALID_SURFACE;
    VAStatus status = vaCreateSurfaces(device->display, VA_RT_FORMAT_RGB32,
                                       width, height, &output, 1, attrs, 2);
    if (status != VA_STATUS_SUCCESS) {
        log_vpp_failure("allocator DMA-BUF import", status);
        return -EIO;
    }
    result = render_vpp_rgb(source, device->display, (VASurfaceID)(uintptr_t)source->data[3],
                            output, width, height, vpp_slot);
    vaDestroySurfaces(device->display, &output, 1);
    return result ? 0 : -EIO;
}
