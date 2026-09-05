#include <android/binder_manager.h>
#include <android/gui/BnFpsListener.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>
#include <xf86drm.h>
#include <xf86drmMode.h>

#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <thread>
using namespace android;
using namespace std::chrono_literals;
constexpr uint32_t EXCHANGE = 1, NESTED = 2, GRAPHICS = 3;
static void pass(const char* s) { std::cout << "PASS " << s << std::endl; }
struct Listener final : gui::BnFpsListener {
  std::mutex mutex;
  std::condition_variable cv;
  std::vector<float> values;
  int pid = 0, uid = -1;
  binder::Status onFpsReported(float fps) override {
    std::lock_guard lock(mutex);
    values.push_back(fps);
    pid = IPCThreadState::self()->getCallingPid();
    uid = IPCThreadState::self()->getCallingUid();
    cv.notify_all();
    return binder::Status::ok();
  }
  void wait(size_t count) {
    std::unique_lock lock(mutex);
    assert(cv.wait_for(lock, 5s, [&] { return values.size() == count; }));
  }
};

struct CpuMap {
  int fd;
  void* ptr;
  size_t size;
  CpuMap(int f, size_t n) : fd(f), size(n) {
    dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_RW};
    assert(ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
    ptr = mmap(nullptr, n, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(ptr != MAP_FAILED);
  }
  ~CpuMap() {
    assert(munmap(ptr, size) == 0);
    dma_buf_sync sync{DMA_BUF_SYNC_END | DMA_BUF_SYNC_RW};
    assert(ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
  }
};
struct Service final : BBinder {
  int ownUid = IPCThreadState::self()->getCallingUid();
  status_t onTransact(uint32_t code, const Parcel& in, Parcel* out,
                      uint32_t) override {
    if (code == GRAPHICS) {
      auto buffer = sp<GraphicBuffer>::make();
      auto fence = sp<Fence>::make();
      auto none = sp<Fence>::make();
      assert(in.read(*buffer) == OK);
      assert(in.read(*fence) == OK);
      assert(in.read(*none) == OK);
      assert(buffer->getWidth() == 16 && buffer->getHeight() == 16 &&
             buffer->getPixelFormat() == PIXEL_FORMAT_RGBA_8888);
      assert(buffer->getUsage() ==
             (uint64_t(1) << 40 | GraphicBuffer::USAGE_SW_READ_OFTEN));
      assert(buffer->getGenerationNumber() == 42);
      assert(buffer->handle && buffer->handle->numFds == 1 &&
             buffer->handle->numInts == 2 &&
             buffer->handle->data[1] == 0x415a4b42);
      assert(fence->isValid() && fence->wait(1000) == OK &&
             fence->getSignalTime() != Fence::SIGNAL_TIME_INVALID);
      assert(!none->isValid() && none->wait(0) == OK);
      assert(in.readInt32() == 0x56789abc);
      {
        CpuMap map(buffer->handle->data[0], buffer->handle->data[2]);
        assert(memcmp(map.ptr, "AZKB", 4) == 0);
        memcpy(map.ptr, "DONE", 4);
      }
      assert(out->write(*buffer) == OK);
      assert(out->write(*fence) == OK);
      return out->writeInt32(0x56789abc);
    }
    if (code == NESTED) {
      return out->writeInt32(in.readInt32() + 1);
    }
    if (code != EXCHANGE) return UNKNOWN_TRANSACTION;
    auto listenerBinder = in.readStrongBinder();
    auto nested = in.readStrongBinder();
    assert(listenerBinder && listenerBinder->remoteBinder());
    auto listener = interface_cast<gui::IFpsListener>(listenerBinder);
    for (float f : {59.5f, 60.f, 120.f})
      assert(listener->onFpsReported(f).isOk());
    int beforePid = IPCThreadState::self()->getCallingPid(),
        beforeUid = IPCThreadState::self()->getCallingUid();
    Parcel data, reply;
    data.writeInt32(40);
    assert(nested->transact(NESTED, data, &reply) == OK);
    assert(reply.readInt32() == 42);
    assert(beforePid == IPCThreadState::self()->getCallingPid() &&
           beforeUid == IPCThreadState::self()->getCallingUid());
    auto fence = sp<Fence>::make();
    assert(in.read(*fence) == OK);
    assert(fence->isValid());
    char c = 0;
    assert(read(fence->get(), &c, 1) == 1 && c == 'Q');
    assert(out->writeStrongBinder(listenerBinder) == OK);
    assert(out->writeInt32(beforePid) == OK);
    assert(out->writeInt32(beforeUid) == OK);
    assert(out->writeInt32(getpid()) == OK);
    assert(out->writeInt32(ownUid) == OK);
    assert(out->write(*fence) == OK);
    return out->writeInt32(0x12345678);
  }
};
struct Nested final : BBinder {
  sp<IBinder> server;
  std::thread::id expected;
  explicit Nested(const sp<IBinder>& b)
      : server(b), expected(std::this_thread::get_id()) {}
  status_t onTransact(uint32_t code, const Parcel& in, Parcel* out,
                      uint32_t) override {
    assert(code == NESTED);
    assert(expected == std::this_thread::get_id());
    int before = IPCThreadState::self()->getCallingPid();
    Parcel data, reply;
    data.writeInt32(in.readInt32() + 1);
    assert(server->transact(NESTED, data, &reply) == OK);
    assert(before == IPCThreadState::self()->getCallingPid());
    return out->writeInt32(reply.readInt32());
  }
};
struct Death final : IBinder::DeathRecipient {
  std::mutex mutex;
  std::condition_variable cv;
  int calls = 0;
  wp<IBinder> expected;
  explicit Death(const sp<IBinder>& b) : expected(b) {}
  void binderDied(const wp<IBinder>& b) override {
    std::lock_guard lock(mutex);
    assert(b == expected);
    calls++;
    cv.notify_all();
  }
  void wait() {
    std::unique_lock lock(mutex);
    assert(cv.wait_for(lock, 5s, [&] { return calls == 1; }));
  }
};
int main(int argc, char** argv) {
  if (argc > 1 && std::string(argv[1]) == "server") {
    auto service = sp<Service>::make();
    auto* n = service->acquireNative();
    assert(AServiceManager_addService(n, "dev.artzig.facade.v2") == OK);
    AIBinder_decStrong(n);
    std::cout << "ready" << std::endl;
    char c;
    while (std::cin.get(c)) {
    }
    return 0;
  }
  wp<Listener> weak;
  {
    auto local = sp<Listener>::make();
    weak = local;
    auto b = IInterface::asBinder(local);
    assert(b->localBinder() && !b->remoteBinder());
    auto iface = interface_cast<gui::IFpsListener>(b);
    assert(iface.get() == local.get());
    assert(weak.promote() == local);
    assert(iface->onFpsReported(30.f).isOk());
    assert(local->values == std::vector<float>{30.f});
  }
  assert(!weak.promote());
  pass("AOSP RefBase/sp/wp; local interface identity and destruction");
  auto* raw = AServiceManager_waitForService("dev.artzig.facade.v2");
  assert(raw);
  auto server = IBinder::fromNative(raw);
  assert(server->remoteBinder() && !server->localBinder());
  auto same = IBinder::fromNative(
      AServiceManager_waitForService("dev.artzig.facade.v2"));
  assert(server == same);
  same.clear();
  auto listener = sp<Listener>::make();
  auto nested = sp<Nested>::make(server);
  auto local = IInterface::asBinder(listener);
  Parcel data, reply;
  assert(data.writeStrongBinder(local) == OK);
  assert(data.writeStrongBinder(nested) == OK);
  int fds[2];
  assert(pipe(fds) == 0);
  assert(write(fds[1], "QR", 2) == 2);
  close(fds[1]);
  {
    auto fence = sp<Fence>::make(fds[0]);
    assert(data.write(*fence) == OK);
  }  // Original FD closed before transact.
  int ownPid = IPCThreadState::self()->getCallingPid(),
      ownUid = IPCThreadState::self()->getCallingUid();
  assert(server->transact(EXCHANGE, data, &reply) == OK);
  assert(reply.readStrongBinder() == local);
  assert(reply.readInt32() == getpid());
  assert(reply.readInt32() == ownUid);
  int serverPid = reply.readInt32(), serverUid = reply.readInt32();
  {
    auto fence = sp<Fence>::make();
    assert(reply.read(*fence) == OK);
    assert(fence->isValid());
    char c = 0;
    assert(read(fence->get(), &c, 1) == 1 && c == 'R');
  }
  assert(reply.readInt32() == 0x12345678);
  listener->wait(3);
  assert((listener->values == std::vector<float>{59.5f, 60.f, 120.f}));
  assert(listener->pid == 0 && listener->uid == serverUid && serverUid != ownUid);
  assert(IPCThreadState::self()->getCallingPid() == ownPid &&
         IPCThreadState::self()->getCallingUid() == ownUid);
  pass(
      "unmodified generated IFpsListener Bp/Bn through AZKB; ordered oneway "
      "callbacks and caller identity");
  pass(
      "nested synchronous callback returns on originating thread; caller "
      "context restored");
  pass(
      "canonical C++ Binder identity, remote Binder reply without application "
      "keepalive");
  pass(
      "unmodified AOSP Fence flatten/unflatten; pipe FD ownership and trailing "
      "data (not a sync fence)");
  Parcel bad, badReply;
  bad.writeInterfaceToken(String16(u"wrong.interface"));
  bad.writeFloat(1);
  assert(local->transact(1, bad, &badReply) == BAD_TYPE);
  Parcel unknown, unused;
  assert(server->transact(999, unknown, &unused) == UNKNOWN_TRANSACTION);
  pass("interface rejection and exact UNKNOWN_TRANSACTION status");

  // Use a real DMA-BUF allocation and a real sync_file. The hosted mapper only
  // clones opaque handles; this is not an Android gralloc or GPU import test.
  int gpu = open("/dev/dri/card1", O_RDWR | O_CLOEXEC);
  assert(gpu >= 0);
  drm_mode_create_dumb dumb{};
  dumb.width = 16;
  dumb.height = 16;
  dumb.bpp = 32;
  assert(drmIoctl(gpu, DRM_IOCTL_MODE_CREATE_DUMB, &dumb) == 0);
  int dma = -1;
  assert(drmPrimeHandleToFD(gpu, dumb.handle, DRM_CLOEXEC | DRM_RDWR, &dma) ==
         0);
  uint32_t syncobj = 0;
  assert(drmSyncobjCreate(gpu, DRM_SYNCOBJ_CREATE_SIGNALED, &syncobj) == 0);
  int syncfd = -1;
  assert(drmSyncobjExportSyncFile(gpu, syncobj, &syncfd) == 0);
  assert(drmSyncobjDestroy(gpu, syncobj) == 0);
  {
    CpuMap map(dma, dumb.size);
    memcpy(map.ptr, "AZKB", 4);
  }
  uint64_t bufferId = 0;
  Parcel graphics, graphicsReply;
  {
    auto* handle = native_handle_create(1, 2);
    assert(handle);
    handle->data[0] = dma;
    handle->data[1] = 0x415a4b42;
    handle->data[2] = dumb.size;
    auto buffer = sp<GraphicBuffer>::make(
        handle, GraphicBuffer::CLONE_HANDLE, 16, 16, PIXEL_FORMAT_RGBA_8888, 1,
        (uint64_t(1) << 40 | GraphicBuffer::USAGE_SW_READ_OFTEN),
        dumb.pitch / 4);
    assert(buffer->initCheck() == OK);
    buffer->setGenerationNumber(42);
    bufferId = buffer->getId();
    native_handle_close(handle);
    native_handle_delete(handle);
    auto fence = sp<Fence>::make(syncfd);
    assert(graphics.write(*buffer) == OK);
    assert(graphics.write(*fence) == OK);
    assert(graphics.write(*Fence::NO_FENCE) == OK);
    graphics.writeInt32(0x56789abc);
  }
  drm_mode_destroy_dumb destroy{};
  destroy.handle = dumb.handle;
  assert(drmIoctl(gpu, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy) == 0);
  close(gpu);
  assert(server->transact(GRAPHICS, graphics, &graphicsReply) == OK);
  {
    auto buffer = sp<GraphicBuffer>::make();
    auto fence = sp<Fence>::make();
    assert(graphicsReply.read(*buffer) == OK);
    assert(graphicsReply.read(*fence) == OK);
    assert(buffer->getId() == bufferId && buffer->getGenerationNumber() == 42);
    assert(buffer->getStride() == dumb.pitch / 4);
    assert(fence->wait(1000) == OK);
    assert(graphicsReply.readInt32() == 0x56789abc);
    CpuMap map(buffer->handle->data[0], buffer->handle->data[2]);
    assert(memcmp(map.ptr, "DONE", 4) == 0);
  }
  pass(
      "unmodified AOSP GraphicBuffer/Fence round trip: real DMA-BUF pixels, "
      "sync_file, NO_FENCE, metadata and ownership");
  auto dead = sp<Death>::make(server), unlinked = sp<Death>::make(server);
  assert(server->linkToDeath(dead) == OK);
  assert(server->linkToDeath(unlinked) == OK);
  assert(server->unlinkToDeath(unlinked) == OK);
  std::cout << "READY_DEATH" << std::endl;
  dead->wait();
  std::this_thread::sleep_for(50ms);
  assert(unlinked->calls == 0);
  pass("C++ death recipient identity and unlink across service process exit");
}
