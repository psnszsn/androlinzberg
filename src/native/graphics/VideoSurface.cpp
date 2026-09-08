#include <azk/VideoSurface.h>
#include <system/window.h>
#include <utils/Timers.h>

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

void VideoSurface::setFrameTracking(bool enabled) {
    mTrackedFrames.clear();
    mFrameTracking = enabled;
    mSurfaceToDisplay = false;
    mHasPresentTimes = false;
    if (enabled) {
        // Query the producer directly: BLAST overrides this query. Surface's
        // historical authentication fallback requires an unrelated SF call for
        // an intermediate queue and ultimately also reports false.
        int display = 0;
        if (mSurface->getIGraphicBufferProducer()->query(
                NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER, &display) == OK)
            mSurfaceToDisplay = display == 1;
        if (mSurfaceToDisplay) {
            auto* window = static_cast<ANativeWindow*>(mSurface.get());
            int present = 0;
            if (window->query(window, NATIVE_WINDOW_FRAME_TIMESTAMPS_SUPPORTS_PRESENT,
                              &present) == OK) mHasPresentTimes = present == 1;
        }
    }
    mSurface->enableFrameTimestamps(enabled && mSurfaceToDisplay);
}

VideoSurface::RenderedFrames VideoSurface::drainRenderedFrames() {
    RenderedFrames result{};
    const int64_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    while (!mTrackedFrames.empty()) {
        const auto frame = mTrackedFrames.front();
        if (mSurfaceToDisplay && frame.checkTimeNs > now) break;
        mTrackedFrames.pop_front();
        nsecs_t rendered = frame.desiredTimeNs;
        if (mSurfaceToDisplay) {
            rendered = NATIVE_WINDOW_TIMESTAMP_INVALID;
            const auto status = mSurface->getFrameTimestamps(frame.number,
                    nullptr, nullptr, mHasPresentTimes ? nullptr : &rendered,
                    nullptr, nullptr, nullptr, mHasPresentTimes ? &rendered : nullptr,
                    nullptr, nullptr);
            // Pinned CCodecBufferChannel::processRenderedFrames drops samples
            // still unobserved after its 100 ms grace. Never invent scanout time.
            if (status != OK || rendered < 0) continue;
        }
        result.frames[result.count++] = {frame.presentationTimeUs, rendered};
    }
    result.pending = mTrackedFrames.size();
    return result;
}

status_t VideoSurface::queue(const AzkGraphicsBufferInfo& source, int64_t timestampNs,
                            int64_t presentationTimeUs) {
    if (mFrameTracking && mTrackedFrames.size() == kMaxTrackedFrames) return WOULD_BLOCK;
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
    const uint64_t number = mSurface->getNextFrameNumber();
    status = window->queueBuffer(window, buffer->getNativeBuffer(), -1);
    if (status != OK) window->cancelBuffer(window, buffer->getNativeBuffer(), -1);
    if (status == OK && mFrameTracking) {
        const int64_t now = systemTime(SYSTEM_TIME_MONOTONIC);
        // Match CCodecBufferChannel::trackReleasedFrame's SF scheduling clamp.
        const int64_t desired = timestampNs < now || timestampNs > now + 1000000000LL
                ? now : timestampNs;
        mTrackedFrames.push_back({number, presentationTimeUs, timestampNs,
                                  desired + 100000000LL});
    }
    return status;
}
}
