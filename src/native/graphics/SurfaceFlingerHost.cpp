// Host process startup follows main_surfaceflinger.cpp: initialize the pinned
// service before publishing its legacy and AIDL interfaces, then run its main
// loop. Graphics allocation and composer discovery use the local host backend.
#define LOG_TAG "SurfaceFlingerHost"
#include "SurfaceFlinger.h"
#include "../TestStackCapture.h"
#include "SurfaceFlingerDefaultFactory.h"
#include <compositionengine/CompositionRefreshArgs.h>
#include <compositionengine/impl/CompositionEngine.h>
#include "FrontEnd/LayerSnapshot.h"
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <log/log.h>
#include <cstdlib>

// Stock SurfaceFlinger::run has process lifetime; Android init stops the
// process without running its C++ global destructors. The host supervisor must
// preserve that lifecycle while SF's compositor/scheduler/Binder threads are
// still live.
extern "C" [[noreturn]] void azk_surfaceflinger_exit(int status) {
  std::_Exit(status);
}

namespace {
// The stock engine reports no features, so layer metadata never reaches the
// composer. A host OS composer reads the pointer sprite's CursorInfo (the
// ARC++ arrangement); its engine snapshots that metadata, as here.
//
// SpriteController sets CursorInfo on the sprite's SurfaceControl, whose
// buffers live on the BLAST child SurfaceControl::getSurface creates (with
// the cursor flag). Generic metadata covers only a layer's own requests, so
// the engine hands the composer the cursor layer's inherited CursorInfo.
class HostCompositionEngine final : public android::compositionengine::impl::CompositionEngine {
public:
  android::compositionengine::FeatureFlags getFeatureFlags() const override {
    return android::compositionengine::Feature::kSnapshotLayerMetadata;
  }
  void preComposition(android::compositionengine::CompositionRefreshArgs &args) override {
    for (const auto &layer : args.layers) {
      const auto *state = layer->getCompositionState();
      if (!state || state->compositionType !=
                        aidl::android::hardware::graphics::composer3::Composition::CURSOR)
        continue;
      // SurfaceFlinger's LayerFE composition state is its layer snapshot.
      auto &snapshot = const_cast<android::surfaceflinger::frontend::LayerSnapshot &>(
          static_cast<const android::surfaceflinger::frontend::LayerSnapshot &>(*state));
      const auto &inherited = snapshot.layerMetadata.mMap;
      auto cursor = inherited.find(android::gui::METADATA_MOUSE_CURSOR);
      if (cursor == inherited.end())
        continue;
      snapshot.metadata.insert_or_assign(
          "org.chromium.arc.V1_0.CursorInfo",
          android::compositionengine::GenericLayerMetadataEntry{false, cursor->second});
    }
    android::compositionengine::impl::CompositionEngine::preComposition(args);
  }
};
class HostFactory final : public android::surfaceflinger::DefaultFactory {
public:
  std::unique_ptr<android::compositionengine::CompositionEngine> createCompositionEngine() override {
    return std::make_unique<HostCompositionEngine>();
  }
};
} // namespace

extern "C" int azk_surfaceflinger_run(void (*ready)(void *), void *context) {
  if (!allowTestStackCapture()) return -1;
  android::ProcessState::self()->startThreadPool();
  HostFactory factory;
  auto flinger = android::sp<android::SurfaceFlinger>::make(factory);
  flinger->init();
  const auto manager = android::defaultServiceManager();
  auto result = manager->addService(
      android::String16(android::SurfaceFlinger::getServiceName()), flinger);
  if (result != android::OK) {
    ALOGE("Cannot publish SurfaceFlinger: %d", result);
    azk_surfaceflinger_exit(1);
  }
  auto aidl = android::sp<android::SurfaceComposerAIDL>::make(flinger);
  result = manager->addService(android::String16("SurfaceFlingerAIDL"), aidl);
  if (result != android::OK) {
    ALOGE("Cannot publish SurfaceFlingerAIDL: %d", result);
    azk_surfaceflinger_exit(1);
  }
  if (ready)
    ready(context);
  // Stock bootFinished waits for WMS/inputflinger. The system-server bootstrap
  // must call it after those services exist; service publication is earlier.
  flinger->run();
  return 0;
}
