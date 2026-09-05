#pragma once
#include <ui/GraphicBufferMapper.h>
namespace android {
class GraphicBufferAllocator {
 public:
  struct AdditionalOptions {
    const char* name;
    int64_t value;
  };
  struct AllocationRequest {
    bool importBuffer;
    uint32_t width, height;
    PixelFormat format;
    uint32_t layerCount;
    uint64_t usage;
    std::string requestorName;
    std::vector<AdditionalOptions> extras;
  };
  struct AllocationResult {
    status_t status = INVALID_OPERATION;
    buffer_handle_t handle = nullptr;
    uint32_t stride = 0;
  };
  static GraphicBufferAllocator& get() {
    static GraphicBufferAllocator a;
    return a;
  }
  AllocationResult allocate(const AllocationRequest&) { return {}; }
  status_t allocate(uint32_t, uint32_t, PixelFormat, uint32_t, uint64_t,
                    buffer_handle_t*, uint32_t*, uint64_t, std::string) {
    return INVALID_OPERATION;
  }
  status_t free(buffer_handle_t) { return INVALID_OPERATION; }
  static void dumpToSystemLog(bool = true) {}
};
}  // namespace android
