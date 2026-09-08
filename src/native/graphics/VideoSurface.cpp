#include <azk/VideoSurface.h>
#include <system/window.h>

namespace azk {
using namespace android;

std::unique_ptr<VideoSurface> VideoSurface::connect(const sp<Surface>& surface,
                                                   status_t* status) {
    *status = BAD_VALUE;
    if (!Surface::isValid(surface)) return nullptr;
    *status = native_window_api_connect(surface.get(), NATIVE_WINDOW_API_MEDIA);
    if (*status != OK) return nullptr;
    auto output = std::unique_ptr<VideoSurface>(new VideoSurface(surface));
    // One producer-owned slot plus the stock consumer's required slots. A slow
    // consumer fails with TIMED_OUT; it cannot grow a private video backlog.
    *status = surface->setMaxDequeuedBufferCount(1);
    if (*status == OK) *status = surface->setDequeueTimeout(1000000000LL);
    if (*status == OK) *status = surface->setScalingMode(NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
    if (*status != OK) return nullptr;
    return output;
}

VideoSurface::~VideoSurface() {
    mSurface->disconnect(NATIVE_WINDOW_API_MEDIA);
}

status_t VideoSurface::configure(int rotationDegrees, int scalingMode) {
    if (scalingMode != NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW &&
        scalingMode != NATIVE_WINDOW_SCALING_MODE_SCALE_CROP) return BAD_VALUE;
    // Pinned SurfaceUtils::setNativeWindowSizeFormatAndUsage: unsupported
    // non-quarter-turn angles use identity; negative quarter turns wrap.
    uint32_t transform = 0;
    if (rotationDegrees % 90 == 0) {
        switch ((rotationDegrees / 90) & 3) {
            case 1: transform = HAL_TRANSFORM_ROT_90; break;
            case 2: transform = HAL_TRANSFORM_ROT_180; break;
            case 3: transform = HAL_TRANSFORM_ROT_270; break;
        }
    }
    status_t status = mSurface->setBuffersTransform(transform);
    if (status == OK) status = mSurface->setScalingMode(scalingMode);
    return status;
}

status_t VideoSurface::queue(const AzkGraphicsBufferInfo& source, int64_t timestampNs) {
    // The codec seam supplies RGBX/RGBA/BGRA only, after vaSyncSurface. Do not
    // misinterpret YUV, HDR, protected storage, or a pending decode fence.
    if (source.format != PIXEL_FORMAT_RGBA_8888 &&
        source.format != PIXEL_FORMAT_RGBX_8888 &&
        source.format != PIXEL_FORMAT_BGRA_8888) return BAD_VALUE;
    native_handle_t* handle = nullptr;
    status_t status = azk_graphics_import_external(&source, &handle);
    if (status != OK) return status;
    AzkGraphicsBufferInfo imported{};
    status = azk_graphics_describe(handle, &imported);
    sp<GraphicBuffer> buffer;
    if (status == OK) {
        // CLONE_HANDLE initializes the pinned GraphicBuffer transport sizes.
        buffer = sp<GraphicBuffer>::make(handle, GraphicBuffer::CLONE_HANDLE,
                imported.width, imported.height, PixelFormat(imported.format),
                1, imported.usage, imported.stride / 4);
        status = buffer->initCheck();
    }
    azk_graphics_free(handle);
    if (status != OK) return status;
    status = mSurface->setBuffersTimestamp(timestampNs);
    if (status == OK) status = mSurface->setBuffersDataSpace(ui::Dataspace(source.dataspace));
    const Rect crop(source.width, source.height);
    if (status == OK) status = mSurface->setCrop(&crop);
    if (status == OK) status = mSurface->attachBuffer(buffer->getNativeBuffer());
    if (status != OK) return status;
    auto* window = static_cast<ANativeWindow*>(mSurface.get());
    status = window->queueBuffer(window, buffer->getNativeBuffer(), -1);
    if (status != OK) window->cancelBuffer(window, buffer->getNativeBuffer(), -1);
    return status;
}
}
