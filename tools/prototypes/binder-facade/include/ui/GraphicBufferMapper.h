#pragma once
// Host seam for GraphicBuffer::unflatten: clone an opaque native handle.
// No Android gralloc service, hardware allocation, layout inference, or
// locking.
#include <android-base/unique_fd.h>
#include <cutils/native_handle.h>
#include <hardware/gralloc.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>

#include <vector>
namespace android {
namespace ui {
enum class Dataspace : int32_t;
struct PlaneLayout {
  int64_t sampleIncrementInBits;
  int64_t strideInBytes;
};
}  // namespace ui
template <class T>
struct ProbeUnavailableResult {
  bool has_value() const { return false; }
  status_t asStatus() const { return INVALID_OPERATION; }
  ProbeUnavailableResult error() const { return {}; }
  T value() const { abort(); }
};
class GraphicBufferMapper {
 public:
  enum Version { GRALLOC_2 = 2, GRALLOC_3, GRALLOC_4, GRALLOC_5 };
  static GraphicBufferMapper& get() {
    static GraphicBufferMapper m;
    return m;
  }
  Version getMapperVersion() const { return GRALLOC_4; }
  status_t importBuffer(const native_handle_t* h, uint32_t, uint32_t, uint32_t,
                        PixelFormat, uint64_t, uint32_t, buffer_handle_t* out) {
    *out = native_handle_clone(h);
    return *out ? OK : NO_MEMORY;
  }
  status_t freeBuffer(buffer_handle_t h) {
    native_handle_close(h);
    return native_handle_delete(const_cast<native_handle_t*>(h));
  }
  void getTransportSize(buffer_handle_t h, uint32_t* f, uint32_t* i) {
    *f = h->numFds;
    *i = h->numInts;
  }
  status_t getDataspace(buffer_handle_t, ui::Dataspace*) {
    return INVALID_OPERATION;
  }
  status_t getName(buffer_handle_t, std::string* name) {
    *name = "facade-probe";
    return OK;
  }
  struct LockResult {
    void* address;
    int32_t bytesPerPixel;
    int32_t bytesPerStride;
  };
  ProbeUnavailableResult<std::vector<ui::PlaneLayout>> getPlaneLayouts(
      buffer_handle_t) {
    return {};
  }
  ProbeUnavailableResult<LockResult> lock(buffer_handle_t, uint64_t,
                                          const Rect&, base::unique_fd) {
    return {};
  }
  ProbeUnavailableResult<android_ycbcr> lockYCbCr(buffer_handle_t, int64_t,
                                                  const Rect&,
                                                  base::unique_fd) {
    return {};
  }
  status_t unlockAsync(buffer_handle_t, int*) { return INVALID_OPERATION; }
  status_t isSupported(uint32_t, uint32_t, PixelFormat, uint32_t, uint64_t,
                       bool*) {
    return INVALID_OPERATION;
  }
};
}  // namespace android
