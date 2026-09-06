// Host process startup follows main_surfaceflinger.cpp: initialize the pinned
// service before publishing its legacy and AIDL interfaces, then run its main
// loop. Graphics allocation and composer discovery use the local host backend.
#define LOG_TAG "SurfaceFlingerHost"
#include "SurfaceFlinger.h"
#include "SurfaceFlingerDefaultFactory.h"
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

extern "C" int azk_surfaceflinger_run(void (*ready)(void *), void *context) {
  android::ProcessState::self()->startThreadPool();
  android::surfaceflinger::DefaultFactory factory;
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
