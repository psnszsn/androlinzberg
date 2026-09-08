#pragma once
#include <azk/graphics_buffer.h>
#include <gui/Surface.h>
#include <memory>
#include <array>
#include <deque>

namespace azk {
// Host equivalent of ACodec's native-window output connection. FFmpeg cannot
// use Android codec buffer allocators: import its completed, immutable, uniquely
// allocated VPP output and let stock Surface own queue state and timestamps.
// Borrowed storage must remain immutable for its entire exported FD lifetime;
// decoder-pool frames do not satisfy this contract.
class VideoSurface final {
public:
    static std::unique_ptr<VideoSurface> connect(
            const android::sp<android::Surface>&, android::status_t*);
    ~VideoSurface();
    android::status_t configure(int rotationDegrees, int scalingMode);
    android::status_t queue(const AzkGraphicsBufferInfo&, int64_t timestampNs,
                           int64_t presentationTimeUs = 0);
    // CCodecBufferChannel frame tracking at the hosted codec seam. A caller
    // serializes access with queue/configure/disconnect; no callback owns this object.
    static constexpr size_t kMaxTrackedFrames = 128;
    struct RenderedFrame { int64_t presentationTimeUs, renderTimeNs; };
    struct RenderedFrames {
        std::array<RenderedFrame, kMaxTrackedFrames> frames;
        size_t count = 0, pending = 0;
    };
    void setFrameTracking(bool enabled);
    RenderedFrames drainRenderedFrames();
private:
    explicit VideoSurface(const android::sp<android::Surface>& surface) : mSurface(surface) {}
    android::sp<android::Surface> mSurface;
    struct TrackedFrame { uint64_t number; int64_t presentationTimeUs, desiredTimeNs, checkTimeNs; };
    std::deque<TrackedFrame> mTrackedFrames;
    bool mFrameTracking = false, mSurfaceToDisplay = false, mHasPresentTimes = false;
};
}
