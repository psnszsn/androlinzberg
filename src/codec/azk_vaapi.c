#include "azk_vaapi.h"
#include <azk/graphics_buffer_info.h>

#include <errno.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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

static int stock_surface_output(void) {
    const char *backend = getenv("AZK_WINDOW_BACKEND");
    return backend && strcmp(backend, "stock") == 0;
}

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

int azk_vaapi_prepare(AVCodecContext *context, AVBufferRef **device_ref) {
    typedef int (*DmabufAvailableFn)(void);
    DmabufAvailableFn dmabuf_available =
        (DmabufAvailableFn)dlsym(RTLD_DEFAULT, stock_surface_output()
                ? "azk_video_surface_available" : "azk_video_dmabuf_available");
    if (!dmabuf_available || !dmabuf_available()) {
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
    fprintf(stderr, "azk: video decoder: vaapi ready, DMA-BUF export pending%s%s\n",
            device ? " device=" : "", device ? device : "");
    return 1;
}

static atomic_flag export_success_logged = ATOMIC_FLAG_INIT;
static atomic_flag export_failure_logged = ATOMIC_FLAG_INIT;
static atomic_flag vpp_failure_logged = ATOMIC_FLAG_INIT;

static void print_fourcc(uint32_t format) {
    fprintf(stderr, "%c%c%c%c (0x%08x)",
            (char)(format & 0xff), (char)((format >> 8) & 0xff),
            (char)((format >> 16) & 0xff), (char)((format >> 24) & 0xff),
            format);
}

static void log_export_descriptor_once(const char *outcome, const char *reason,
                                       const VADRMPRIMESurfaceDescriptor *descriptor,
                                       atomic_flag *guard) {
    if (atomic_flag_test_and_set(guard)) return;
    fprintf(stderr, "azk: DMA-BUF export %s: %s; format=", outcome, reason);
    if (descriptor->num_layers > 0) {
        print_fourcc(descriptor->layers[0].drm_format);
    } else {
        fprintf(stderr, "unavailable");
    }
    fprintf(stderr, " layers=%u planes=%u objects=%u modifiers=[",
            descriptor->num_layers,
            descriptor->num_layers > 0 ? descriptor->layers[0].num_planes : 0,
            descriptor->num_objects);
    const uint32_t object_count = descriptor->num_objects < AZK_DMABUF_MAX_PLANES
        ? descriptor->num_objects : AZK_DMABUF_MAX_PLANES;
    for (uint32_t index = 0; index < object_count; ++index) {
        fprintf(stderr, "%s0x%016llx", index ? "," : "",
                (unsigned long long)descriptor->objects[index].drm_format_modifier);
    }
    fprintf(stderr, "]\n");
}

static void close_descriptor(VADRMPRIMESurfaceDescriptor *descriptor) {
    for (uint32_t index = 0; index < descriptor->num_objects; ++index) {
        if (descriptor->objects[index].fd >= 0) close(descriptor->objects[index].fd);
    }
}

static int compositor_supports(
    const VADRMPRIMESurfaceDescriptor *descriptor);

static const char *descriptor_rejection(
        const VADRMPRIMESurfaceDescriptor *descriptor) {
    if (descriptor->num_layers != 1)
        return "expected one composed layer";
    if (descriptor->layers[0].num_planes == 0)
        return "exported layer has no planes";
    if (descriptor->layers[0].num_planes > AZK_DMABUF_MAX_PLANES)
        return "exported layer has too many planes";
    if (descriptor->num_objects > AZK_DMABUF_MAX_PLANES)
        return "exported surface has too many objects";
    if (!compositor_supports(descriptor))
        return "compositor did not advertise the exported format/modifier";
    return NULL;
}

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

static int compositor_supports(const VADRMPRIMESurfaceDescriptor *descriptor) {
    if (stock_surface_output()) {
        // Single-plane RGBX from a separate VPP allocation; the actual modifier
        // is validated by GBM import, not a fabricated Wayland capability list.
        return descriptor->num_objects == 1 &&
                descriptor->layers[0].num_planes == 1 &&
                descriptor->layers[0].object_index[0] == 0 &&
                descriptor->layers[0].drm_format == 0x34324258; // DRM_FORMAT_XBGR8888
    }
    typedef int (*DmabufSupportsFn)(uint32_t, int, const uint64_t *);
    DmabufSupportsFn supports =
        (DmabufSupportsFn)dlsym(RTLD_DEFAULT, "azk_video_dmabuf_supports");
    if (!supports) return 0;

    uint64_t modifiers[AZK_DMABUF_MAX_PLANES];
    for (uint32_t index = 0; index < descriptor->layers[0].num_planes; ++index) {
        const uint32_t object = descriptor->layers[0].object_index[index];
        if (object >= descriptor->num_objects) return 0;
        modifiers[index] = descriptor->objects[object].drm_format_modifier;
    }
    return supports(descriptor->layers[0].drm_format,
                    descriptor->layers[0].num_planes, modifiers);
}

static AzkHardwareFrame *take_descriptor(
        const AVFrame *source, VADRMPRIMESurfaceDescriptor *descriptor,
        int width, int height, VADisplay display, VASurfaceID owned_surface) {
    AzkHardwareFrame *frame = calloc(1, sizeof(*frame));
    if (!frame) {
        close_descriptor(descriptor);
        if (owned_surface != VA_INVALID_SURFACE)
            vaDestroySurfaces(display, &owned_surface, 1);
        return NULL;
    }
    for (int index = 0; index < AZK_DMABUF_MAX_PLANES; ++index) {
        frame->fds[index] = -1;
        frame->object_fds[index] = -1;
    }
    frame->width = width;
    frame->height = height;
    frame->format = descriptor->layers[0].drm_format;
    frame->plane_count = descriptor->layers[0].num_planes;
    frame->object_count = descriptor->num_objects;
    frame->va_display = display;
    frame->va_surface = owned_surface;
    frame->owns_va_surface = owned_surface != VA_INVALID_SURFACE;
    for (int index = 0; index < frame->object_count; ++index)
        frame->object_fds[index] = descriptor->objects[index].fd;

    if (frame->owns_va_surface) {
        frame->device_ref = av_buffer_ref(source->hw_frames_ctx);
        if (!frame->device_ref) {
            azk_hardware_frame_release(frame);
            return NULL;
        }
    } else {
        frame->frame = av_frame_clone(source);
        if (!frame->frame) {
            azk_hardware_frame_release(frame);
            return NULL;
        }
    }

    for (int index = 0; index < frame->plane_count; ++index) {
        const uint32_t object = descriptor->layers[0].object_index[index];
        if (object >= descriptor->num_objects) {
            azk_hardware_frame_release(frame);
            return NULL;
        }
        frame->fds[index] = descriptor->objects[object].fd;
        frame->offsets[index] = descriptor->layers[0].offset[index];
        frame->strides[index] = descriptor->layers[0].pitch[index];
        frame->modifiers[index] = descriptor->objects[object].drm_format_modifier;
    }
    return frame;
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

static AzkHardwareFrame *export_vpp_rgb(
        const AVFrame *source, VADisplay display, VASurfaceID input,
        int target_width, int target_height, AzkVaapiVpp **vpp_slot) {
    VARectangle source_region;
    if (!source_crop(source, &source_region)) return NULL;
    if (target_width <= 0 || target_width == source->width) target_width = source_region.width;
    if (target_height <= 0 || target_height == source->height) target_height = source_region.height;
    if (target_width > UINT16_MAX || target_height > UINT16_MAX) return NULL;
    if (!prepare_vpp(vpp_slot, display, target_width, target_height))
        return NULL;
    AzkVaapiVpp *vpp = *vpp_slot;

    VASurfaceID output = VA_INVALID_SURFACE;
    VABufferID pipeline_buffer = VA_INVALID_ID;
    VASurfaceAttrib pixel_format = {
        .type = VASurfaceAttribPixelFormat,
        .flags = VA_SURFACE_ATTRIB_SETTABLE,
        .value = {
            .type = VAGenericValueTypeInteger,
            .value = { .i = stock_surface_output() ? VA_FOURCC_RGBX : VA_FOURCC_BGRX },
        },
    };
    VAStatus status = vaCreateSurfaces(display, VA_RT_FORMAT_RGB32,
                                       target_width, target_height,
                                       &output, 1, &pixel_format, 1);
    if (status != VA_STATUS_SUCCESS) {
        log_vpp_failure("surface allocation", status);
        return NULL;
    }

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
        vaDestroySurfaces(display, &output, 1);
        return NULL;
    }

    VADRMPRIMESurfaceDescriptor descriptor;
    memset(&descriptor, 0, sizeof(descriptor));
    status = vaExportSurfaceHandle(
        display, output, VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2,
        VA_EXPORT_SURFACE_READ_ONLY | VA_EXPORT_SURFACE_COMPOSED_LAYERS,
        &descriptor);
    if (status != VA_STATUS_SUCCESS) {
        log_vpp_failure("DMA-BUF export", status);
        vaDestroySurfaces(display, &output, 1);
        return NULL;
    }
    const char *rejection = descriptor_rejection(&descriptor);
    if (rejection) {
        log_export_descriptor_once("rejected", rejection, &descriptor,
                                   &export_failure_logged);
        close_descriptor(&descriptor);
        vaDestroySurfaces(display, &output, 1);
        return NULL;
    }

    log_export_descriptor_once("confirmed", "GPU VPP RGB path active",
                               &descriptor, &export_success_logged);
    AzkHardwareFrame *frame = take_descriptor(source, &descriptor, target_width,
                                              target_height, display, output);
    if (frame && stock_surface_output()) {
        // Pinned android_dataspace bit fields. RGB output retains SDR primaries
        // and transfer; VPP expands the YUV input range to full-range RGB.
        const int standard = source->color_primaries == AVCOL_PRI_BT709 ? 1 :
                source->color_primaries == AVCOL_PRI_BT470BG ? 2 :
                source->color_primaries == AVCOL_PRI_SMPTE170M ? 4 :
                default_primaries(source) == AVCOL_PRI_BT709 ? 1 :
                default_primaries(source) == AVCOL_PRI_BT470BG ? 2 : 4;
        const int transfer = source->color_trc == AVCOL_TRC_IEC61966_2_1 ? 2 :
                source->color_trc == AVCOL_TRC_GAMMA22 ? 4 : 3;
        frame->dataspace = (standard << 16) | (transfer << 22) | (1 << 27);
    }
    return frame;
}

AzkHardwareFrame *azk_vaapi_export(const AVFrame *source, int target_width,
                                   int target_height, AzkVaapiVpp **vpp_slot) {
    if (!source || source->format != AV_PIX_FMT_VAAPI ||
        !source->hw_frames_ctx || !vpp_slot)
        return NULL;

    AVHWFramesContext *frames = (AVHWFramesContext *)source->hw_frames_ctx->data;
    AVVAAPIDeviceContext *device =
        (AVVAAPIDeviceContext *)frames->device_ctx->hwctx;
    VASurfaceID surface = (VASurfaceID)(uintptr_t)source->data[3];
    if (stock_surface_output()) {
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
            return NULL;
        }
        return export_vpp_rgb(source, device->display, surface,
                               target_width, target_height, vpp_slot);
    }
    // A direct DMA-BUF export has no representation for the decoder crop in
    // the legacy presenter. Apply it on the GPU before exporting that path too.
    if (source->crop_left || source->crop_top || source->crop_right || source->crop_bottom)
        return export_vpp_rgb(source, device->display, surface,
                              target_width, target_height, vpp_slot);
    VADRMPRIMESurfaceDescriptor descriptor;
    memset(&descriptor, 0, sizeof(descriptor));

    VAStatus status = vaExportSurfaceHandle(
        device->display, surface, VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2,
        VA_EXPORT_SURFACE_READ_ONLY | VA_EXPORT_SURFACE_COMPOSED_LAYERS,
        &descriptor);
    if (status == VA_STATUS_SUCCESS) {
        const char *rejection = descriptor_rejection(&descriptor);
        if (!rejection) {
            log_export_descriptor_once("confirmed", "direct zero-copy path active",
                                       &descriptor, &export_success_logged);
            return take_descriptor(source, &descriptor, source->width,
                                   source->height, device->display,
                                   VA_INVALID_SURFACE);
        }
        log_export_descriptor_once("rejected", rejection, &descriptor,
                                   &export_failure_logged);
        close_descriptor(&descriptor);
    } else if (!atomic_flag_test_and_set(&export_failure_logged)) {
        fprintf(stderr, "azk: direct DMA-BUF export failed: %s (%d); trying RGB VPP\n",
                vaErrorStr(status), status);
    }

    return export_vpp_rgb(source, device->display, surface,
                          target_width, target_height, vpp_slot);
}

