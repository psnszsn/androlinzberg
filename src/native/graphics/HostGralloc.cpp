// Host implementation of pinned AOSP ui/Gralloc.h. GraphicBufferMapper and
// GraphicBufferAllocator retain their AOSP control flow; only HAL selection is
// adapted because this host allocates/imports GBM buffers instead of using a
// HAL.
#include <azk/HostGralloc.h>
#include <aidl/android/hardware/graphics/common/PlaneLayoutComponentType.h>
#include <azk/graphics_buffer.h>
#include <ui/Gralloc.h>
#include <ui/Fence.h>
#include <utils/Log.h>

namespace android::azk {
namespace {
class Mapper final : public GrallocMapper {
public:
  bool isLoaded() const override { return true; }
  status_t importBuffer(const native_handle_t *raw,
                        buffer_handle_t *out) const override {
    native_handle_t *handle = nullptr;
    auto status = azk_graphics_import(raw, &handle);
    *out = handle;
    return status;
  }
  void freeBuffer(buffer_handle_t handle) const override {
    const auto status = azk_graphics_free(handle);
    ALOGE_IF(status, "host gralloc free failed: %d", status);
  }
  status_t validateBufferSize(buffer_handle_t handle, uint32_t w, uint32_t h,
                              PixelFormat format, uint32_t layers,
                              uint64_t usage, uint32_t stride) const override {
    AzkGraphicsBufferInfo info{};
    if (azk_graphics_describe(handle, &info))
      return BAD_VALUE;
    // Match all allocation metadata before accepting an imported native
    // handle as the GraphicBuffer described by its outer Parcel fields.
    return info.width == w && info.height == h &&
                   info.format == uint32_t(format) && info.layers == layers &&
                   info.usage == usage &&
                   info.stride == stride * uint64_t(bytesPerPixel(format))
               ? OK
               : BAD_VALUE;
  }
  void getTransportSize(buffer_handle_t handle, uint32_t *fds,
                        uint32_t *ints) const override {
    AzkGraphicsBufferInfo info{};
    if (azk_graphics_describe(handle, &info)) {
      *fds = *ints = 0;
      return;
    }
    *fds = handle->numFds;
    *ints = handle->numInts;
  }
  status_t lock(buffer_handle_t handle, uint64_t usage, const Rect &rect,
                int fence, void **address, int32_t *pixelStride,
                int32_t *rowStride) const override {
    // Fence is an AOSP object with the contract to consume its descriptor
    // even on failure. Synchronization precedes access to the mapped buffer.
    auto acquire = sp<Fence>::make(fence);
    auto status = acquire->wait(3000);
    if (status != OK)
      return status;
    status = azk_graphics_lock(handle, usage, rect.left, rect.top, rect.right,
                               rect.bottom, address, pixelStride, rowStride);
    if (status != OK) {
      AzkGraphicsBufferInfo info{};
      azk_graphics_describe(handle, &info);
      ALOGE("host buffer lock failed: status=%d usage=%#llx "
            "allocationUsage=%#llx "
            "rect=%d,%d,%d,%d size=%ux%u",
            status, static_cast<unsigned long long>(usage),
            static_cast<unsigned long long>(info.usage), rect.left, rect.top,
            rect.right, rect.bottom, info.width, info.height);
    }
    return status;
  }
  status_t lock(buffer_handle_t, uint64_t, const Rect &, int fence,
                android_ycbcr *) const override {
    auto acquire = sp<Fence>::make(fence);
    return INVALID_OPERATION;
  }
  int unlock(buffer_handle_t handle) const override {
    auto status = azk_graphics_unlock(handle);
    ALOGE_IF(status, "host gralloc unlock failed: %d", status);
    // GBM CPU unmap completes the CPU transfer synchronously.
    return -1;
  }
  status_t isSupported(uint32_t w, uint32_t h, PixelFormat f, uint32_t layers,
                       uint64_t usage, bool *out) const override {
    const int status = azk_graphics_supported(w, h, f, layers, usage);
    *out = status == 1;
    return status < 0 ? status : OK;
  }
  status_t getName(buffer_handle_t handle, std::string *out) const override {
    AzkGraphicsBufferInfo info{};
    auto status = azk_graphics_describe(handle, &info);
    if (!status)
      *out = "androlinzberg-gbm";
    return status;
  }
#define GETTER(name, type, expression)                                         \
  status_t name(buffer_handle_t h, type *out) const override {                 \
    AzkGraphicsBufferInfo info{};                                              \
    auto status = azk_graphics_describe(h, &info);                             \
    if (!status)                                                               \
      *out = (expression);                                                     \
    return status;                                                             \
  }
  GETTER(getBufferId, uint64_t, info.id)
  GETTER(getWidth, uint64_t, info.width)
  GETTER(getHeight, uint64_t, info.height)
  GETTER(getLayerCount, uint64_t, info.layers)
  GETTER(getUsage, uint64_t, info.usage)
  GETTER(getPixelFormatRequested, ui::PixelFormat,
         static_cast<ui::PixelFormat>(info.format))
  GETTER(getPixelFormatFourCC, uint32_t, info.drm_format)
  GETTER(getPixelFormatModifier, uint64_t, info.modifier)
  GETTER(getProtectedContent, uint64_t, 0)
  GETTER(getDataspace, ui::Dataspace,
         static_cast<ui::Dataspace>(info.dataspace))
#undef GETTER
  status_t setDataspace(buffer_handle_t handle,
                        ui::Dataspace dataspace) const override {
    return azk_graphics_dataspace(handle, static_cast<int32_t>(dataspace));
  }
  status_t getPlaneLayouts(buffer_handle_t handle,
                           std::vector<ui::PlaneLayout> *out) const override {
    AzkGraphicsBufferInfo info{};
    const auto status = azk_graphics_describe(handle, &info);
    if (status)
      return status;
    // CPU layout metadata is defined here only for linear RGB buffers.
    if (info.modifier != 0)
      return INVALID_OPERATION;
    ui::PlaneLayout plane;
    plane.offsetInBytes = info.offset;
    plane.sampleIncrementInBits = bytesPerPixel(info.format) * 8;
    plane.strideInBytes = info.stride;
    plane.widthInSamples = info.width;
    plane.heightInSamples = info.height;
    plane.totalSizeInBytes = uint64_t(info.stride) * info.height;
    plane.horizontalSubsampling = plane.verticalSubsampling = 1;
    using Component =
        aidl::android::hardware::graphics::common::PlaneLayoutComponentType;
    auto component = [&](Component type, int64_t offset, int64_t bits) {
      aidl::android::hardware::graphics::common::PlaneLayoutComponent value;
      value.type.name =
          "android.hardware.graphics.common.PlaneLayoutComponentType";
      value.type.value = static_cast<int64_t>(type);
      value.offsetInBits = offset;
      value.sizeInBits = bits;
      plane.components.push_back(std::move(value));
    };
    switch (info.format) {
    case PIXEL_FORMAT_RGBA_8888:
    case PIXEL_FORMAT_RGBX_8888:
      component(Component::R, 0, 8);
      component(Component::G, 8, 8);
      component(Component::B, 16, 8);
      if (info.format == PIXEL_FORMAT_RGBA_8888)
        component(Component::A, 24, 8);
      break;
    case PIXEL_FORMAT_BGRA_8888:
      component(Component::B, 0, 8);
      component(Component::G, 8, 8);
      component(Component::R, 16, 8);
      component(Component::A, 24, 8);
      break;
    case PIXEL_FORMAT_RGB_565:
      component(Component::B, 0, 5);
      component(Component::G, 5, 6);
      component(Component::R, 11, 5);
      break;
    case PIXEL_FORMAT_RGBA_FP16:
      component(Component::R, 0, 16);
      component(Component::G, 16, 16);
      component(Component::B, 32, 16);
      component(Component::A, 48, 16);
      break;
    case PIXEL_FORMAT_RGBA_1010102:
      component(Component::R, 0, 10);
      component(Component::G, 10, 10);
      component(Component::B, 20, 10);
      component(Component::A, 30, 2);
      break;
    default:
      return INVALID_OPERATION;
    }
    *out = {std::move(plane)};
    return OK;
  }
};
class Allocator final : public GrallocAllocator {
public:
  bool isLoaded() const override { return true; }
  std::string dumpDebugInfo(bool) const override {
    return "host GBM live buffers: " +
           std::to_string(azk_graphics_live_buffers()) + "\n";
  }
  status_t allocate(std::string, uint32_t w, uint32_t h, PixelFormat f,
                    uint32_t layers, uint64_t usage, uint32_t *stride,
                    buffer_handle_t *output, bool import) const override {
    *output = nullptr;
    *stride = 0;
    native_handle_t *handle = nullptr;
    auto status = azk_graphics_allocate(w, h, f, layers, usage, &handle);
    if (status)
      return status;
    AzkGraphicsBufferInfo info{};
    status = azk_graphics_describe(handle, &info);
    if (status) {
      azk_graphics_free(handle);
      return status;
    }
    *stride = info.stride / bytesPerPixel(f);
    if (import) {
      *output = handle;
      return OK;
    }
    // AOSP allocateRawHandle requires an ordinary caller-owned native
    // handle, with no mapper import retained by this process.
    *output = native_handle_clone(handle);
    azk_graphics_free(handle);
    return *output ? OK : NO_MEMORY;
  }
};
} // namespace
std::unique_ptr<const GrallocMapper> createGrallocMapper() {
  return std::make_unique<Mapper>();
}
std::unique_ptr<const GrallocAllocator> createGrallocAllocator() {
  return std::make_unique<Allocator>();
}
} // namespace android::azk
