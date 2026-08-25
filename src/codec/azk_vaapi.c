#include "azk_vaapi.h"

#include <errno.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vaapi.h>
#include <va/va.h>
#include <va/va_drmcommon.h>

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
    const char *selection = getenv("ARTZIG_VIDEO_DECODER");
    if (selection && strcmp(selection, "software") == 0) {
        fprintf(stderr, "azk: video decoder: software+wl_shm (forced)\n");
        return 0;
    }

    typedef int (*DmabufAvailableFn)(void);
    DmabufAvailableFn dmabuf_available =
        (DmabufAvailableFn)dlsym(RTLD_DEFAULT, "azk_video_dmabuf_available");
    if (!dmabuf_available || !dmabuf_available()) {
        fprintf(stderr,
                "azk: video decoder: software+wl_shm (compositor has no compatible DMA-BUF path)\n");
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
        fprintf(stderr, "azk: video decoder: software+wl_shm (codec has no VA-API path)\n");
        return 0;
    }

    const char *device = getenv("ARTZIG_VAAPI_DEVICE");
    if (device && device[0] == '\0') device = NULL;
    int result = av_hwdevice_ctx_create(device_ref, AV_HWDEVICE_TYPE_VAAPI,
                                        device, NULL, 0);
    if (result < 0) {
        fprintf(stderr, "azk: video decoder: software+wl_shm (VA-API unavailable: %d)\n",
                result);
        return 0;
    }

    context->hw_device_ctx = av_buffer_ref(*device_ref);
    if (!context->hw_device_ctx) {
        av_buffer_unref(device_ref);
        return 0;
    }
    context->get_format = choose_vaapi_format;
    fprintf(stderr, "azk: video decoder: vaapi+dmabuf%s%s\n",
            device ? " device=" : "", device ? device : "");
    return 1;
}

static void close_descriptor(VADRMPRIMESurfaceDescriptor *descriptor) {
    for (uint32_t index = 0; index < descriptor->num_objects; ++index) {
        if (descriptor->objects[index].fd >= 0) close(descriptor->objects[index].fd);
    }
}

static int compositor_supports(const VADRMPRIMESurfaceDescriptor *descriptor) {
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

AzkHardwareFrame *azk_vaapi_export(const AVFrame *source) {
    if (!source || source->format != AV_PIX_FMT_VAAPI || !source->hw_frames_ctx)
        return NULL;

    AVHWFramesContext *frames = (AVHWFramesContext *)source->hw_frames_ctx->data;
    AVVAAPIDeviceContext *device =
        (AVVAAPIDeviceContext *)frames->device_ctx->hwctx;
    VASurfaceID surface = (VASurfaceID)(uintptr_t)source->data[3];
    VADRMPRIMESurfaceDescriptor descriptor;
    memset(&descriptor, 0, sizeof(descriptor));

    VAStatus status = vaExportSurfaceHandle(
        device->display, surface, VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2,
        VA_EXPORT_SURFACE_READ_ONLY | VA_EXPORT_SURFACE_COMPOSED_LAYERS,
        &descriptor);
    if (status != VA_STATUS_SUCCESS) return NULL;

    if (descriptor.num_layers != 1 || descriptor.layers[0].num_planes == 0 ||
        descriptor.layers[0].num_planes > AZK_DMABUF_MAX_PLANES ||
        descriptor.num_objects > AZK_DMABUF_MAX_PLANES ||
        !compositor_supports(&descriptor)) {
        close_descriptor(&descriptor);
        return NULL;
    }

    AzkHardwareFrame *frame = calloc(1, sizeof(*frame));
    if (!frame) {
        close_descriptor(&descriptor);
        return NULL;
    }
    frame->frame = av_frame_clone(source);
    if (!frame->frame) {
        close_descriptor(&descriptor);
        free(frame);
        return NULL;
    }

    frame->width = source->width;
    frame->height = source->height;
    frame->format = descriptor.layers[0].drm_format;
    frame->plane_count = descriptor.layers[0].num_planes;
    frame->object_count = descriptor.num_objects;
    for (int index = 0; index < frame->object_count; ++index)
        frame->object_fds[index] = descriptor.objects[index].fd;

    for (int index = 0; index < frame->plane_count; ++index) {
        const uint32_t object = descriptor.layers[0].object_index[index];
        if (object >= descriptor.num_objects) {
            azk_hardware_frame_release(frame);
            return NULL;
        }
        frame->fds[index] = descriptor.objects[object].fd;
        frame->offsets[index] = descriptor.layers[0].offset[index];
        frame->strides[index] = descriptor.layers[0].pitch[index];
        frame->modifiers[index] = descriptor.objects[object].drm_format_modifier;
    }
    return frame;
}

void azk_hardware_frame_release(AzkHardwareFrame *frame) {
    if (!frame) return;
    for (int index = 0; index < frame->object_count; ++index) {
        if (frame->object_fds[index] >= 0) close(frame->object_fds[index]);
    }
    av_frame_free(&frame->frame);
    free(frame);
}
