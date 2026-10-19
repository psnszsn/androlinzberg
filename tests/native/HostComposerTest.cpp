// Public ComposerHal contracts for the Zig host backend, including a real GPU
// client target and a callback which reenters the composer from its clock.
#define LOG_TAG "HostComposerTest"
#include <azk/host_composer.h>
#include "DisplayHardware/ComposerHal.h"
#include "DisplayHardware/HWC2.h"
#include "GpuBufferRenderer.h"
#include <cassert>
#include <cstring>
#include <condition_variable>
#include <cerrno>
#include <fcntl.h>
#include <mutex>
#include <iostream>

using namespace android;
using namespace android::Hwc2;
using namespace std::chrono_literals;
namespace hal = android::hardware::graphics::composer::hal;
using HWC2::DisplayHotplugEvent;
using HWC2::HdcpLevels;
using HWC2::RefreshRateChangedDebugData;

struct Callback final : HWC2::ComposerCallback {
  Composer *composer;
  std::mutex mutex;
  std::condition_variable changed;
  unsigned hotplugs = 0, pulses = 0;
  int64_t last = 0;
  explicit Callback(Composer *value) : composer(value) {}
  void onComposerHalHotplugEvent(hal::HWDisplayId id,
                                 DisplayHotplugEvent event) override {
    assert(id == 1 && event == DisplayHotplugEvent::CONNECTED);
    ++hotplugs;
  }
  void
  onComposerHalVsync(hal::HWDisplayId id, nsecs_t timestamp,
                     std::optional<hal::VsyncPeriodNanos> period) override {
    assert(id == 1 && period == 5'000'000);
    assert(composer->setVsyncEnabled(1, IComposerClient::Vsync::DISABLE) ==
           Error::NONE);
    std::lock_guard lock(mutex);
    assert(timestamp > last);
    last = timestamp;
    ++pulses;
    changed.notify_all();
  }
  void wait(unsigned count) {
    std::unique_lock lock(mutex);
    assert(changed.wait_for(lock, 2s, [&] { return pulses == count; }));
  }
  void onComposerHalRefresh(hal::HWDisplayId) override { assert(false); }
  void onComposerHalVsyncPeriodTimingChanged(
      hal::HWDisplayId, const hal::VsyncPeriodChangeTimeline &) override {
    assert(false);
  }
  void onComposerHalSeamlessPossible(hal::HWDisplayId) override {
    assert(false);
  }
  void onComposerHalVsyncIdle(hal::HWDisplayId) override { assert(false); }
  void onRefreshRateChangedDebug(const RefreshRateChangedDebugData &) override {
    assert(false);
  }
  void onComposerHalHdcpLevelsChanged(hal::HWDisplayId,
                                      const HdcpLevels &) override {
    assert(false);
  }
};

