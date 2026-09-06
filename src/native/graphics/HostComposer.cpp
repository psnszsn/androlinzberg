// Typed AOSP ComposerHal adapter. Linux state and the software vsync worker
// live in host_composer.zig. GraphicBuffer/Fence strong references stay here.
// The logical display has no physical scanout; task outputs use SF's GPU
// virtual displays and the Wayland BufferQueue consumer.
#define LOG_TAG "HostComposer"
#include <azk/host_composer.h>
#include "DisplayHardware/ComposerHal.h"
#include "DisplayHardware/HWC2.h"
#include <array>
#include <atomic>
#include <mutex>
#include <fcntl.h>
#include <log/log.h>

namespace android::Hwc2 {
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
    return error(azk_host_composer_layer_destroy(state_, layer));
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
    if (display != 1)
      return Error::BAD_DISPLAY;
    if (slot >= targets_.size() || hdrSdrRatio != 1.0f)
      return Error::BAD_PARAMETER;
    std::lock_guard lock(targetMutex_);
    auto buffer = target ? target : targets_[slot];
    if (!buffer || buffer->initCheck() != OK ||
        buffer->getWidth() != uint32_t(width_) ||
        buffer->getHeight() != uint32_t(height_))
      return Error::BAD_PARAMETER;
    if (buffer->getUsage() & GraphicBuffer::USAGE_PROTECTED)
      return Error::UNSUPPORTED;
    // Fence::dup in the pinned API duplicates a borrowed FD without consuming
    // it.
    sp<Fence> fence = Fence::NO_FENCE;
    if (acquireFence >= 0) {
      int owned = ::fcntl(acquireFence, F_DUPFD_CLOEXEC, 0);
      if (owned < 0)
        return Error::NO_RESOURCES;
      fence = sp<Fence>::make(owned);
    }
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
  Error setCursorPosition(Display display, Layer layer, int32_t x,
                          int32_t y) override {
    if (display != 1)
      return Error::BAD_DISPLAY;
    return Error::UNSUPPORTED;
  }
  Error setLayerBuffer(Display display, Layer layer, uint32_t slot,
                       const sp<GraphicBuffer> &buffer,
                       int acquireFence) override {
    return layerStatus(display, layer, false);
  }
  Error setLayerBufferSlotsToClear(Display display, Layer layer,
                                   const std::vector<uint32_t> &slotsToClear,
                                   uint32_t activeBufferSlot) override {
    return layerStatus(display, layer, false);
  }
  Error setLayerSurfaceDamage(
      Display display, Layer layer,
      const std::vector<IComposerClient::Rect> &damage) override {
    return layerStatus(display, layer, false);
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
    return error(azk_host_composer_layer_composition(
        state_, layer, static_cast<int32_t>(type)));
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
    return V2_4::Error::UNSUPPORTED;
  }
  V2_4::Error getLayerGenericMetadataKeys(
      std::vector<IComposerClient::LayerGenericMetadataKey> *outKeys) override {
    outKeys->clear();
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
  AzkHostComposer *state_ = nullptr;
  int32_t width_, height_, period_;
  std::atomic<HWC2::ComposerCallback *> callback_{nullptr};
  std::mutex targetMutex_;
  std::array<sp<GraphicBuffer>, 64> targets_;
  sp<GraphicBuffer> currentTarget_;
  sp<Fence> currentFence_ = Fence::NO_FENCE;
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
  auto composer = createHostComposer(1280, 720, 16666667);
  LOG_ALWAYS_FATAL_IF(!composer, "Cannot create host composer clock");
  return composer;
}
} // namespace android::Hwc2
