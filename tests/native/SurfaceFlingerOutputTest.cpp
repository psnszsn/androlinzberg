// Running pinned SF: privileged host output management, stock transactions,
// GPU-rendered input and local consumers of remote virtual-display producers.
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <gui/BufferItemConsumer.h>
#include <gui/BufferItem.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include "GpuBufferRenderer.h"
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <thread>

using namespace android;
using namespace std::chrono_literals;
using Pixel = std::array<unsigned, 4>;

struct Signal final : ConsumerBase::FrameAvailableListener {
  std::mutex mutex;
  std::condition_variable changed;
  uint64_t sequence = 0;
  void onFrameAvailable(const BufferItem &) override {
    std::lock_guard lock(mutex);
    ++sequence;
    changed.notify_all();
  }
};

static bool matches(const GraphicBuffer &buffer, const void *memory, unsigned x,
                    unsigned y, Pixel expected) {
  const auto *pixel =
      static_cast<const uint8_t *>(memory) + (y * buffer.getStride() + x) * 4;
  for (unsigned i = 0; i < 4; ++i)
    if (std::abs(int(pixel[i]) - int(expected[i])) > 2)
      return false;
  return true;
}

struct Output {
  sp<BufferItemConsumer> consumer;
  sp<Surface> surface;
  sp<Signal> signal = sp<Signal>::make();
  sp<IBinder> display;
  sp<SurfaceControl> root, child, mirror, mirrorRoot;
  std::optional<BufferItem> held;
  Pixel expected{0, 0, 255, 255};
  uint32_t width = 64, height = 64;

  BufferItem acquire() {
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (std::chrono::steady_clock::now() < deadline) {
      std::unique_lock lock(signal->mutex);
      const auto mark = signal->sequence;
      lock.unlock();
      BufferItem item;
      const auto status = consumer->acquireBuffer(&item, 0, false);
      if (status == OK)
        return item;
      assert(status == BufferItemConsumer::NO_BUFFER_AVAILABLE);
      lock.lock();
      signal->changed.wait_until(lock, deadline,
                                 [&] { return signal->sequence != mark; });
    }
    std::cerr << "timed out acquiring SF output" << std::endl;
    std::abort();
  }

  void release() {
    if (!held)
      return;
    assert(consumer->releaseBuffer(*held) == OK);
    held.reset();
  }
  bool pixels(const BufferItem &item, Pixel color) {
    auto buffer = item.mGraphicBuffer;
    assert(buffer && item.mFence->wait(3000) == OK);
    if (buffer->getWidth() != width || buffer->getHeight() != height)
      return false;
    void *memory = nullptr;
    assert(buffer->lock(GRALLOC_USAGE_SW_READ_OFTEN, &memory) == OK);
    const bool ok = matches(*buffer, memory, 2, 2, {0, 0, 255, 255}) &&
                    matches(*buffer, memory, 12, 12, color) &&
                    matches(*buffer, memory, 26, 12, {0, 0, 255, 255});
    if (!ok) {
      const auto *p = static_cast<const uint8_t *>(memory) +
                      (12 * buffer->getStride() + 12) * 4;
      std::cerr << "output sample " << unsigned(p[0]) << ',' << unsigned(p[1])
                << ',' << unsigned(p[2]) << ',' << unsigned(p[3]) << std::endl;
    }
    assert(buffer->unlock() == OK);
    return ok;
  }
  void await(Pixel color) {
    release();
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (std::chrono::steady_clock::now() < deadline) {
      uint64_t mark;
      {
        std::lock_guard lock(signal->mutex);
        mark = signal->sequence;
      }
      BufferItem item;
      const auto status = consumer->acquireBuffer(&item, 0, false);
      if (status == OK) {
        if (pixels(item, color)) {
          held = item;
          expected = color;
          return;
        }
        assert(consumer->releaseBuffer(item) == OK);
        continue;
      }
      assert(status == BufferItemConsumer::NO_BUFFER_AVAILABLE);
      std::unique_lock lock(signal->mutex);
      signal->changed.wait_until(lock, deadline,
                                 [&] { return signal->sequence != mark; });
    }
    std::cerr << "timed out waiting for SF output pixels" << std::endl;
    std::abort();
  }
};

static void apply(SurfaceComposerClient::Transaction &transaction) {
  struct Completion {
    std::mutex mutex;
    std::condition_variable changed;
    unsigned stage = 0;
  };
  auto state = std::make_shared<Completion>();
  transaction.addTransactionCommittedCallback(
      [state](void *, nsecs_t, const sp<Fence> &,
              const std::vector<SurfaceControlStats> &) {
        std::lock_guard lock(state->mutex);
        assert(state->stage == 0);
        state->stage = 1;
      },
      nullptr);
  transaction.addTransactionCompletedCallback(
      [state](void *, nsecs_t, const sp<Fence> &,
              const std::vector<SurfaceControlStats> &) {
        std::lock_guard lock(state->mutex);
        assert(state->stage == 1);
        state->stage = 2;
        state->changed.notify_all();
      },
      nullptr);
  assert(transaction.apply() == OK);
  std::unique_lock lock(state->mutex);
  assert(state->changed.wait_for(lock, 5s, [&] { return state->stage == 2; }));
}

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