int main() {
  const auto baseline = azk_graphics_live_buffers();
  {
    auto composer = createHostComposer(64, 48, 5'000'000);
    assert(composer);
    Callback callback(composer.get());
    composer->registerCallback(callback);
    assert(callback.hotplugs == 1);
    assert(!composer->isVrrSupported());
    assert(composer->getMaxVirtualDisplayCount() == 0);
    const auto capabilities = composer->getCapabilities();
    assert(capabilities.size() == 1 &&
           capabilities[0] == V3_0::Capability::PRESENT_FENCE_IS_NOT_RELIABLE);
    int32_t width = 0;
    assert(composer->getDisplayAttribute(1, 0,
                                         IComposerClient::Attribute::WIDTH,
                                         &width) == Error::NONE &&
           width == 64);
    assert(composer->getDisplayAttribute(2, 0,
                                         IComposerClient::Attribute::WIDTH,
                                         &width) == Error::BAD_DISPLAY);
    assert(composer->getDisplayAttribute(1, 9,
                                         IComposerClient::Attribute::WIDTH,
                                         &width) == Error::BAD_CONFIG);
    assert(composer->setDisplayBrightness(1, 0.5, 100, {}) ==
           Error::UNSUPPORTED);
    assert(composer->acceptDisplayChanges(1) == Error::NOT_VALIDATED);
    // SF can commit an empty logical display before allocating its first GPU
    // target (task mirrors may already be active on private outputs).
    int emptyFence = -2;
    uint32_t emptyChanges = 1, emptyRequests = 1;
    assert(composer->presentDisplay(1, &emptyFence) == Error::NOT_VALIDATED);
    assert(composer->validateDisplay(1, 0, 5'000'000, &emptyChanges,
                                      &emptyRequests) == Error::NONE);
    assert(emptyChanges == 0 && emptyRequests == 0);
    assert(composer->presentDisplay(1, &emptyFence) == Error::NONE && emptyFence == -1);
    Layer layer;
    assert(composer->createLayer(1, &layer) == Error::NONE);
    assert(composer->setLayerCompositionType(
               1, layer, V3_0::Composition::SIDEBAND) == Error::UNSUPPORTED);
    assert(composer->setLayerCompositionType(
               1, layer, V3_0::Composition::DEVICE) == Error::NONE);
    assert(composer->setLayerBrightness(1, layer, 1.f) == Error::NONE);
    assert(composer->setLayerBrightness(1, layer, 0.5f) == Error::UNSUPPORTED);
    assert(composer->setLayerBrightness(1, layer + 1000, 1.f) ==
           Error::BAD_LAYER);
    std::vector<Layer> lutLayers{layer};
    std::vector<V3_0::DisplayLuts::LayerLut> luts(1);
    assert(composer->getRequestedLuts(1, &lutLayers, &luts) == Error::NONE);
    assert(lutLayers.empty() && luts.empty());
    assert(composer->getRequestedLuts(2, &lutLayers, &luts) ==
           Error::BAD_DISPLAY);
    uint32_t count = 0, requests = 1;
    assert(hasChangesError(
        composer->validateDisplay(1, 0, 5'000'000, &count, &requests)));
    assert(count == 1 && requests == 0);
    std::vector<Layer> layers;
    std::vector<V3_0::Composition> types;
    assert(composer->getChangedCompositionTypes(1, &layers, &types) ==
           Error::NONE);
    assert(layers == std::vector<Layer>{layer} &&
           types == std::vector<V3_0::Composition>{V3_0::Composition::CLIENT});
    assert(composer->acceptDisplayChanges(1) == Error::NONE);
    assert(composer->presentDisplay(1, &emptyFence) == Error::BAD_PARAMETER);

    GpuBufferRenderer renderer;
    auto buffer = sp<GraphicBuffer>::make(64, 48, PIXEL_FORMAT_RGBA_8888, 1,
                                          GRALLOC_USAGE_HW_RENDER |
                                              GRALLOC_USAGE_HW_TEXTURE,
                                          "HostComposerTarget");
    assert(buffer->initCheck() == OK);
    auto acquire = renderer.render(buffer, 0xff123456);
    assert(acquire->isValid());
    // Like HWC2::Display::setClientTarget, hand over a duplicate; the composer
    // owns it on every path, including rejected calls.
    int handed = acquire->dup();
    assert(handed >= 0);
    assert(composer->setClientTarget(1, 64, buffer, handed, Dataspace::SRGB,
                                     {}, 1) == Error::BAD_PARAMETER);
    assert(fcntl(handed, F_GETFD) < 0 && errno == EBADF);
    assert(composer->setClientTarget(1, 3, nullptr, -1, Dataspace::SRGB, {},
                                     1) == Error::BAD_PARAMETER);
    const int layerHanded = acquire->dup();
    assert(layerHanded >= 0);
    assert(composer->setLayerBuffer(1, 0, 0, buffer, layerHanded) !=
           Error::NONE);
    assert(fcntl(layerHanded, F_GETFD) < 0 && errno == EBADF);
    handed = acquire->dup();
    assert(handed >= 0);
    assert(composer->setClientTarget(1, 3, buffer, handed, Dataspace::SRGB,
                                     {}, 1) == Error::NONE);
    // Accepted: the composer keeps the fence until the target is replaced.
    assert(fcntl(handed, F_GETFD) >= 0);
    acquire.clear();
    buffer.clear();
    int completed = -1;
    assert(composer->presentDisplay(1, &completed) == Error::NONE &&
           completed >= 0);
    assert(sp<Fence>::make(completed)->wait(5000) == OK);
    assert(composer->presentDisplay(1, &completed) == Error::NOT_VALIDATED);
    // Reuse the retained slot after releasing the caller's GraphicBuffer.
    assert(composer->setClientTarget(1, 3, nullptr, -1, Dataspace::SRGB, {},
                                     1) == Error::NONE);
    // The replaced target's fence is closed with it.
    assert(fcntl(handed, F_GETFD) < 0 && errno == EBADF);
    assert(composer->validateDisplay(1, 0, 5'000'000, &count, &requests) ==
               Error::NONE &&
           count == 0);
    assert(composer->presentDisplay(1, &completed) == Error::NONE &&
           completed == -1);
    std::vector<int> fences;
    assert(composer->getReleaseFences(1, &layers, &fences) == Error::NONE &&
           layers.empty() && fences.empty());
    assert(composer->destroyLayer(1, layer) == Error::NONE);
    assert(composer->destroyLayer(1, layer) == Error::BAD_LAYER);
    // The cursor layer stays device-composited (the cursor plane), and the
    // CursorInfo metadata SurfaceFlinger forwards for it is advertised.
    std::vector<IComposerClient::LayerGenericMetadataKey> keys;
    assert(composer->getLayerGenericMetadataKeys(&keys) == V2_4::Error::NONE &&
           keys.size() == 1 &&
           keys[0].name == "org.chromium.arc.V1_0.CursorInfo" &&
           !keys[0].mandatory);
    Layer cursor;
    assert(composer->createLayer(1, &cursor) == Error::NONE);
    assert(composer->setLayerCompositionType(
               1, cursor, V3_0::Composition::CURSOR) == Error::NONE);
    std::vector<uint8_t> info(16, 0);
    const float hotspot[2] = {2.f, 3.f};
    std::memcpy(info.data() + 4, hotspot, sizeof(hotspot));
    assert(composer->setLayerGenericMetadata(
               1, cursor, "org.chromium.arc.V1_0.CursorInfo", false, info) ==
           V2_4::Error::NONE);
    assert(composer->setLayerGenericMetadata(
               1, cursor, "org.chromium.arc.V1_0.CursorInfo", false, {1, 2}) ==
           V2_4::Error::BAD_PARAMETER);
    assert(composer->setLayerGenericMetadata(1, cursor, "org.example.Other",
                                             false, info) ==
           V2_4::Error::UNSUPPORTED);
    assert(composer->validateDisplay(1, 0, 5'000'000, &count, &requests) ==
               Error::NONE &&
           count == 0);
    assert(composer->destroyLayer(1, cursor) == Error::NONE);
    assert(composer->getChangedCompositionTypes(1, &layers, &types) ==
           Error::NOT_VALIDATED);
    for (unsigned i = 0; i < 1024; ++i) {
      Layer id;
      assert(composer->createLayer(1, &id) == Error::NONE && id > layer);
      layers.push_back(id);
    }
    assert(composer->createLayer(1, &layer) == Error::NO_RESOURCES);
    for (Layer id : layers)
      assert(composer->destroyLayer(1, id) == Error::NONE);

    for (unsigned count = 1; count <= 3; ++count) {
      assert(composer->setVsyncEnabled(1, IComposerClient::Vsync::ENABLE) ==
             Error::NONE);
      callback.wait(count);
    }
    assert(composer->setPowerMode(1, IComposerClient::PowerMode::OFF) ==
           Error::NONE);
    assert(composer->setVsyncEnabled(1, IComposerClient::Vsync::ENABLE) ==
           Error::NONE);
    {
      std::unique_lock lock(callback.mutex);
      assert(!callback.changed.wait_for(lock, 30ms,
                                        [&] { return callback.pulses > 3; }));
    }
    assert(composer->setPowerMode(1, IComposerClient::PowerMode::ON) ==
           Error::NONE);
    callback.wait(4);
    // Destroy while callbacks are enabled; joining must precede callback death.
    assert(composer->setVsyncEnabled(1, IComposerClient::Vsync::ENABLE) ==
           Error::NONE);
    composer.reset();
  }
  {
    // The synthetic desktop's mode determines layer visibility; the stock
    // max_graphics properties keep its actual client framebuffer small.
    auto composer = createHostComposer(16384, 16384, 5'000'000);
    assert(composer);
    Layer layer;
    assert(composer->createLayer(1, &layer) == Error::NONE);
    GpuBufferRenderer renderer;
    auto buffer = sp<GraphicBuffer>::make(720, 720, PIXEL_FORMAT_RGBA_8888, 1,
        GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE, "BoundedDesktopTarget");
    assert(buffer->initCheck() == OK);
    auto acquire = renderer.render(buffer, 0xff123456);
    assert(acquire->isValid());
    assert(composer->setClientTarget(1, 0, buffer, acquire->dup(), Dataspace::SRGB,
                                     {}, 1) == Error::NONE);
    uint32_t count = 0, requests = 0;
    assert(composer->validateDisplay(1, 0, 5'000'000, &count, &requests) == Error::NONE);
    int completed = -1;
    assert(composer->presentDisplay(1, &completed) == Error::NONE && completed >= 0);
    assert(sp<Fence>::make(completed)->wait(5000) == OK);
    assert(composer->setClientTarget(1, 0, nullptr, -1, Dataspace::SRGB, {}, 1) == Error::NONE);
  }
  assert(azk_graphics_live_buffers() == baseline);
  std::cout << "Host composer: client validation, bounded layers, GPU "
               "fence/slot ownership, reentrant vsync and shutdown passed\n";
}