void azk_hardware_frame_release(AzkHardwareFrame *frame) {
    if (!frame) return;
    for (int index = 0; index < frame->object_count; ++index) {
        if (frame->object_fds[index] >= 0) close(frame->object_fds[index]);
    }
    if (frame->owns_va_surface) {
        VASurfaceID surface = frame->va_surface;
        vaDestroySurfaces((VADisplay)frame->va_display, &surface, 1);
    }
    av_frame_free(&frame->frame);
    av_buffer_unref(&frame->device_ref);
    free(frame);
}

// Borrowed description consumed synchronously by HostedVideoOutput. Restrict
// this to completed, separately allocated VPP storage: no decoder-pool reuse.
int azk_hardware_frame_describe_surface(const AzkHardwareFrame *frame,
                                       AzkGraphicsBufferInfo *out) {
    if (!frame || !out || !frame->owns_va_surface || frame->frame ||
        frame->plane_count != 1 || frame->object_count != 1 ||
        frame->format != 0x34324258 || frame->dataspace == 0)
        return -EINVAL;
    *out = (AzkGraphicsBufferInfo) {
        .width = frame->width, .height = frame->height,
        .format = 2, .layers = 1, .usage = 0x100, // RGBX_8888, HW_TEXTURE
        .drm_format = frame->format, .stride = frame->strides[0],
        .offset = frame->offsets[0], .modifier = frame->modifiers[0],
        .fd = frame->fds[0], .dataspace = frame->dataspace,
    };
    return 0;
}