int main() {
  ProcessState::self()->startThreadPool();
  auto service =
      defaultServiceManager()->checkService(String16("SurfaceFlingerAIDL"));
  assert(service && service->remoteBinder());
  auto death = sp<Death>::make();
  assert(service->linkToDeath(death) == OK);
  const auto physical = SurfaceComposerClient::getPhysicalDisplayIds();
  assert(physical.size() == 1);
  const auto baseline = azk_graphics_live_buffers();
  {
    GpuBufferRenderer renderer;
    auto client = sp<SurfaceComposerClient>::make();
    assert(client->initCheck() == OK);
    std::array<Output, 2> outputs;
    SurfaceComposerClient::Transaction setup;
    for (unsigned i = 0; i < outputs.size(); ++i) {
      auto &out = outputs[i];
      std::tie(out.consumer, out.surface) = BufferItemConsumer::create(
          GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_SW_READ_OFTEN, 2, false);
      out.consumer->setFrameAvailableListener(out.signal);
      assert(out.consumer->setDefaultBufferSize(64, 64) == OK);
      out.display =
          SurfaceComposerClient::createVirtualDisplay("HostTaskOutput", false);
      assert(out.display);
      out.root = client->createSurface(
          String8("TaskRoot"), 64, 64, PIXEL_FORMAT_RGBA_8888,
          gui::ISurfaceComposerClient::eFXSurfaceEffect);
      assert(out.root);
      out.child = client->createSurface(String8("TaskChild"), 32, 32,
                                        PIXEL_FORMAT_RGBA_8888, 0,
                                        out.root->getHandle());
      out.mirrorRoot = client->createSurface(
          String8("HostOutputRoot"), 64, 64, PIXEL_FORMAT_RGBA_8888,
          gui::ISurfaceComposerClient::eFXSurfaceContainer);
      out.mirror = client->mirrorSurface(out.root.get());
      assert(out.child && out.mirrorRoot && out.mirror);
      setup.setColor(out.root, half3(0, 0, 1))
          .setCrop(out.root, Rect(64, 64))
          .setPosition(out.root, 300 * (i + 1), 0)
          .setLayerStack(out.root, ui::LayerStack{0})
          .show(out.root)
          .show(out.child)
          .setLayer(out.child, 1)
          .setPosition(out.child, 8, 8)
          .setMatrix(out.child, 2, 0, 0, 2)
          .setCrop(out.child, Rect(8, 8))
          .setAlpha(out.child, .5f)
          .setLayerStack(out.mirrorRoot, ui::LayerStack{101 + i})
          .setCrop(out.mirrorRoot, Rect(64, 64))
          .show(out.mirrorRoot)
          .reparent(out.mirror, out.mirrorRoot)
          .show(out.mirror)
          .setPosition(out.mirror, -300.f * (i + 1), 0);
      assert(setup.setDisplaySurface(
                 out.display, out.surface->getIGraphicBufferProducer()) == OK);
      setup.setDisplayLayerStack(out.display, ui::LayerStack{101 + i});
      setup.setDisplayProjection(out.display, ui::Rotation::Rotation0,
                                 Rect(64, 64), Rect(64, 64));
      setup.setDisplaySize(out.display, 64, 64);
      // Pinned SF remains in BOOTLOADER until it latches its first buffer.
      // Supply real GPU content before waiting for color-layer composition.
      auto initial = sp<GraphicBuffer>::make(
          32, 32, PIXEL_FORMAT_RGBA_8888, 1,
          GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE, "TaskInitial");
      assert(initial->initCheck() == OK);
      setup.setBuffer(out.child, initial, renderer.render(initial, 0xffff0000u))
          .setDataspace(out.child, ui::Dataspace::V0_SRGB);
    }
    apply(setup);
    for (auto &out : outputs)
      out.await({0, 0, 255, 255});
    struct Releases {
      std::mutex mutex;
      std::condition_variable changed;
      std::array<bool, 6> received{};
    };
    auto releases = std::make_shared<Releases>();
    for (unsigned frame = 0; frame < 6; ++frame) {
      const unsigned index = frame % 2;
      auto &out = outputs[index];
      const unsigned intensity = 80 + frame * 24;
      const bool secure = frame == 4;
      auto buffer = sp<GraphicBuffer>::make(
          32, 32, PIXEL_FORMAT_RGBA_8888, 1,
          GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE, "TaskFrame");
      assert(buffer->initCheck() == OK);
      auto fence =
          renderer.render(buffer, 0xff000000u | (intensity << (index ? 8 : 0)));
      SurfaceComposerClient::Transaction update;
      update
          .setBuffer(out.child, buffer, fence, std::nullopt, 0,
                     [releases, frame](const ReleaseCallbackId &,
                                       const sp<Fence> &released,
                                       std::optional<uint32_t>) {
                       assert(released && released->wait(3000) == OK);
                       std::lock_guard lock(releases->mutex);
                       assert(!releases->received[frame]);
                       releases->received[frame] = true;
                       releases->changed.notify_all();
                     })
          .setDataspace(out.child, ui::Dataspace::V0_SRGB)
          .setFlags(out.child, secure ? layer_state_t::eLayerSecure : 0,
                    layer_state_t::eLayerSecure);
      apply(update);
      const Pixel expected = secure  ? Pixel{0, 0, 0, 255}
                             : index ? Pixel{0, (intensity + 1) / 2, 128, 255}
                                     : Pixel{(intensity + 1) / 2, 0, 128, 255};
      out.await(expected);
      const auto &other = outputs[1 - index];
      assert(outputs[1 - index].pixels(*other.held, other.expected));
      assert(SurfaceComposerClient::getPhysicalDisplayIds() == physical);
    }
    auto &resized = outputs[0];
    resized.width = 80;
    resized.height = 72;
    resized.release();
    assert(resized.consumer->setDefaultBufferSize(80, 72) == OK);
    SurfaceComposerClient::Transaction resize;
    resize.setDisplaySize(resized.display, 80, 72);
    resize.setDisplayProjection(resized.display, ui::Rotation::Rotation0,
                                Rect(80, 72), Rect(80, 72));
    apply(resize);
    resized.await(resized.expected);
    assert(outputs[1].pixels(*outputs[1].held, outputs[1].expected));
    // Hold both allowed consumer leases. SF must keep the other output moving
    // while this consumer stops draining its queue.
    auto updateFrame = [&](unsigned index, unsigned intensity) {
      auto &out = outputs[index];
      auto buffer = sp<GraphicBuffer>::make(
          32, 32, PIXEL_FORMAT_RGBA_8888, 1,
          GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE, "TaskStallFrame");
      assert(buffer->initCheck() == OK);
      auto fence =
          renderer.render(buffer, 0xff000000u | (intensity << (index ? 8 : 0)));
      SurfaceComposerClient::Transaction transaction;
      transaction.setBuffer(out.child, buffer, fence)
          .setFlags(out.child, 0, layer_state_t::eLayerSecure);
      apply(transaction);
    };
    updateFrame(0, 120);
    auto stalled = resized.acquire();
    assert(resized.pixels(stalled, {60, 0, 128, 255}));
    for (unsigned i = 0; i < 4; ++i) {
      updateFrame(0, 140 + i * 10);
      updateFrame(1, 140 + i * 10);
      outputs[1].await({0, (140 + i * 10) / 2, 128, 255});
    }
    assert(resized.pixels(stalled, {60, 0, 128, 255}));
    assert(resized.consumer->releaseBuffer(stalled) == OK);
    stalled = {};
    resized.await({85, 0, 128, 255});
    SurfaceComposerClient::Transaction clear;
    for (auto &out : outputs)
      clear.setBuffer(out.child, nullptr);
    apply(clear);
    {
      std::unique_lock lock(releases->mutex);
      assert(releases->changed.wait_for(lock, 5s, [&] {
        return std::all_of(releases->received.begin(), releases->received.end(),
                           [](bool value) { return value; });
      }));
    }
    outputs[0].release();
    assert(SurfaceComposerClient::destroyVirtualDisplay(outputs[0].display) ==
           OK);
    outputs[0].consumer->abandon();
    updateFrame(1, 200);
    outputs[1].await({0, 100, 128, 255});
    assert(SurfaceComposerClient::getPhysicalDisplayIds() == physical);
    SurfaceComposerClient::Transaction releaseInput;
    releaseInput.setBuffer(outputs[1].child, nullptr);
    apply(releaseInput);
    std::cout << "PASS output destruction preserves the surviving output"
              << std::endl;
    std::cout << "READY_DEATH" << std::endl;
    {
      std::unique_lock lock(death->mutex);
      assert(death->changed.wait_for(lock, 5s, [&] { return death->died; }));
    }
    assert(service->pingBinder() == DEAD_OBJECT);
    // The last acquired frame remains owned by the consumer after SF dies.
    // Abandon the disconnected queue before dropping that retained frame.
    assert(outputs[1].pixels(*outputs[1].held, outputs[1].expected));
    outputs[1].consumer->abandon();
    outputs[1].held.reset();
  }
  // Imported queue buffers and the local producers are no longer owned here.
  assert(azk_graphics_live_buffers() == baseline);
  std::cout
      << "PASS running SF independent mirrored output queues, GPU "
         "updates, crop/scale/alpha, secure blackout, resize, stalled-output "
         "isolation, release fences and callback order"
      << std::endl;
  std::cout << "PASS SF death with a live output and retained frame cleanup"
            << std::endl;
}
