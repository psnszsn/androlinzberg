// Pinned producer Bp/Bn and stock BufferQueue across two AZKB peers. The
// consumer is local to the queue-owning process, as required by Android 16.
#include <azk/binder.h>
#include <cassert>
#include <azk/graphics_buffer.h>
#include <binder/Binder.h>
#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <android-base/properties.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_aidl.h>
#include <android/native_window_aidl.h>
#include <filesystem>
#include <gui/BufferQueue.h>
#include <gui/BufferItem.h>
#include <gui/IConsumerListener.h>
#include <gui/IProducerListener.h>
#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferAllocator.h>
#include <system/window.h>
#include "GpuBufferRenderer.h"
#include "DelayedGpuFence.h"
#include <cerrno>
#include <android/binder_manager.h>
#include <chrono>
#include <iostream>
#include <thread>
#include <atomic>
#include <array>
#include <vector>

using namespace android;
using namespace std::chrono_literals;
namespace {
constexpr uint64_t usage = GRALLOC_USAGE_SW_READ_OFTEN |
                           GRALLOC_USAGE_SW_WRITE_OFTEN |
                           GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_RENDER;
class Listener final : public ConsumerListener {
public:
  void onFrameAvailable(const BufferItem &) override {}
  void onBuffersReleased() override {}
  void onSidebandStreamChanged() override {}
};
class ReleaseListener final : public BnProducerListener {
public:
  std::atomic<unsigned> releases = 0;
  std::atomic<int> callerUid = -1;
  void onBufferReleased() override {
    assert(azk_binder_callFlags() & IBinder::FLAG_ONEWAY);
    callerUid = IPCThreadState::self()->getCallingUid();
    ++releases;
  }
  bool needsReleaseNotify() override { return true; }
};
class Service final : public BBinder {
  sp<IGraphicBufferProducer> producer;
  sp<IGraphicBufferConsumer> consumer;
  sp<Listener> listener = sp<Listener>::make();
  std::array<sp<GraphicBuffer>, 64> slots;
  std::optional<BufferItem> held;

public:
  Service() {
    BufferQueue::createBufferQueue(&producer, &consumer);
    auto proxy = sp<BufferQueue::ProxyConsumerListener>::make(listener);
    assert(consumer->consumerConnect(proxy, false) == OK);
    assert(consumer->setDefaultBufferSize(64, 48) == OK);
    assert(consumer->setConsumerUsageBits(usage) == OK);
  }
  ~Service() override { consumer->consumerDisconnect(); }
  status_t onTransact(uint32_t code, const Parcel &in, Parcel *out,
                      uint32_t) override {
    if (code == 1)
      return out->writeStrongBinder(IInterface::asBinder(producer));
    if (code == 2) {
      assert(!held);
      BufferItem item;
      auto status = consumer->acquireBuffer(&item, 0);
      out->writeInt32(status);
      if (status != OK)
        return OK;
      assert(item.mSlot >= 0 && item.mSlot < 64);
      if (item.mGraphicBuffer)
        slots[item.mSlot] = item.mGraphicBuffer;
      auto buffer = slots[item.mSlot];
      assert(buffer && item.mFence->wait(3000) == OK);
      void *pixels = nullptr;
      assert(buffer->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels) == OK);
      const auto value = *static_cast<uint32_t *>(pixels);
      assert(buffer->unlock() == OK);
      out->writeUint32(buffer->getWidth());
      out->writeUint32(buffer->getHeight());
      out->writeUint32(value);
      held = item;
      return OK;
    }
    if (code == 3) {
      assert(held);
      auto fence = sp<Fence>::make();
      assert(in.read(*fence) == OK);
      auto status = consumer->releaseBuffer(held->mSlot, held->mFrameNumber,
                                            fence);
      held.reset();
      return out->writeInt32(status);
    }
    if (code == 4) {
      const auto width = in.readUint32();
      const auto height = in.readUint32();
      return out->writeInt32(consumer->setDefaultBufferSize(width, height));
    }
    if (code == 5) {
      out->writeInt32(IPCThreadState::self()->getCallingPid());
      return out->writeInt32(IPCThreadState::self()->getCallingUid());
    }
    if (code == 6)
      return out->writeUint64(azk_graphics_live_buffers());
    return UNKNOWN_TRANSACTION;
  }
};
class Death final : public IBinder::DeathRecipient {
public:
  std::atomic<bool> died = false;
  void binderDied(const wp<IBinder> &) override { died = true; }
};
void release(const sp<IBinder> &service,
             const sp<Fence> &fence = Fence::NO_FENCE) {
  Parcel in, out;
  assert(in.write(*fence) == OK);
  assert(service->transact(3, in, &out) == OK && out.readInt32() == OK);
}
void acquire(const sp<IBinder> &service, uint32_t width, uint32_t height,
             uint32_t pixel) {
  Parcel in, out;
  assert(service->transact(2, in, &out) == OK);
  assert(out.readInt32() == OK);
  assert(out.readUint32() == width && out.readUint32() == height &&
         out.readUint32() == pixel);
}
size_t openFiles() {
  return std::distance(std::filesystem::directory_iterator("/proc/self/fd"),
                       std::filesystem::directory_iterator{});
}
// Run in a fresh process so the administrative limits precede GBM startup.
void graphicsBudgetTest(bool countLimit) {
  assert(setenv("AZK_GRAPHICS_MAX_BUFFERS", countLimit ? "3" : "1024", 1) == 0);
  assert(setenv("AZK_GRAPHICS_MAX_BYTES", countLimit ? "2097152" : "1048576", 1) == 0);
  AHardwareBuffer_Desc desc{};
  desc.width = desc.height = 64;
  desc.layers = 1;
  desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
  desc.usage = usage;
  AHardwareBuffer *warm = nullptr;
  assert(AHardwareBuffer_allocate(&desc, &warm) == OK);
  const auto unit = azk_graphics_live_bytes();
  assert(unit >= 64 * 64 * 4);
  void *warmPixels = nullptr;
  assert(AHardwareBuffer_lock(warm, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                              -1, nullptr, &warmPixels) == OK && warmPixels);
  assert(AHardwareBuffer_unlock(warm, nullptr) == OK);
  AHardwareBuffer_release(warm);
  const auto files = openFiles();
  const uint64_t limit = countLimit ? 3 : 1048576 / unit;
  assert(limit > 1 && limit < 1024);
  for (int round = 0; round < 3; ++round) {
    std::vector<AHardwareBuffer*> held;
    for (uint64_t i = 0; i < limit; ++i) {
      AHardwareBuffer *buffer = nullptr;
      assert(AHardwareBuffer_allocate(&desc, &buffer) == OK && buffer);
      held.push_back(buffer);
    }
    assert(azk_graphics_live_buffers() == limit);
    assert(azk_graphics_live_bytes() == limit * unit);
    const auto occupied = openFiles();
    AHardwareBuffer *rejected = nullptr;
    assert(AHardwareBuffer_allocate(&desc, &rejected) == NO_MEMORY && !rejected);
    assert(openFiles() == occupied && azk_graphics_live_buffers() == limit);
    AHardwareBuffer_release(held.back());
    held.pop_back();
    AHardwareBuffer *retry = nullptr;
    assert(AHardwareBuffer_allocate(&desc, &retry) == OK && retry);
    held.push_back(retry);
    for (auto *buffer : held) AHardwareBuffer_release(buffer);
    assert(azk_graphics_live_buffers() == 0 && azk_graphics_live_bytes() == 0);
    assert(openFiles() == files);

    // Repeated imports of one borrowed DMA-BUF also consume full admission.
    AHardwareBuffer *source = nullptr;
    assert(AHardwareBuffer_allocate(&desc, &source) == OK);
    auto *graphic = GraphicBuffer::fromAHardwareBuffer(source);
    std::vector<native_handle_t*> imported;
    for (uint64_t i = 1; i < limit; ++i) {
      native_handle_t *handle = nullptr;
      assert(azk_graphics_import(graphic->handle, &handle) == OK && handle);
      imported.push_back(handle);
    }
    const auto importFiles = openFiles();
    native_handle_t *denied = nullptr;
    assert(azk_graphics_import(graphic->handle, &denied) == NO_MEMORY && !denied);
    assert(openFiles() == importFiles && azk_graphics_live_buffers() == limit);
    assert(azk_graphics_free(imported.back()) == OK);
    imported.pop_back();
    assert(azk_graphics_import(graphic->handle, &denied) == OK && denied);
    imported.push_back(denied);
    AHardwareBuffer_release(source);
    // The imported descriptor remains usable after its exporter disappears.
    void *pixels = nullptr;
    int32_t bpp, stride;
    assert(azk_graphics_lock(imported[0], GRALLOC_USAGE_SW_WRITE_OFTEN,
                              0, 0, 64, 64, &pixels, &bpp, &stride) == OK);
    assert(pixels && bpp == 4);
    *static_cast<uint32_t*>(pixels) = 0xff123456;
    assert(azk_graphics_unlock(imported[0]) == OK);
    for (auto *handle : imported) assert(azk_graphics_free(handle) == OK);
    assert(azk_graphics_live_buffers() == 0 && azk_graphics_live_bytes() == 0);
    assert(openFiles() == files);
  }
  std::cout << "PASS gralloc " << (countLimit ? "handle" : "byte")
            << " admission: allocation/import rejection, live exporter independence, "
               "capacity reuse, stable descriptors and zero retained bytes" << std::endl;
}

