#define LOG_TAG "SurfaceFlingerServiceTest"
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <gui/LayerState.h>
#include <private/gui/ComposerServiceAIDL.h>
#include <cassert>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <thread>

using namespace android;
using namespace std::chrono_literals;

struct Death final : IBinder::DeathRecipient {
  std::mutex mutex;
  std::condition_variable changed;
  bool died = false;
  void binderDied(const wp<IBinder> &) override {
    std::lock_guard lock(mutex);
    died = true;
    changed.notify_all();
  }
};

static void parcelContracts() {
  Region region(Rect(1, 2, 5, 6));
  region.orSelf(Rect(9, 10, 14, 16));
  Parcel parcel;
  assert(parcel.write(region) == OK);
  assert(parcel.writeInt32(0x13579) == OK);
  parcel.setDataPosition(0);
  assert(parcel.readInt32() == int32_t(region.getFlattenedSize()));
  parcel.setDataPosition(0);
  Region decoded;
  assert(parcel.read(decoded) == OK && decoded.hasSameRects(region));
  assert(parcel.readInt32() == 0x13579 && parcel.dataAvail() == 0);
  for (int32_t size : {-1, 0, 4096}) {
    parcel.freeData();
    assert(parcel.writeInt32(size) == OK);
    parcel.setDataPosition(0);
    assert(parcel.read(decoded) == (size == 0 ? OK : NO_MEMORY));
    assert(decoded.hasSameRects(region));
  }
  parcel.freeData();
  const Rect fixed(2, 4, 10, 12);
  assert(parcel.write(fixed) == OK &&
         parcel.dataSize() == fixed.getFlattenedSize());
  parcel.setDataPosition(0);
  Rect restored;
  assert(parcel.read(restored) == OK && restored == fixed);
  parcel.freeData();
  layer_state_t source, destination;
  source.x = 17;
  source.y = 29;
  source.updateTransparentRegion(region);
  source.updateSurfaceDamageRegion(region);
  assert(source.write(parcel) == OK);
  parcel.setDataPosition(0);
  assert(destination.read(parcel) == OK);
  assert(destination.x == 17 && destination.y == 29);
  assert(destination.getTransparentRegion().hasSameRects(region));
  assert(destination.getSurfaceDamageRegion().hasSameRects(region));
  assert(parcel.dataAvail() == 0);
  std::cout << "PASS stock Region/Rect and complete LayerState parcel framing"
            << std::endl;
}

int main(int argc, char **argv) {
  assert(argc == 2);
  const bool restart = std::strcmp(argv[1], "restart") == 0;
  assert(restart || std::strcmp(argv[1], "retire") == 0);
  parcelContracts();
  ProcessState::self()->startThreadPool();
  auto binder =
      defaultServiceManager()->checkService(String16("SurfaceFlingerAIDL"));
  assert(binder && binder->remoteBinder());
  auto death = sp<Death>::make();
  assert(binder->linkToDeath(death) == OK);
  const auto ids = SurfaceComposerClient::getPhysicalDisplayIds();
  assert(ids.size() == 1);
  auto client = sp<SurfaceComposerClient>::make();
  assert(client->initCheck() == OK);
  auto surface = client->createSurface(String8("NativeServiceContract"), 64, 48,
                                       PIXEL_FORMAT_RGBA_8888);
  assert(surface && surface->isValid());
  std::mutex commitMutex;
  std::condition_variable commitChanged;
  bool committed = false;
  assert(SurfaceComposerClient::Transaction()
             .setPosition(surface, 8, 12)
             .setAlpha(surface, 0.5f)
             .addTransactionCommittedCallback(
                 [&](void *, nsecs_t, const sp<Fence> &,
                     const std::vector<SurfaceControlStats> &) {
                   std::lock_guard lock(commitMutex);
                   committed = true;
                   commitChanged.notify_all();
                 },
                 nullptr)
             .apply() == OK);
  {
    std::unique_lock lock(commitMutex);
    assert(commitChanged.wait_for(lock, 5s, [&] { return committed; }));
  }
  // The runner gives this process an application UID. Display creation must
  // preserve SF's access check, even though it shares the host login UID.
  auto service = ComposerServiceAIDL::getComposerService();
  sp<IBinder> unauthorizedDisplay;
  const auto status = service->createVirtualDisplay(
      "Unauthorized", false,
      gui::ISurfaceComposer::OptimizationPolicy::optimizeForPower, "", 60,
      &unauthorizedDisplay);
  assert(status.exceptionCode() == binder::Status::EX_SECURITY &&
         !unauthorizedDisplay);
  std::cout << "PASS running SF display discovery, stock client/layer "
               "transaction and permission enforcement"
            << std::endl;
  std::cout << "READY_DEATH" << std::endl;
  std::unique_lock lock(death->mutex);
  assert(death->changed.wait_for(lock, 5s, [&] { return death->died; }));
  lock.unlock();
  // A cached identity never becomes the replacement service.
  assert(binder->pingBinder() == DEAD_OBJECT);
  std::vector<int64_t> deadIds;
  assert(service->getPhysicalDisplayIds(&deadIds).transactionError() == DEAD_OBJECT);
  if (restart) {
    sp<gui::ISurfaceComposer> replacement;
    for (int attempt = 0; attempt < 100; ++attempt) {
      replacement = ComposerServiceAIDL::getComposerService();
      if (IInterface::asBinder(replacement) != binder &&
          IInterface::asBinder(replacement)->pingBinder() == OK) break;
      std::this_thread::sleep_for(10ms);
    }
    assert(replacement && IInterface::asBinder(replacement) != binder);
    std::vector<int64_t> replacementIds;
    assert(replacement->getPhysicalDisplayIds(&replacementIds).isOk());
    assert(replacementIds.size() == 1);
    auto newClient = sp<SurfaceComposerClient>::make();
    assert(newClient->initCheck() == OK);
    auto newSurface = newClient->createSurface(String8("ReplacementServiceContract"),
                                              64, 48, PIXEL_FORMAT_RGBA_8888);
    assert(newSurface && newSurface->isValid());
    {
      std::lock_guard guard(commitMutex);
      committed = false;
    }
    assert(SurfaceComposerClient::Transaction().setAlpha(newSurface, 0.75f)
        .addTransactionCommittedCallback(
            [&](void *, nsecs_t, const sp<Fence> &,
                const std::vector<SurfaceControlStats> &) {
              std::lock_guard guard(commitMutex);
              committed = true;
              commitChanged.notify_all();
            }, nullptr).apply() == OK);
    std::unique_lock guard(commitMutex);
    assert(commitChanged.wait_for(guard, 5s, [&] { return committed; }));
    assert(binder->pingBinder() == DEAD_OBJECT);
    std::cout << "PASS original legacy and AIDL composer clients reconnect; old identity stays dead"
              << std::endl;
  } else {
    // Whole-session loss retains dead identities for final surface cleanup.
    for (int retry = 0; retry < 3; ++retry) {
      assert(SurfaceComposerClient::Transaction().setAlpha(surface, 0.25f).apply() ==
             DEAD_OBJECT);
      auto retired = ComposerServiceAIDL::getComposerService();
      assert(retired);
      std::vector<int64_t> retiredIds;
      assert(retired->getPhysicalDisplayIds(&retiredIds).transactionError() ==
             DEAD_OBJECT);
      assert(retiredIds.empty());
    }
    std::cout << "PASS session retirement returns DEAD_OBJECT for legacy and AIDL clients"
              << std::endl;
  }
}
