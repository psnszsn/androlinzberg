// Typed AOSP ComposerHal adapter. Linux state and the software vsync worker
// live in host_composer.zig. GraphicBuffer/Fence strong references stay here.
// The logical display has no physical scanout; task outputs use SF's GPU
// virtual displays and the Wayland BufferQueue consumer.
//
// The one device-composited layer is the cursor: stock PointerController's
// sprite is a cursor-flagged layer, which a device composer scans out on its
// cursor plane. Here the plane is the Wayland seat's cursor surface
// (cursor_plane.zig). As for ARC++, SpriteController publishes the icon's
// hotspot as CursorInfo layer metadata, which this composer advertises.
#define LOG_TAG "HostComposer"
#include <azk/host_composer.h>
#include "DisplayHardware/ComposerHal.h"
#include "DisplayHardware/HWC2.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <optional>
#include <vector>
#include <fcntl.h>
#include <log/log.h>

extern "C" void azk_host_composer_cursor(int32_t hotspotX, int32_t hotspotY, int32_t width,
                                         int32_t height, const int32_t *pixels, size_t count);

namespace android::Hwc2 {
// SurfaceFlinger::getGenericLayerMetadataKeyMap's name for METADATA_MOUSE_CURSOR.
static constexpr char kCursorInfo[] = "org.chromium.arc.V1_0.CursorInfo";
static_assert(int(V3_0::Composition::CLIENT) == 1);
static_assert(int(V3_0::Composition::CURSOR) == 4);
static_assert(int(V3_0::Composition::SIDEBAND) == 5);
static_assert(int(V3_0::Composition::DISPLAY_DECORATION) == 6);
static_assert(int(V3_0::Composition::REFRESH_RATE_INDICATOR) == 7);
class HostComposer final : public Composer {
public:
  HostComposer(int32_t width, int32_t height, int32_t period)
      : width_(width), height_(height), period_(period) {
    state_ = azk_host_composer_create(period, pulse, this);
  }
  ~HostComposer() override {
    if (state_)
      azk_host_composer_destroy(state_);
  }
  bool ready() const { return state_ != nullptr; }
  bool isSupported(OptionalFeature) const override { return false; }
  bool isVrrSupported() const override { return false; }
  std::vector<aidl::android::hardware::graphics::composer3::Capability>
  getCapabilities() override {
    return {V3_0::Capability::PRESENT_FENCE_IS_NOT_RELIABLE};
  }
  std::string dumpDebugInfo() override {
    return "AZKB host composer: GPU client composition, software vsync, no "
           "physical scanout\n";
  }
  void registerCallback(HWC2::ComposerCallback &callback) override {
    callback_ = &callback;
    callback.onComposerHalHotplugEvent(
        1, aidl::android::hardware::graphics::common::DisplayHotplugEvent::
               CONNECTED);
  }
  Error executeCommands(Display display) override { return valid(display); }
  uint32_t getMaxVirtualDisplayCount() override { return 0; }
  Error createVirtualDisplay(uint32_t width, uint32_t height, PixelFormat *,
                             Display *outDisplay) override {
    return Error::UNSUPPORTED;
  }
  Error destroyVirtualDisplay(Display display) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error acceptDisplayChanges(Display display) override {
    return valid(display) == Error::NONE
               ? error(azk_host_composer_accept(state_))
               : Error::BAD_DISPLAY;
  }
  Error createLayer(Display display, Layer *outLayer) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return error(azk_host_composer_layer_create(state_, outLayer));
  }
  Error destroyLayer(Display display, Layer layer) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    auto result = error(azk_host_composer_layer_destroy(state_, layer));
    if (result == Error::NONE) {
      std::lock_guard lock(layerMutex_);
      layers_.erase(layer);
    }
    return result;
  }
  Error getActiveConfig(Display display, Config *outConfig) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outConfig = 0;
    return Error::NONE;
  }
  Error getChangedCompositionTypes(
      Display display, std::vector<Layer> *outLayers,
      std::vector<V3_0::Composition> *outTypes) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    std::array<uint64_t, 1024> ids;
    uint32_t count = 0;
    auto result = error(
        azk_host_composer_changes(state_, ids.data(), ids.size(), &count));
    if (result != Error::NONE)
      return result;
    outLayers->assign(ids.begin(), ids.begin() + count);
    outTypes->assign(count, V3_0::Composition::CLIENT);
    return Error::NONE;
  }
  Error getColorModes(Display display,
                      std::vector<ColorMode> *outModes) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outModes = {ColorMode::NATIVE};
    return Error::NONE;
  }
  Error getDisplayAttribute(Display display, Config config,
                            IComposerClient::Attribute attribute,
                            int32_t *outValue) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (config != 0)
      return Error::BAD_CONFIG;
    switch (attribute) {
    case IComposerClient::Attribute::WIDTH:
      *outValue = width_;
      break;
    case IComposerClient::Attribute::HEIGHT:
      *outValue = height_;
      break;
    case IComposerClient::Attribute::VSYNC_PERIOD:
      *outValue = period_;
      break;
    case IComposerClient::Attribute::DPI_X:
    case IComposerClient::Attribute::DPI_Y:
      *outValue = 160000;
      break;
    case IComposerClient::Attribute::CONFIG_GROUP:
      *outValue = 0;
      break;
    default:
      return Error::BAD_PARAMETER;
    }
    return Error::NONE;
  }
  Error getDisplayConfigs(Display display,
                          std::vector<Config> *outConfigs) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outConfigs = {0};
    return Error::NONE;
  }
  Error getDisplayConfigurations(
      Display display, int32_t maxFrameIntervalNs,
      std::vector<DisplayConfiguration> *configs) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    DisplayConfiguration config;
    config.configId = 0;
    config.width = width_;
    config.height = height_;
    config.vsyncPeriod = period_;
    config.configGroup = 0;
    config.dpi = DisplayConfiguration::Dpi{160, 160};
    *configs = {config};
    return Error::NONE;
  }
  Error getDisplayName(Display display, std::string *outName) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outName = "Hosted Android display";
    return Error::NONE;
  }
  Error
  getDisplayRequests(Display display, uint32_t *outDisplayRequestMask,
                     std::vector<Layer> *outLayers,
                     std::vector<uint32_t> *outLayerRequestMasks) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outDisplayRequestMask = 0;
    outLayers->clear();
    outLayerRequestMasks->clear();
    return Error::NONE;
  }
  Error getDozeSupport(Display display, bool *outSupport) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outSupport = false;
    return Error::NONE;
  }
  Error hasDisplayIdleTimerCapability(Display display,
                                      bool *outSupport) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outSupport = false;
    return Error::NONE;
  }
  Error getHdrCapabilities(Display display, std::vector<Hdr> *outHdrTypes,
                           float *outMaxLuminance,
                           float *outMaxAverageLuminance,
                           float *outMinLuminance) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    outHdrTypes->clear();
    *outMaxLuminance = *outMaxAverageLuminance = *outMinLuminance = 0;
    return Error::NONE;
  }
  Error getReleaseFences(Display display, std::vector<Layer> *outLayers,
                         std::vector<int> *outReleaseFences) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    outLayers->clear();
    outReleaseFences->clear();
    return Error::NONE;
  }
  Error
  getLayerPresentFences(Display display, std::vector<Layer> *outLayers,
                        std::vector<int> *outFences,
                        std::vector<int64_t> *outLatenciesNanos) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error presentDisplay(Display display, int *outPresentFence) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    std::lock_guard lock(targetMutex_);
    int fence = currentFence_->dup();
    if (currentFence_->isValid() && fence < 0)
      return Error::NO_RESOURCES;
    auto result = error(azk_host_composer_present(state_, currentTarget_ != nullptr));
    if (result != Error::NONE) {
      if (fence >= 0)
        sp<Fence>::make(fence).clear();
      return result;
    }
    // No physical scanout occurs here. This is only the GPU completion fence;
    // PRESENT_FENCE_IS_NOT_RELIABLE keeps it out of presentation accounting.
    *outPresentFence = fence;
    presentCursor();
    return Error::NONE;
  }
  Error setActiveConfig(Display display, Config config) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return config == 0 ? Error::NONE : Error::BAD_CONFIG;
  }
  Error setClientTarget(Display display, uint32_t slot,
                        const sp<GraphicBuffer> &target, int acquireFence,
                        Dataspace dataspace,
                        const std::vector<IComposerClient::Rect> &damage,
                        float hdrSdrRatio) override {
    // HWC2::Display::setClientTarget hands over Fence::dup() of the client
    // target's acquire fence, and a composer HAL owns every fence FD it is
    // given (HidlComposer's command writer closes them). Adopt it on every
    // path, including errors, or SurfaceFlinger leaks one sync_file per
    // composited frame.
    sp<Fence> fence =
        acquireFence >= 0 ? sp<Fence>::make(acquireFence) : Fence::NO_FENCE;
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (slot >= targets_.size() || hdrSdrRatio != 1.0f)
      return Error::BAD_PARAMETER;
    std::lock_guard lock(targetMutex_);
    auto buffer = target ? target : targets_[slot];
    // FramebufferSurface applies max_graphics_width/height independently of
    // the display mode. A smaller client target still covers the whole mode;
    // the host consumes its fence without scanning its pixels out.
    if (!buffer || buffer->initCheck() != OK ||
        buffer->getWidth() == 0 || buffer->getWidth() > uint32_t(width_) ||
        buffer->getHeight() == 0 || buffer->getHeight() > uint32_t(height_))
      return Error::BAD_PARAMETER;
    if (buffer->getUsage() & GraphicBuffer::USAGE_PROTECTED)
      return Error::UNSUPPORTED;
    targets_[slot] = buffer;
    currentTarget_ = buffer;
    currentFence_ = fence;
    return Error::NONE;
  }
  Error setColorMode(Display display, ColorMode mode,
                     RenderIntent renderIntent) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return mode == ColorMode::NATIVE &&
                   renderIntent == RenderIntent::COLORIMETRIC
               ? Error::NONE
               : Error::UNSUPPORTED;
  }
  Error setColorTransform(Display display, const float *matrix) override {
    return valid(display);
  }
  Error setOutputBuffer(Display display, const native_handle_t *buffer,
                        int releaseFence) override {
    if (releaseFence >= 0)
      sp<Fence>::make(releaseFence).clear();  // owned, like every HAL fence
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setPowerMode(Display display,
                     IComposerClient::PowerMode mode) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (mode != IComposerClient::PowerMode::ON &&
        mode != IComposerClient::PowerMode::OFF)
      return Error::UNSUPPORTED;
    azk_host_composer_power(state_, mode == IComposerClient::PowerMode::ON);
    return Error::NONE;
  }
  Error setVsyncEnabled(Display display,
                        IComposerClient::Vsync enabled) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (enabled != IComposerClient::Vsync::ENABLE &&
        enabled != IComposerClient::Vsync::DISABLE)
      return Error::BAD_PARAMETER;
    azk_host_composer_vsync(state_, enabled == IComposerClient::Vsync::ENABLE);
    return Error::NONE;
  }
  Error setClientTargetSlotCount(Display display) override {
    return valid(display);
  }
  Error validateDisplay(Display display, nsecs_t expectedPresentTime,
                        int32_t frameIntervalNs, uint32_t *outNumTypes,
                        uint32_t *outNumRequests) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outNumRequests = 0;
    return error(azk_host_composer_validate(state_, outNumTypes));
  }
  Error presentOrValidateDisplay(Display display, nsecs_t expectedPresentTime,
                                 int32_t frameIntervalNs, uint32_t *outNumTypes,
                                 uint32_t *outNumRequests, int *outPresentFence,
                                 uint32_t *state) override {
    *outPresentFence = -1;
    *state = 0;
    return validateDisplay(display, expectedPresentTime, frameIntervalNs,
                           outNumTypes, outNumRequests);
  }
  // The Wayland compositor places its cursor surface at its own pointer, so
  // the plane's position carries nothing the presenter uses.
  Error setCursorPosition(Display display, Layer layer, int32_t x,
                          int32_t y) override {
    return layerStatus(display, layer, false);
  }
  Error setLayerBuffer(Display display, Layer layer, uint32_t slot,
                       const sp<GraphicBuffer> &buffer,
                       int acquireFence) override {
    // Owned, like every HAL fence.
    sp<Fence> fence =
        acquireFence >= 0 ? sp<Fence>::make(acquireFence) : Fence::NO_FENCE;
    auto result = layerStatus(display, layer, false);
    if (result != Error::NONE)
      return result;
    std::lock_guard lock(layerMutex_);
    auto &state = layers_[layer];
    // A null buffer selects the composer's cached buffer in that slot.
    if (buffer)
      state.slots[slot] = buffer;
    auto cached = state.slots.find(slot);
    sp<GraphicBuffer> current =
        cached == state.slots.end() ? nullptr : cached->second;
    if (current != state.buffer)
      state.dirty = true;
    state.buffer = current;
    state.fence = fence;
    return Error::NONE;
  }
  Error setLayerBufferSlotsToClear(Display display, Layer layer,
                                   const std::vector<uint32_t> &slotsToClear,
                                   uint32_t activeBufferSlot) override {
    auto result = layerStatus(display, layer, false);
    if (result != Error::NONE)
      return result;
    std::lock_guard lock(layerMutex_);
    auto &state = layers_[layer];
    for (uint32_t slot : slotsToClear)
      if (slot != activeBufferSlot)
        state.slots.erase(slot);
    return Error::NONE;
  }
  Error setLayerSurfaceDamage(
      Display display, Layer layer,
      const std::vector<IComposerClient::Rect> &damage) override {
    auto result = layerStatus(display, layer, false);
    if (result != Error::NONE)
      return result;
    // HWC2: one empty rectangle means unchanged contents; none means unknown.
    const bool unchanged = damage.size() == 1 && damage[0].left == 0 &&
                           damage[0].top == 0 && damage[0].right == 0 &&
                           damage[0].bottom == 0;
    if (!unchanged) {
      std::lock_guard lock(layerMutex_);
      layers_[layer].dirty = true;
    }
    return Error::NONE;
  }
  Error setLayerBlendMode(Display display, Layer layer,
                          IComposerClient::BlendMode mode) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerColor(Display display, Layer layer,
                      const aidl::android::hardware::graphics::composer3::Color
                          &color) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerCompositionType(
      Display display, Layer layer,
      aidl::android::hardware::graphics::composer3::Composition type) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    auto result = error(azk_host_composer_layer_composition(
        state_, layer, static_cast<int32_t>(type)));
    if (result == Error::NONE) {
      std::lock_guard lock(layerMutex_);
      layers_[layer].cursor = type == V3_0::Composition::CURSOR;
    }
    return result;
  }
  Error setLayerDataspace(Display display, Layer layer,
                          Dataspace dataspace) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerDisplayFrame(Display display, Layer layer,
                             const IComposerClient::Rect &frame) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerPlaneAlpha(Display display, Layer layer, float alpha) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerSidebandStream(Display display, Layer layer,
                               const native_handle_t *stream) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setLayerSourceCrop(Display display, Layer layer,
                           const IComposerClient::FRect &crop) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerTransform(Display display, Layer layer,
                          Transform transform) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerVisibleRegion(
      Display display, Layer layer,
      const std::vector<IComposerClient::Rect> &visible) override {
    return layerStatus(display, layer, true);
  }
  Error setLayerZOrder(Display display, Layer layer, uint32_t z) override {
    return layerStatus(display, layer, true);
  }
  Error
  setLayerPerFrameMetadata(Display display, Layer layer,
                           const std::vector<IComposerClient::PerFrameMetadata>
                               &perFrameMetadatas) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  std::vector<IComposerClient::PerFrameMetadataKey>
  getPerFrameMetadataKeys(Display display) override {
    return {};
  }
  Error getRenderIntents(Display display, ColorMode colorMode,
                         std::vector<RenderIntent> *outRenderIntents) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (colorMode != ColorMode::NATIVE)
      return Error::UNSUPPORTED;
    *outRenderIntents = {RenderIntent::COLORIMETRIC};
    return Error::NONE;
  }
  Error getDataspaceSaturationMatrix(Dataspace dataspace,
                                     mat4 *outMatrix) override {
    return Error::UNSUPPORTED;
  }
  Error getDisplayIdentificationData(Display display, uint8_t *outPort,
                                     std::vector<uint8_t> *outData) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setLayerColorTransform(Display display, Layer layer,
                               const float *matrix) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error
  getDisplayedContentSamplingAttributes(Display display, PixelFormat *outFormat,
                                        Dataspace *outDataspace,
                                        uint8_t *outComponentMask) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setDisplayContentSamplingEnabled(Display display, bool enabled,
                                         uint8_t componentMask,
                                         uint64_t maxFrames) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getDisplayedContentSample(Display display, uint64_t maxFrames,
                                  uint64_t timestamp,
                                  DisplayedFrameStats *outStats) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setLayerPerFrameMetadataBlobs(
      Display display, Layer layer,
      const std::vector<PerFrameMetadataBlob> &metadata) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setDisplayBrightness(Display display, float brightness,
                             float brightnessNits,
                             const DisplayBrightnessOptions &options) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getDisplayCapabilities(
      Display display,
      std::vector<V3_0::DisplayCapability> *outCapabilities) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outCapabilities = {V3_0::DisplayCapability::SKIP_CLIENT_COLOR_TRANSFORM};
    return Error::NONE;
  }
  V2_4::Error getDisplayConnectionType(
      Display display,
      IComposerClient::DisplayConnectionType *outType) override {
    if (display != 1)
      return V2_4::Error::BAD_DISPLAY;
    *outType = IComposerClient::DisplayConnectionType::INTERNAL;
    return V2_4::Error::NONE;
  }
  V2_4::Error getDisplayVsyncPeriod(Display display,
                                    VsyncPeriodNanos *outVsyncPeriod) override {
    if (display != 1)
      return V2_4::Error::BAD_DISPLAY;
    *outVsyncPeriod = period_;
    return V2_4::Error::NONE;
  }
  Error setActiveConfigWithConstraints(
      Display display, Config config,
      const IComposerClient::VsyncPeriodChangeConstraints
          &vsyncPeriodChangeConstraints,
      VsyncPeriodChangeTimeline *outTimeline) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  V2_4::Error setAutoLowLatencyMode(Display displayId, bool on) override {
    if (displayId != 1)
      return V2_4::Error::BAD_DISPLAY;
    return V2_4::Error::UNSUPPORTED;
  }
  V2_4::Error getSupportedContentTypes(Display displayId,
                                       std::vector<IComposerClient::ContentType>
                                           *outSupportedContentTypes) override {
    if (displayId != 1)
      return V2_4::Error::BAD_DISPLAY;
    outSupportedContentTypes->clear();
    return V2_4::Error::NONE;
  }
  V2_4::Error
  setContentType(Display displayId,
                 IComposerClient::ContentType contentType) override {
    if (displayId != 1)
      return V2_4::Error::BAD_DISPLAY;
    return V2_4::Error::UNSUPPORTED;
  }
  V2_4::Error
  setLayerGenericMetadata(Display display, Layer layer, const std::string &key,
                          bool mandatory,
                          const std::vector<uint8_t> &value) override {
    if (display != 1)
      return V2_4::Error::BAD_DISPLAY;
    if (key != kCursorInfo)
      return V2_4::Error::UNSUPPORTED;
    if (azk_host_composer_layer_check(state_, layer, false) != 0)
      return V2_4::Error::BAD_LAYER;
    // SpriteController's parcel: int32 style, float hotspot x, float hotspot
    // y, bool drop shadow. The drop shadow is already in the sprite's pixels.
    if (value.size() < 12)
      return V2_4::Error::BAD_PARAMETER;
    float hotspot[2];
    std::memcpy(hotspot, value.data() + 4, sizeof(hotspot));
    if (!std::isfinite(hotspot[0]) || !std::isfinite(hotspot[1]))
      return V2_4::Error::BAD_PARAMETER;
    std::lock_guard lock(layerMutex_);
    layers_[layer].hotspot = {static_cast<int32_t>(std::lround(hotspot[0])),
                              static_cast<int32_t>(std::lround(hotspot[1]))};
    return V2_4::Error::NONE;
  }
  V2_4::Error getLayerGenericMetadataKeys(
      std::vector<IComposerClient::LayerGenericMetadataKey> *outKeys) override {
    *outKeys = {{kCursorInfo, false}};
    return V2_4::Error::NONE;
  }
  Error getClientTargetProperty(Display display,
                                V3_0::ClientTargetPropertyWithBrightness
                                    *outClientTargetProperty) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    outClientTargetProperty->clientTargetProperty.pixelFormat =
        aidl::android::hardware::graphics::common::PixelFormat::RGBA_8888;
    outClientTargetProperty->clientTargetProperty.dataspace =
        aidl::android::hardware::graphics::common::Dataspace::SRGB;
    outClientTargetProperty->brightness = 1.0f;
    return Error::NONE;
  }
  Error setLayerBrightness(Display display, Layer layer,
                           float brightness) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    const auto result = layerStatus(display, layer, false);
    if (result != Error::NONE)
      return result;
    // Pinned HidlComposer treats this as a no-op. Unit brightness has no
    // effect on our client-composed layers; other values need GPU dimming.
    return brightness == 1.f ? Error::NONE : Error::UNSUPPORTED;
  }
  Error setLayerBlockingRegion(
      Display display, Layer layer,
      const std::vector<IComposerClient::Rect> &blocking) override {
    return layerStatus(display, layer, true);
  }
  Error setBootDisplayConfig(Display displayId, Config) override {
    if (displayId != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error clearBootDisplayConfig(Display displayId) override {
    if (displayId != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getPreferredBootDisplayConfig(Display displayId, Config *) override {
    if (displayId != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getDisplayDecorationSupport(
      Display display,
      std::optional<
          ::aidl::android::hardware::graphics::common::DisplayDecorationSupport>
          *support) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setIdleTimerEnabled(Display displayId,
                            std::chrono::milliseconds timeout) override {
    if (displayId != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error
  getPhysicalDisplayOrientation(Display displayId,
                                AidlTransform *outDisplayOrientation) override {
    if (displayId != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getOverlaySupport(V3_0::OverlayProperties *outProperties) override {
    return Error::UNSUPPORTED;
  }
  void onHotplugConnect(Display) override {}
  void onHotplugDisconnect(Display) override {}
  Error getHdrConversionCapabilities(
      std::vector<
          ::aidl::android::hardware::graphics::common::HdrConversionCapability>
          *) override {
    return Error::UNSUPPORTED;
  }
  Error setHdrConversionStrategy(
      ::aidl::android::hardware::graphics::common::HdrConversionStrategy,
      Hdr *) override {
    return Error::UNSUPPORTED;
  }
  Error setRefreshRateChangedCallbackDebugEnabled(Display, bool) override {
    return Error::UNSUPPORTED;
  }
  Error notifyExpectedPresent(Display, nsecs_t expectedPresentTime,
                              int32_t frameIntervalNs) override {
    return Error::UNSUPPORTED;
  }
  Error
  getRequestedLuts(Display display, std::vector<Layer> *outLayers,
                   std::vector<V3_0::DisplayLuts::LayerLut> *outLuts) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    // HWComposer::getDeviceCompositionChanges queries this unconditionally.
    // Like pinned HidlComposer, a client-composition backend requests no LUTs.
    outLayers->clear();
    outLuts->clear();
    return Error::NONE;
  }
  Error setLayerLuts(Display display, Layer layer, V3_0::Luts &luts) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getMaxLayerPictureProfiles(Display display,
                                   int32_t *outMaxProfiles) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    *outMaxProfiles = 0;
    return Error::NONE;
  }
  Error setDisplayPictureProfileId(Display display,
                                   PictureProfileId id) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setLayerPictureProfileId(Display display, Layer layer,
                                 PictureProfileId id) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error startHdcpNegotiation(
      Display display,
      const aidl::android::hardware::drm::HdcpLevels &levels) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error getLuts(Display display, const std::vector<sp<GraphicBuffer>> &,
                std::vector<V3_0::Luts> *) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }

private:
  static Error error(int32_t code) { return static_cast<Error>(code); }
  static Error valid(Display display) {
    return display == 1 ? Error::NONE : Error::BAD_DISPLAY;
  }
  Error layerStatus(Display display, Layer layer, bool geometry) {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return error(azk_host_composer_layer_check(state_, layer, geometry));
  }
  static void pulse(void *cookie, int64_t timestamp) {
    auto &self = *static_cast<HostComposer *>(cookie);
    if (auto *callback = self.callback_.load())
      callback->onComposerHalVsync(1, timestamp, self.period_);
  }
  struct LayerState {
    bool cursor = false;
    bool dirty = true;
    std::map<uint32_t, sp<GraphicBuffer>> slots;
    sp<GraphicBuffer> buffer;
    sp<Fence> fence = Fence::NO_FENCE;
    std::optional<std::array<int32_t, 2>> hotspot;
  };
  struct SentCursor {
    Layer layer;
    std::array<int32_t, 2> hotspot;
  };

  /** The cursor plane after a present: the sprite's pixels when its layer,
   * contents or hotspot changed, or an empty plane once it is gone. */
  void presentCursor() {
    std::lock_guard lock(layerMutex_);
    auto plane = std::find_if(layers_.begin(), layers_.end(), [](const auto &entry) {
      return entry.second.cursor && entry.second.buffer && entry.second.hotspot;
    });
    if (plane == layers_.end()) {
      if (sent_ && sent_->layer != 0) {
        azk_host_composer_cursor(0, 0, 0, 0, nullptr, 0);
        sent_ = SentCursor{0, {0, 0}};
      }
      return;
    }
    auto &[layer, state] = *plane;
    if (sent_ && sent_->layer == layer && sent_->hotspot == *state.hotspot &&
        !state.dirty)
      return;
    std::vector<int32_t> pixels;
    if (!readCursor(state, &pixels))
      return;
    state.dirty = false;
    azk_host_composer_cursor((*state.hotspot)[0], (*state.hotspot)[1],
                             int32_t(state.buffer->getWidth()),
                             int32_t(state.buffer->getHeight()), pixels.data(),
                             pixels.size());
    sent_ = SentCursor{layer, *state.hotspot};
  }
  /** Premultiplied ARGB words of the sprite's RGBA_8888 buffer. */
  static bool readCursor(const LayerState &state, std::vector<int32_t> *out) {
    const auto &buffer = state.buffer;
    if (buffer->getPixelFormat() != PIXEL_FORMAT_RGBA_8888 ||
        buffer->getWidth() == 0 || buffer->getWidth() > 256 ||
        buffer->getHeight() == 0 || buffer->getHeight() > 256) {
      ALOGW("Cursor plane cannot show a %ux%u buffer of format %d",
            buffer->getWidth(), buffer->getHeight(), buffer->getPixelFormat());
      return false;
    }
    if (state.fence->wait(Fence::TIMEOUT_NEVER) != OK)
      return false;
    void *data = nullptr;
    if (buffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &data) != OK)
      return false;
    const uint32_t width = buffer->getWidth(), height = buffer->getHeight();
    out->resize(size_t(width) * height);
    for (uint32_t y = 0; y < height; y++) {
      const auto *row = static_cast<const uint8_t *>(data) +
                        size_t(y) * buffer->getStride() * 4;
      for (uint32_t x = 0; x < width; x++) {
        const uint8_t *p = row + x * 4;
        (*out)[size_t(y) * width + x] =
            int32_t(uint32_t(p[3]) << 24 | uint32_t(p[0]) << 16 |
                    uint32_t(p[1]) << 8 | p[2]);
      }
    }
    buffer->unlock();
    return true;
  }

  AzkHostComposer *state_ = nullptr;
  int32_t width_, height_, period_;
  std::atomic<HWC2::ComposerCallback *> callback_{nullptr};
  std::mutex targetMutex_;
  std::array<sp<GraphicBuffer>, 64> targets_;
  sp<GraphicBuffer> currentTarget_;
  sp<Fence> currentFence_ = Fence::NO_FENCE;
  std::mutex layerMutex_;
  std::map<Layer, LayerState> layers_;
  // Layer 0 (never a composer layer) records an empty plane.
  std::optional<SentCursor> sent_;
};

// Pinned ComposerHal.cpp owns this destructor and selects the AIDL/HIDL HAL.
// The host build selects its local backend at that same factory boundary.
Composer::~Composer() = default;
std::unique_ptr<Composer> createHostComposer(int32_t width, int32_t height,
                                             int32_t period) {
  if (width <= 0 || height <= 0 || width > 16384 || height > 16384)
    return nullptr;
  auto composer = std::make_unique<HostComposer>(width, height, period);
  if (!composer->ready())
    return nullptr;
  return composer;
}
std::unique_ptr<Composer> Composer::create(const std::string &serviceName) {
  LOG_ALWAYS_FATAL_IF(serviceName != "default" && serviceName != "host",
                      "Unsupported host composer %s", serviceName.c_str());
  // SurfaceFlinger clips output layers, including the cursor, to this mode.
  // Task/input coordinates occupy TaskGeometry.DESKTOP_SIZE, so the desktop
  // mode must cover that same plane. The stock max_graphics_width/height
  // properties bound the primary framebuffer independently; task virtual
  // displays still render at their own full output resolution.
  const char *taskWindows = std::getenv("AZK_TASK_WINDOWS");
  const bool desktop = taskWindows && std::strcmp(taskWindows, "1") == 0;
  auto composer = createHostComposer(desktop ? 16384 : 1280,
                                     desktop ? 16384 : 720, 16666667);
  LOG_ALWAYS_FATAL_IF(!composer, "Cannot create host composer clock");
  return composer;
}
} // namespace android::Hwc2
