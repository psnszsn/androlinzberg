#pragma once
#include <azk/graphics_buffer.h>
#include <gui/Surface.h>
#include <memory>

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
    android::status_t queue(const AzkGraphicsBufferInfo&, int64_t timestampNs);
private:
    explicit VideoSurface(const android::sp<android::Surface>& surface) : mSurface(surface) {}
    android::sp<android::Surface> mSurface;
};
}