void hardwareBufferTests() {
  const auto baseline = azk_graphics_live_buffers();
  const auto files = openFiles();
  AHardwareBuffer_Desc desc{};
  desc.width = 40;
  desc.height = 20;
  desc.layers = 1;
  desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
  desc.usage = usage;
  AHardwareBuffer *original = nullptr;
  assert(AHardwareBuffer_allocate(&desc, &original) == OK);
  auto *graphic = GraphicBuffer::fromAHardwareBuffer(original);
  assert(graphic && graphic->handle);
  uint64_t id;
  assert(AHardwareBuffer_getId(original, &id) == OK && id == graphic->getId());
  void *pixels = nullptr;
  int32_t bpp = 0, row = 0;
  const auto locked = AHardwareBuffer_lockAndGetInfo(
      original, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels,
      &bpp, &row);
  if (locked != OK) {
    AzkGraphicsBufferInfo debug{};
    azk_graphics_describe(graphic->handle, &debug);
    std::cerr << "AHB lock status=" << locked << " modifier=" << debug.modifier
              << " usage=" << debug.usage << " bpp=" << bpp << " row=" << row
              << std::endl;
  }
  assert(locked == OK);
  assert(bpp == 4 && row >= 160);
  static_cast<uint32_t *>(pixels)[5 * row / 4 + 5] = 0xff123456;
  assert(AHardwareBuffer_unlock(original, nullptr) == OK);
  Parcel parcel;
  assert(AHardwareBuffer_writeToParcel(original, parcel.native()) == OK);
  parcel.setDataPosition(0);
  AHardwareBuffer *imported = nullptr;
  assert(AHardwareBuffer_readFromParcel(parcel.native(), &imported) == OK);
  AHardwareBuffer_release(original);
  parcel.freeData();
  const ARect bounds{5, 5, 10, 10};
  assert(AHardwareBuffer_lock(imported, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                              -1, &bounds, &pixels) == OK);
  // Android returns the buffer base even for a restricted access rectangle.
  assert(static_cast<uint32_t *>(pixels)[5 * row / 4 + 5] == 0xff123456);
  assert(AHardwareBuffer_unlock(imported, nullptr) == OK);
  uint64_t importedId;
  assert(AHardwareBuffer_getId(imported, &importedId) == OK &&
         importedId == id);
  AHardwareBuffer_release(imported);
  assert(azk_graphics_live_buffers() == baseline && openFiles() == files);
  std::cout << "PASS stock AHardwareBuffer/GraphicBuffer share ownership, IDs, "
               "mapping and AParcel serialization"
            << std::endl;
}
void externalBufferTests() {
  const auto baseline = azk_graphics_live_buffers();
  GpuBufferRenderer renderer;
  const auto files = openFiles();
  {
    auto original = sp<GraphicBuffer>::make(48, 32, PIXEL_FORMAT_RGBA_8888,
                                             1, usage, "external-source");
    assert(original->initCheck() == OK);
    auto fence = renderer.render(original, 0xff3478ab);
    assert(fence->wait(1000) == OK);
    fence.clear();
    AzkGraphicsBufferInfo source{}, importedInfo{};
    assert(azk_graphics_describe(original->handle, &source) == OK);
    native_handle_t *handle = nullptr;
    for (unsigned invalid = 0; invalid < 6; ++invalid) {
      auto bad = source;
      switch (invalid) {
      case 0: bad.fd = -1; break;
      case 1: bad.drm_format ^= 1; break;
      case 2: bad.stride = 1; break;
      case 3: bad.offset = 0xffffffff; break;
      case 4: bad.usage |= GRALLOC_USAGE_PROTECTED; break;
      case 5: bad.reserved = 1; break;
      }
      assert(azk_graphics_import_external(&bad, &handle) == BAD_VALUE && !handle);
    }
    assert(azk_graphics_import_external(&source, &handle) == OK && handle);
    assert(azk_graphics_describe(handle, &importedInfo) == OK);
    assert(importedInfo.fd != source.fd && importedInfo.id != source.id);
    // Pinned initWithHandle queries transport sizes on CLONE_HANDLE; the raw
    // TAKE_HANDLE wrapper does not initialize the fields used by flatten().
    auto imported = sp<GraphicBuffer>::make(handle, GraphicBuffer::CLONE_HANDLE,
        importedInfo.width, importedInfo.height, PIXEL_FORMAT_RGBA_8888,
        1, importedInfo.usage, importedInfo.stride / 4);
    assert(azk_graphics_free(handle) == OK);
    assert(imported->initCheck() == OK);
    original.clear(); // The foreign exporter's allocation owner may disappear.
    Parcel parcel;
    assert(parcel.write(*imported) == OK);
    parcel.setDataPosition(0);
    auto remote = sp<GraphicBuffer>::make();
    assert(parcel.read(*remote) == OK);
    imported.clear();
    parcel.freeData();
    void *pixels = nullptr;
    assert(remote->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels) == OK);
    for (unsigned y : {0u, 31u}) for (unsigned x : {0u, 47u})
      assert(static_cast<uint32_t *>(pixels)[y * remote->getStride() + x] == 0xff3478ab);
    assert(remote->unlock() == OK);
  }
  assert(azk_graphics_live_buffers() == baseline && openFiles() == files);
  std::cout << "PASS foreign GPU DMA-BUF import validates metadata, owns its FD "
               "and preserves pixels through stock GraphicBuffer Parcel transport" << std::endl;
}
void handleTests() {
  const auto baseline = azk_graphics_live_buffers();
  auto buffer = sp<GraphicBuffer>::make(40, 20, PIXEL_FORMAT_RGBA_8888, 1,
                                        usage, "handle-test");
  assert(buffer->initCheck() == OK);
  assert(azk_graphics_live_buffers() == baseline + 1);
  const auto files = openFiles();
  for (int iteration = 0; iteration < 64; ++iteration) {
    native_handle_t *imported = nullptr;
    assert(azk_graphics_import(buffer->handle, &imported) == OK);
    assert(azk_graphics_free(imported) == OK);
  }
  assert(openFiles() == files);
  auto *clone = native_handle_clone(buffer->handle);
  assert(clone);
  native_handle_t *imported = nullptr;
  assert(azk_graphics_import(clone, &imported) == OK);
  AzkGraphicsBufferInfo original{}, other{};
  assert(azk_graphics_describe(buffer->handle, &original) == OK);
  assert(azk_graphics_describe(imported, &other) == OK);
  assert(original.id == other.id && original.fd != other.fd);
  assert(azk_graphics_free(imported) == OK);
  clone->data[2] = 99; // schema version
  imported = nullptr;
  assert(azk_graphics_import(clone, &imported) == BAD_VALUE &&
         imported == nullptr);
  native_handle_close(clone);
  native_handle_delete(clone);
  auto protectedBuffer =
      sp<GraphicBuffer>::make(40, 20, PIXEL_FORMAT_RGBA_8888, 1,
                              uint64_t(GRALLOC_USAGE_PROTECTED), "rejected");
  assert(protectedBuffer->initCheck() != OK);
  buffer.clear();
  protectedBuffer.clear();
  assert(azk_graphics_live_buffers() == baseline);
  std::cout << "PASS GraphicBuffer allocation/import metadata, independent "
               "ownership and protected rejection"
            << std::endl;
}
} // namespace
int main(int argc, char **argv) {
  if (argc > 1 && (std::string(argv[1]) == "budget-count" ||
                  std::string(argv[1]) == "budget-bytes")) {
    graphicsBudgetTest(std::string(argv[1]) == "budget-count");
    return 0;
  }
  if (argc > 1 && std::string(argv[1]) == "server") {
    auto service = sp<Service>::make();
    auto *native = service->acquireNative();
    assert(AServiceManager_addService(native, "dev.artzig.facade.v2") == OK);
    AIBinder_decStrong(native);
    std::cout << "ready" << std::endl;
    char ch;
    while (std::cin.get(ch)) {
    }
    return 0;
  }
  if (argc > 1 && std::string(argv[1]) == "crash-producer") {
    auto service = IBinder::fromNative(
        AServiceManager_waitForService("dev.artzig.facade.v2"));
    Parcel data, reply;
    assert(service->transact(1, data, &reply) == OK);
    auto producer =
        interface_cast<IGraphicBufferProducer>(reply.readStrongBinder());
    IGraphicBufferProducer::QueueBufferOutput connected;
    assert(producer->connect(sp<ReleaseListener>::make(), NATIVE_WINDOW_API_CPU,
                             false, &connected) == OK);
    int slot;
    sp<Fence> fence;
    assert(producer->dequeueBuffer(&slot, &fence, 0, 0, PIXEL_FORMAT_RGBA_8888,
                                   usage, nullptr, nullptr) >= 0);
    sp<GraphicBuffer> buffer;
    assert(producer->requestBuffer(slot, &buffer) == OK && buffer);
    std::cout << "READY_CRASH" << std::endl;
    char ch;
    while (std::cin.get(ch)) {
    }
    return 1; // The harness must kill us with a live dequeued buffer.
  }
  assert(android::base::GetProperty("ro.build.version.sdk", "missing") == "35");
  assert(android::base::GetProperty("ro.build.version.known_codenames", "")
             .size() > 92);
  assert(android::base::GetProperty("azk.missing.regression", "fallback") ==
         "fallback");
  assert(!android::checkPermission(String16("android.permission.DUMP"),
                                   getpid(), 10000, false));
  int32_t callingPid = -1, callingUid = -1;
  assert(!android::checkCallingPermission(String16("android.permission.DUMP"),
                                         &callingPid, &callingUid));
  // The broker assigns ordinary clients Android application UIDs, independently of
  // the desktop UID that owns the socket.
  assert(callingPid == getpid() && callingUid >= 10000);
  assert(!android::checkCallingPermission(String16("android.permission.DUMP")));
  assert(!android::waitForDeclaredService<IGraphicBufferProducer>(
      String16("dev.artzig.undeclared.regression")));
  std::cout << "PASS native properties share the session area; unavailable "
               "permission service denies access"
            << std::endl;
  handleTests();
  hardwareBufferTests();
  externalBufferTests();
  auto service = IBinder::fromNative(
      AServiceManager_waitForService("dev.artzig.facade.v2"));
  assert(service && service->remoteBinder());
  // Binder death must disconnect the killed producer and free its dequeued
  // allocation before a replacement producer attaches to this same queue.
  const auto cleanupDeadline = std::chrono::steady_clock::now() + 3s;
  while (true) {
    Parcel data, reply;
    assert(service->transact(6, data, &reply) == OK);
    if (reply.readUint64() == 0)
      break;
    assert(std::chrono::steady_clock::now() < cleanupDeadline);
    std::this_thread::sleep_for(1ms);
  }
  std::cout << "PASS producer crash releases a dequeued allocation and permits "
               "replacement"
            << std::endl;

  Parcel in, out;
  assert(service->transact(1, in, &out) == OK);
  auto producerBinder = out.readStrongBinder();
  auto producer = interface_cast<IGraphicBufferProducer>(producerBinder);
  assert(producer && producerBinder->remoteBinder());
  out.freeData();
  {
    Parcel data, reply;
    assert(service->transact(5, data, &reply) == OK);
    assert(reply.readInt32() == getpid() && reply.readInt32() >= 10000);
  }
  {
    Parcel malformed, reply;
    assert(producerBinder->transact(IBinder::FIRST_CALL_TRANSACTION, malformed,
                                    &reply) == PERMISSION_DENIED);
  }
  auto listener = sp<ReleaseListener>::make();
  IGraphicBufferProducer::QueueBufferOutput connected;
  assert(producer->connect(listener, NATIVE_WINDOW_API_CPU, false,
                           &connected) == OK);
  {
    sp<GraphicBuffer> invalid;
    assert(producer->requestBuffer(-1, &invalid) == BAD_VALUE && !invalid);
  }
  assert(producer->setDequeueTimeout(30'000'000) == OK);
  std::array<sp<GraphicBuffer>, 64> slots;
  GpuBufferRenderer renderer;
  for (uint32_t frame = 0; frame < 24; ++frame) {
    uint32_t width = frame < 12 ? 64 : 96;
    uint32_t height = frame < 12 ? 48 : 72;
    if (frame == 12) {
      Parcel data, reply;
      data.writeUint32(width);
      data.writeUint32(height);
      assert(service->transact(4, data, &reply) == OK &&
             reply.readInt32() == OK);
    }
    int slot = -1;
    sp<Fence> fence;
    auto status = producer->dequeueBuffer(
        &slot, &fence, 0, 0, PIXEL_FORMAT_RGBA_8888, usage, nullptr, nullptr);
    assert(status >= 0 && slot >= 0 && slot < 64);
    if (status & IGraphicBufferProducer::RELEASE_ALL_BUFFERS)
      for (auto &b : slots)
        b.clear();
    if (status & IGraphicBufferProducer::BUFFER_NEEDS_REALLOCATION)
      assert(producer->requestBuffer(slot, &slots[slot]) == OK);
    assert(slots[slot] && fence->wait(3000) == OK);
    auto buffer = slots[slot];
    assert(buffer->getWidth() == width && buffer->getHeight() == height);
    const uint32_t color = 0xff000000 | (frame * 0x010203);
    sp<Fence> completion = Fence::NO_FENCE;
    if (frame < 12) {
      void *pixels = nullptr;
      assert(buffer->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, &pixels) == OK);
      for (uint32_t y = 0; y < height; ++y)
        for (uint32_t x = 0; x < width; ++x)
          static_cast<uint32_t *>(pixels)[y * buffer->getStride() + x] = color;
      assert(buffer->unlock() == OK);
    } else {
      completion = renderer.render(buffer, color);
    }
    IGraphicBufferProducer::QueueBufferInput queued(
        0, true, HAL_DATASPACE_UNKNOWN, Rect(width, height),
        NATIVE_WINDOW_SCALING_MODE_FREEZE, 0, completion);
    IGraphicBufferProducer::QueueBufferOutput reply;
    assert(producer->queueBuffer(slot, queued, &reply) == OK);
    if (frame < 23)
      acquire(service, width, height, color);
    if (frame < 22)
      release(service);
  }
  // One acquired buffer plus one queued buffer exhausts this synchronous
  // queue. Withholding compositor release must time out, not allocate forever.
  int blockedSlot = -1;
  sp<Fence> blockedFence;
  const auto start = std::chrono::steady_clock::now();
  assert(producer->dequeueBuffer(&blockedSlot, &blockedFence, 0, 0,
                                 PIXEL_FORMAT_RGBA_8888, usage, nullptr,
                                 nullptr) == TIMED_OUT);
  assert(std::chrono::steady_clock::now() - start >= 20ms);
  {
    // The released slot is the only available slot. Its real GPU release fence
    // must survive both the control Parcel and stock Bp/Bn producer transport;
    // dequeuing a slot does not authorize overwriting its pixels before signal.
    DelayedGpuFence gate;
    release(service, gate.fence());
    const auto status = producer->dequeueBuffer(
        &blockedSlot, &blockedFence, 0, 0, PIXEL_FORMAT_RGBA_8888, usage,
        nullptr, nullptr);
    const auto pending = status >= 0 && blockedFence && blockedFence->isValid()
        ? blockedFence->wait(25) : BAD_VALUE;
    gate.release();
    assert(status >= 0 && pending == -ETIME && !gate.timedOut());
    assert(blockedFence->wait(1000) == OK);
    assert(producer->cancelBuffer(blockedSlot, blockedFence) == OK);
    blockedFence.clear();
  }
  std::cout << "PASS delayed GPU release fence survives stock BufferQueue and AZKB"
            << std::endl;
  acquire(service, 96, 72, 0xff000000 | (23 * 0x010203));
  release(service);
  const auto callbackDeadline = std::chrono::steady_clock::now() + 3s;
  while (listener->releases < 24 &&
         std::chrono::steady_clock::now() < callbackDeadline)
    std::this_thread::sleep_for(1ms);
  assert(listener->releases == 24 && listener->callerUid == 1000);
  std::cout << "PASS bounded queue timeout, resumed release, cancellation and "
               "oneway callbacks with authenticated identities"
            << std::endl;
  assert(producer->disconnect(NATIVE_WINDOW_API_CPU) == OK);
  for (auto &b : slots)
    b.clear();
  assert(azk_graphics_live_buffers() == 0);
  {
    auto surface = sp<Surface>::make(producer, true);
    ANativeWindow *window = surface.get();
    assert(native_window_api_connect(window, NATIVE_WINDOW_API_EGL) == OK);
    assert(native_window_set_usage(window, usage) == OK);
    assert(native_window_set_buffers_dimensions(window, 80, 60) == OK);
    Parcel windowParcel;
    assert(ANativeWindow_writeToParcel(window, windowParcel.native()) == OK);
    windowParcel.setDataPosition(0);
    ANativeWindow *roundTrip = nullptr;
    assert(ANativeWindow_readFromParcel(windowParcel.native(), &roundTrip) == OK);
    assert(roundTrip != nullptr);
    assert(ANativeWindow_getWidth(roundTrip) == ANativeWindow_getWidth(window));
    assert(ANativeWindow_getHeight(roundTrip) == ANativeWindow_getHeight(window));
    windowParcel.freeData();
    ANativeWindow_release(roundTrip);
    std::cout << "PASS original ANativeWindow parcel loader and producer ownership" << std::endl;
    ANativeWindowBuffer *nativeBuffer = nullptr;
    int fenceFd = -1;
    assert(window->dequeueBuffer(window, &nativeBuffer, &fenceFd) == OK);
    assert(sp<Fence>::make(fenceFd)->wait(3000) == OK);
    auto buffer = GraphicBuffer::from(nativeBuffer);
    auto completion = renderer.render(buffer, 0xff3478ab);
    assert(window->queueBuffer(window, nativeBuffer, completion->dup()) == OK);
    acquire(service, 80, 60, 0xff3478ab);
    release(service);
    assert(native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL) == OK);
  }
  assert(azk_graphics_live_buffers() == 0);
  std::cout << "PASS stock Surface/ANativeWindow queues GPU frames to a remote "
               "BufferQueue"
            << std::endl;
  {
    GLuint texture;
    glGenTextures(1, &texture);
    auto [consumer, surface] =
        GLConsumer::create(texture, GL_TEXTURE_2D, true, false);
    ANativeWindow *window = surface.get();
    assert(native_window_api_connect(window, NATIVE_WINDOW_API_EGL) == OK);
    assert(native_window_set_usage(window, usage) == OK);
    for (unsigned frame = 0; frame < 6; ++frame) {
      const unsigned width = frame < 3 ? 64 : 96;
      const unsigned height = frame < 3 ? 48 : 72;
      assert(native_window_set_buffers_dimensions(window, width, height) == OK);
      ANativeWindowBuffer *nativeBuffer = nullptr;
      int fd = -1;
      assert(window->dequeueBuffer(window, &nativeBuffer, &fd) == OK);
      assert(sp<Fence>::make(fd)->wait(3000) == OK);
      const uint32_t color = 0xff000000 | ((frame + 1) * 0x102030);
      auto completion =
          renderer.render(GraphicBuffer::from(nativeBuffer), color);
      assert(window->queueBuffer(window, nativeBuffer, completion->dup()) ==
             OK);
      assert(consumer->updateTexImage() == OK);
      assert(consumer->getCurrentBuffer()->getWidth() == width);
      GLuint fbo;
      glGenFramebuffers(1, &fbo);
      glBindFramebuffer(GL_FRAMEBUFFER, fbo);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                             GL_TEXTURE_2D, texture, 0);
      assert(glCheckFramebufferStatus(GL_FRAMEBUFFER) ==
             GL_FRAMEBUFFER_COMPLETE);
      uint32_t pixel = 0;
      glReadPixels(width / 2, height / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                   &pixel);
      assert(glGetError() == GL_NO_ERROR && pixel == color);
      glDeleteFramebuffers(1, &fbo);
    }
    assert(native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL) == OK);
    consumer->abandon();
    glDeleteTextures(1, &texture);
  }
  assert(azk_graphics_live_buffers() == 0);
  std::cout << "PASS stock GLConsumer imports GPU images, waits on fences and "
               "reuses slots across resize"
            << std::endl;
  std::cout << "PASS remote stock BufferQueue "
               "dequeue/request/queue/acquire/release, slot reuse and resize"
            << std::endl;
  std::cout
      << "PASS GPU-written pixels and real sync_file acquire fences cross AZKB"
      << std::endl;
  auto death = sp<Death>::make();
  assert(producerBinder->linkToDeath(death) == OK);
  std::cout << "READY_DEATH" << std::endl;
  const auto deadline = std::chrono::steady_clock::now() + 5s;
  while (!death->died && std::chrono::steady_clock::now() < deadline)
    std::this_thread::sleep_for(5ms);
  assert(death->died);
  int value = 0;
  assert(producer->query(NATIVE_WINDOW_WIDTH, &value) == DEAD_OBJECT);
  std::cout << "PASS queue owner death notifies client and fails further calls"
            << std::endl;
}
