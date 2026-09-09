// Keep host NDK assertion macros contained before loading generated headers.
#include <azk/binder.h>
#include <cassert>

#include <android/binder_manager.h>
#include <android/binder_libbinder.h>
#include <android/binder_process.h>
#include <android/gui/BnFpsListener.h>
#include <binder/IPCThreadState.h>
#include <binder/IResultReceiver.h>
#include <binder/IMemory.h>
#include <binder/MemoryDealer.h>
#include <binder/Parcel.h>
#include <binder/ParcelFileDescriptor.h>
#include <fcntl.h>
#include <poll.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <thread>
using namespace android;
using namespace std::chrono_literals;
constexpr uint32_t EXCHANGE = 1, NESTED = 2, GRAPHICS = 3;
constexpr uint32_t HOLD_WORKER = 8, MONITOR_POOL = 9, BLOB_ROUND_TRIP = 10;
constexpr uint32_t RETAIN_RELEASE_TARGET = 12, DROP_RELEASE_TARGET = 13;
constexpr uint32_t QUOTA_ONEWAY = 14, QUOTA_PROGRESS = 15, PREPARE_WAITERS = 16;
constexpr int POOL_WORKERS = 32;
static void pass(const char *s) { std::cout << "PASS " << s << std::endl; }
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

// Same size/fd framing used by AOSP Fence, with ownership visible to the test.
struct PipeValue : Flattenable<PipeValue> {
  int fd = -1;
  explicit PipeValue(int value = -1) : fd(value) {}
  ~PipeValue() {
    if (fd >= 0)
      close(fd);
  }
  size_t getFlattenedSize() const { return 4; }
  size_t getFdCount() const { return fd >= 0 ? 1 : 0; }
  status_t flatten(void *&p, size_t &n, int *&f, size_t &count) const {
    if (n < 4 || count < getFdCount())
      return NO_MEMORY;
    int32_t present = fd >= 0;
    memcpy(p, &present, 4);
    p = static_cast<char *>(p) + 4;
    n -= 4;
    if (present) {
      *f++ = fd;
      --count;
    }
    return OK;
  }
  status_t unflatten(const void *&p, size_t &n, const int *&f, size_t &count) {
    if (n < 4)
      return BAD_VALUE;
    int32_t present;
    memcpy(&present, p, 4);
    p = static_cast<const char *>(p) + 4;
    n -= 4;
    if (present) {
      if (!count)
        return BAD_VALUE;
      fd = *f++;
      --count;
    }
    return OK;
  }
  bool isValid() const { return fd >= 0; }
  int get() const { return fd; }
};
static std::atomic<int> remoteDestroyed{0};
struct RemoteTracked final : BBinder {
  ~RemoteTracked() override { ++remoteDestroyed; }
};
struct Service final : BBinder {
  std::atomic<int> quotaCompleted = 0;
  std::mutex releaseMutex;
  sp<IBinder> releaseTarget;
  int ownUid = IPCThreadState::self()->getCallingUid();
  status_t dump(int fd, const Vector<String16> &args) override {
    if (args.size() != 2 || args[0] != String16("--layers") ||
        args[1] != String16(u"task \u03bb"))
      return BAD_VALUE;
    assert(IPCThreadState::self()->getCallingPid() != getpid());
    return write(fd, "layers\n", 7) == 7 ? OK : UNKNOWN_ERROR;
  }
  status_t onTransact(uint32_t code, const Parcel &in, Parcel *out,
                      uint32_t flags) override {
    if (code == PREPARE_WAITERS) {
      const int gate = dup(in.readFileDescriptor());
      assert(gate >= 0);
      std::thread([gate] {
        pollfd ready{gate, POLLIN, 0};
        assert(poll(&ready, 1, 10000) == 1);
        char byte;
        assert(read(gate, &byte, 1) == 1);
        close(gate);
        auto object = sp<BBinder>::make();
        auto *native = AIBinder_fromPlatformBinder(object);
        assert(AServiceManager_addService(native, "dev.artzig.facade.v2.waiters") == OK);
        AIBinder_decStrong(native);
      }).detach();
      return OK;
    }
    if (code == QUOTA_ONEWAY) {
      assert(flags & IBinder::FLAG_ONEWAY);
      const int sequence = in.readInt32();
      assert(sequence == quotaCompleted);
      if (sequence == 0) {
        const int fd = in.readFileDescriptor();
        pollfd ready{fd, POLLIN, 0};
        assert(poll(&ready, 1, 10000) == 1);
        char byte;
        assert(read(fd, &byte, 1) == 1);
      }
      ++quotaCompleted;
      return OK;
    }
    if (code == QUOTA_PROGRESS) return out->writeInt32(quotaCompleted);
    if (code == RETAIN_RELEASE_TARGET) {
      std::lock_guard lock(releaseMutex);
      assert(!releaseTarget);
      releaseTarget = in.readStrongBinder();
      assert(releaseTarget && releaseTarget->remoteBinder());
      return OK;
    }
    if (code == DROP_RELEASE_TARGET) {
      sp<IBinder> target;
      {
        std::lock_guard lock(releaseMutex);
        target = std::move(releaseTarget);
      }
      assert(target);
      target.clear();
      return OK;
    }
    if (code == 11) {
      auto memory = interface_cast<IMemory>(in.readStrongBinder());
      assert(memory && memory->size() == 8192 && memory->offset() > 0);
      auto *bytes = static_cast<uint8_t *>(memory->unsecurePointer());
      assert(bytes);
      for (size_t i = 0; i < memory->size(); ++i) assert(bytes[i] == uint8_t(i));
      bytes[0] = 93;
      return out->writeStrongBinder(IInterface::asBinder(memory));
    }
    if (code == BLOB_ROUND_TRIP) {
      const int32_t size = in.readInt32();
      assert(size > 16 * 1024);
      Parcel::ReadableBlob blob;
      assert(in.readBlob(size, &blob) == OK && blob.fd() >= 0);
      const auto *bytes = static_cast<const uint8_t *>(blob.data());
      for (int32_t i = 0; i < size; ++i) assert(bytes[i] == uint8_t(i));
      if (blob.isMutable()) {
        assert(blob.mutableData());
        static_cast<uint8_t *>(blob.mutableData())[0] = 93;
      } else {
        assert(!blob.mutableData());
      }
      assert(out->writeInt32(size) == OK);
      return out->writeDupImmutableBlobFileDescriptor(blob.fd());
    }
    if (code == HOLD_WORKER) {
      const int release = in.readFileDescriptor();
      const int ready = in.readFileDescriptor();
      assert(write(ready, "R", 1) == 1);
      char signal;
      assert(read(release, &signal, 1) == 1 && signal == 'R');
      return out->writeInt32(42);
    }
    if (code == MONITOR_POOL) {
      // Pinned Watchdog.BinderThreadMonitor runs on FgThread, outside the
      // Binder pool it monitors. Arm the same external caller before saturation.
      const int result = dup(in.readFileDescriptor());
      const int start = dup(in.readFileDescriptor());
      assert(result >= 0 && start >= 0);
      std::thread([result, start] {
        char signal;
        assert(read(start, &signal, 1) == 1 && signal == 'S');
        assert(write(result, "S", 1) == 1);
        IPCThreadState::self()->blockUntilThreadAvailable();
        assert(write(result, "A", 1) == 1);
        close(start);
        close(result);
      }).detach();
      return OK;
    }
    if (code == 4)
      return out->writeStrongBinder(sp<RemoteTracked>::make());
    if (code == 5)
      return out->writeInt32(remoteDestroyed.load());
    if (code == 6) {
      os::ParcelFileDescriptor descriptor;
      auto status = descriptor.readFromParcel(&in);
      return status ? status : descriptor.writeToParcel(out);
    }
    if (code == 7) {
      std::optional<std::vector<std::optional<std::string>>> values;
      const auto status = in.readUtf8VectorFromUtf16Vector(&values);
      return status ? status : out->writeUtf8VectorAsUtf16Vector(values);
    }
    if (code == NESTED) {
      return out->writeInt32(in.readInt32() + 1);
    }
    if (code != EXCHANGE)
      return BBinder::onTransact(code, in, out, flags);
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
    auto fence = std::make_unique<PipeValue>();
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
  explicit Nested(const sp<IBinder> &b)
      : server(b), expected(std::this_thread::get_id()) {}
  status_t onTransact(uint32_t code, const Parcel &in, Parcel *out,
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
  explicit Death(const sp<IBinder> &b) : expected(b) {}
  void binderDied(const wp<IBinder> &b) override {
    std::lock_guard lock(mutex);
    assert(b == expected);
    // This synchronous broker call would deadlock on the socket reader thread.
    assert(AServiceManager_checkService("dev.artzig.facade.v2") == nullptr);
    calls++;
    cv.notify_all();
  }
  void wait() {
    std::unique_lock lock(mutex);
    assert(cv.wait_for(lock, 5s, [&] { return calls == 1; }));
  }
};
void parcelTests();
void blobTests();
struct ReleaseState {
  std::mutex gate, progress;
  std::condition_variable changed;
  bool entered = false, destroyed = false, replied = false;
};
struct BlockingRelease final : BBinder {
  std::shared_ptr<ReleaseState> state;
  explicit BlockingRelease(std::shared_ptr<ReleaseState> value) : state(std::move(value)) {}
  ~BlockingRelease() override {
    {
      std::lock_guard lock(state->progress);
      state->entered = true;
      state->changed.notify_all();
    }
    // SurfaceFlinger LayerHandle destruction takes its state lock. Its owner
    // can simultaneously wait for a remote producer reply while holding it.
    std::lock_guard gate(state->gate);
    std::lock_guard lock(state->progress);
    state->destroyed = true;
    state->changed.notify_all();
  }
};
static void releaseReaderProgressTest(const sp<IBinder>& server) {
  auto state = std::make_shared<ReleaseState>();
  std::unique_lock gate(state->gate);
  {
    auto target = sp<BlockingRelease>::make(state);
    Parcel request, response;
    assert(request.writeStrongBinder(target) == OK);
    assert(server->transact(RETAIN_RELEASE_TARGET, request, &response) == OK);
  }
  {
    Parcel request;
    assert(server->transact(DROP_RELEASE_TARGET, request, nullptr, IBinder::FLAG_ONEWAY) == OK);
  }
  {
    std::unique_lock progress(state->progress);
    assert(state->changed.wait_for(progress, 5s, [&] { return state->entered; }));
    assert(!state->destroyed);
  }
  std::thread caller([&] {
    Parcel request, response;
    assert(request.writeInt32(41) == OK);
    assert(server->transact(NESTED, request, &response) == OK);
    assert(response.readInt32() == 42);
    std::lock_guard progress(state->progress);
    state->replied = true;
    state->changed.notify_all();
  });
  bool repliedWhileDestructorBlocked;
  {
    std::unique_lock progress(state->progress);
    repliedWhileDestructorBlocked = state->changed.wait_for(progress, 1s, [&] { return state->replied; });
    assert(!state->destroyed);
  }
  // Always break the deliberate lock cycle before asserting, including the
  // old reader-side destruction path used as a negative control.
  gate.unlock();
  {
    std::unique_lock progress(state->progress);
    assert(state->changed.wait_for(progress, 5s, [&] { return state->destroyed && state->replied; }));
  }
  caller.join();
  assert(repliedWhileDestructorBlocked && "export destruction must not block Binder reply reception");
  pass("exported object destruction can wait for a lock while Binder replies progress");
}

static void poolMonitorTest(const sp<IBinder>& server) {
  IPCThreadState::self()->blockUntilThreadAvailable();
  int release[2], ready[2], monitor[2], start[2];
  assert(pipe(release) == 0 && pipe(ready) == 0 && pipe(monitor) == 0 && pipe(start) == 0);
  Parcel request, reply;
  assert(request.writeFileDescriptor(monitor[1]) == OK);
  assert(request.writeFileDescriptor(start[0]) == OK);
  assert(server->transact(MONITOR_POOL, request, &reply) == OK);
  std::vector<std::thread> callers;
  for (int i = 0; i < POOL_WORKERS; ++i) {
    callers.emplace_back([&] {
      Parcel request, reply;
      assert(request.writeFileDescriptor(release[0]) == OK);
      assert(request.writeFileDescriptor(ready[1]) == OK);
      assert(server->transact(HOLD_WORKER, request, &reply) == OK);
      assert(reply.readInt32() == 42);
    });
  }
  auto receive = [](int fd, char expected) {
    pollfd event{fd, POLLIN, 0};
    assert(poll(&event, 1, 5000) == 1 && (event.revents & POLLIN));
    char value;
    assert(read(fd, &value, 1) == 1 && value == expected);
  };
  for (int i = 0; i < POOL_WORKERS; ++i) receive(ready[0], 'R');
  assert(write(start[1], "S", 1) == 1);
  receive(monitor[0], 'S');
  pollfd waiting{monitor[0], POLLIN, 0};
  assert(poll(&waiting, 1, 200) == 0);
  assert(write(release[1], "R", 1) == 1);
  receive(monitor[0], 'A');
  for (int i = 1; i < POOL_WORKERS; ++i) assert(write(release[1], "R", 1) == 1);
  for (auto& caller : callers) caller.join();
  for (int fd : {release[0], release[1], ready[0], ready[1], monitor[0], monitor[1], start[0], start[1]}) close(fd);
  pass("Watchdog pool monitor blocks at capacity and resumes when one worker completes");
}
static void onewayQuotaTest(const sp<IBinder>& server) {
  int gate[2];
  assert(pipe2(gate, O_CLOEXEC) == 0);
  int accepted = 0;
  status_t rejected = OK;
  const auto start = std::chrono::steady_clock::now();
  for (; accepted < 4096; ++accepted) {
    Parcel input;
    assert(input.writeInt32(accepted) == OK);
    if (accepted == 0) assert(input.writeFileDescriptor(gate[0], false) == OK);
    rejected = server->transact(QUOTA_ONEWAY, input, nullptr, IBinder::FLAG_ONEWAY);
    if (rejected != OK) break;
  }
  Parcel progress, response;
  assert(server->transact(QUOTA_PROGRESS, progress, &response) == OK);
  const bool controlProgress = response.readInt32() == 0;
  const bool prompt = std::chrono::steady_clock::now() - start < 5s;
  // Always unblock and drain the receiver before asserting the negative case.
  assert(write(gate[1], "x", 1) == 1);
  close(gate[0]);
  close(gate[1]);
  int completed = 0;
  for (int i = 0; i < 500 && completed != accepted; ++i) {
    Parcel request, reply;
    assert(server->transact(QUOTA_PROGRESS, request, &reply) == OK);
    completed = reply.readInt32();
    if (completed != accepted) std::this_thread::sleep_for(10ms);
  }
  assert(completed == accepted);
  const bool bounded = accepted > 0 && accepted < 4096;
  assert(bounded && rejected == FAILED_TRANSACTION && prompt && controlProgress);
  // Completion returns admission credit and preserves FIFO through the retry.
  Parcel retry;
  assert(retry.writeInt32(accepted) == OK);
  assert(server->transact(QUOTA_ONEWAY, retry, nullptr, IBinder::FLAG_ONEWAY) == OK);
  for (int i = 0; i < 500 && completed != accepted + 1; ++i) {
    Parcel request, reply;
    assert(server->transact(QUOTA_PROGRESS, request, &reply) == OK);
    completed = reply.readInt32();
    if (completed != accepted + 1) std::this_thread::sleep_for(10ms);
  }
  assert(completed == accepted + 1);
  pass("oneway admission bounded until handler completion; synchronous progress and FIFO retry");
}

static void serviceRequestQuotaTest(const sp<IBinder>& server) {
  int gate[2];
  assert(pipe2(gate, O_CLOEXEC) == 0);
  {
    Parcel input, output;
    assert(input.writeFileDescriptor(gate[0], false) == OK);
    assert(server->transact(PREPARE_WAITERS, input, &output) == OK);
  }
  close(gate[0]);
  std::mutex mutex;
  std::condition_variable changed;
  int failed = 0, succeeded = 0;
  std::vector<std::thread> callers;
  for (int i = 0; i < 129; ++i) callers.emplace_back([&] {
    auto *binder = AServiceManager_waitForService("dev.artzig.facade.v2.waiters");
    if (binder) AIBinder_decStrong(binder);
    std::lock_guard lock(mutex);
    if (binder) ++succeeded; else ++failed;
    changed.notify_all();
  });
  bool bounded;
  {
    std::unique_lock lock(mutex);
    bounded = changed.wait_for(lock, 2s, [&] { return failed > 0; });
    assert(succeeded == 0);
  }
  // The server publishes from its independently signalled thread. Always
  // release and join the callers before asserting the negative old-runtime case.
  assert(write(gate[1], "x", 1) == 1);
  close(gate[1]);
  for (auto &caller : callers) caller.join();
  assert(bounded && failed > 0 && succeeded > 0 && succeeded + failed == 129);
  auto *retry = AServiceManager_checkService("dev.artzig.facade.v2.waiters");
  assert(retry);
  AIBinder_decStrong(retry);
  pass("outstanding service requests are bounded and completed waits return capacity");
}

static void deathLinkQuotaTest(const sp<IBinder>& server) {
  auto *native = AIBinder_fromPlatformBinder(server);
  assert(native);
  std::atomic<int> unlinked = 0;
  auto onDied = +[](void *) { assert(false && "live service unexpectedly died"); };
  auto onUnlinked = +[](void *cookie) { ++*static_cast<std::atomic<int> *>(cookie); };
  auto *recipient = AIBinder_DeathRecipient_new(onDied);
  AIBinder_DeathRecipient_setOnUnlinked(recipient, onUnlinked);
  int accepted = 0;
  status_t result = OK;
  for (; accepted < 16384; ++accepted) {
    result = AIBinder_linkToDeath(native, recipient, &unlinked);
    if (result != OK) break;
  }
  assert(accepted > 0 && accepted < 16384 && result == NO_MEMORY);
  // A failed registration still invokes the pinned onUnlinked contract.
  assert(unlinked == 1);
  AIBinder_DeathRecipient_delete(recipient);
  assert(unlinked == accepted + 1);
  recipient = AIBinder_DeathRecipient_new(onDied);
  AIBinder_DeathRecipient_setOnUnlinked(recipient, onUnlinked);
  assert(AIBinder_linkToDeath(native, recipient, &unlinked) == OK);
  assert(AIBinder_unlinkToDeath(native, recipient, &unlinked) == OK);
  assert(unlinked == accepted + 2);
  AIBinder_DeathRecipient_delete(recipient);
  AIBinder_decStrong(native);
  pass("death subscription quota, failed onUnlinked ordering and released capacity");
}

struct ReleasePressureState {
  std::mutex gate;
  std::atomic<int> destroyed = 0;
};
struct ReleasePressureObject final : BBinder {
  std::shared_ptr<ReleasePressureState> state;
  explicit ReleasePressureObject(std::shared_ptr<ReleasePressureState> value) : state(std::move(value)) {}
  ~ReleasePressureObject() override {
    std::lock_guard lock(state->gate);
    ++state->destroyed;
  }
};
static void releasePressureTest() {
  auto server = IBinder::fromNative(AServiceManager_waitForService("dev.artzig.facade.v2.pressure"));
  assert(server);
  auto state = std::make_shared<ReleasePressureState>();
  std::unique_lock gate(state->gate);
  int created = 0;
  bool retired = false;
  for (; created < 8192;) {
    {
      auto target = sp<ReleasePressureObject>::make(state);
      ++created;
      Parcel input, output;
      assert(input.writeStrongBinder(target) == OK);
      const status_t result = server->transact(RETAIN_RELEASE_TARGET, input, &output);
      if (result == DEAD_OBJECT) {
        retired = true;
        gate.unlock();
      } else assert(result == OK);
    }
    if (retired) break;
    // Only the remote holder remains before it drops this export. Therefore
    // its eventual destructor runs on a Binder cleanup worker, never here.
    Parcel input, output;
    const status_t result = server->transact(DROP_RELEASE_TARGET, input, &output);
    if (result == DEAD_OBJECT) {
      retired = true;
      break;
    }
    assert(result == OK);
    if (!server->isBinderAlive()) {
      retired = true;
      break;
    }
  }
  // Drain before asserting, including on the old unbounded implementation.
  if (gate.owns_lock()) gate.unlock();
  for (int i = 0; i < 1000 && state->destroyed != created; ++i)
    std::this_thread::sleep_for(10ms);
  assert(state->destroyed == created);
  assert(retired && created < 8192);
  pass("blocked export destructors have bounded retained work and clean session retirement");
}

int main(int argc, char **argv) {
  if (argc > 1 && (std::string(argv[1]) == "server" || std::string(argv[1]) == "server-pressure")) {
    assert(ABinderProcess_setThreadPoolMaxThreadCount(POOL_WORKERS - 1));
    auto service = sp<Service>::make();
    auto *n = service->acquireNative();
    const char *name = std::string(argv[1]) == "server-pressure" ? "dev.artzig.facade.v2.pressure" : "dev.artzig.facade.v2";
    assert(AServiceManager_addService(n, name) == OK);
    AIBinder_decStrong(n);
    std::cout << "ready" << std::endl;
    char c;
    while (std::cin.get(c)) {
    }
    return 0;
  }
  if (argc > 1 && std::string(argv[1]) == "release-pressure") {
    releasePressureTest();
    return 0;
  }
  parcelTests();
  blobTests();
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
  {
    auto owner = sp<BBinder>::make();
    int tag, detachedTag, weakTag, cleaned = 0;
    auto cleanup = [](const void *, void *, void *cookie) {
      ++*static_cast<int *>(cookie);
    };
    assert(owner->attachObject(&tag, &tag, &cleaned, cleanup) == nullptr);
    assert(owner->attachObject(&tag, &detachedTag, &cleaned, cleanup) == &tag);
    assert(owner->findObject(&tag) == &tag);
    assert(owner->attachObject(&detachedTag, &detachedTag, &cleaned, cleanup) ==
           nullptr);
    assert(owner->detachObject(&detachedTag) == &detachedTag && cleaned == 0);
    std::atomic<int> made = 0;
    auto make = [](const void *args) -> sp<IBinder> {
      ++*static_cast<std::atomic<int> *>(const_cast<void *>(args));
      return sp<BBinder>::make();
    };
    std::array<sp<IBinder>, 8> views;
    std::array<std::thread, 8> workers;
    for (size_t i = 0; i < workers.size(); ++i)
      workers[i] = std::thread([&, i] {
        views[i] = owner->lookupOrCreateWeak(&weakTag, make, &made);
      });
    for (auto &worker : workers)
      worker.join();
    for (const auto &view : views)
      assert(view == views[0]);
    assert(made == 1);
    wp<IBinder> weakView = views[0];
    for (auto &view : views)
      view.clear();
    assert(!weakView.promote());
    auto replacement = owner->lookupOrCreateWeak(&weakTag, make, &made);
    assert(replacement && made == 2);
    owner.clear();
    assert(cleaned == 1 && replacement->localBinder());
    pass("Binder attachments clean once; concurrent delegates share a weak "
         "cached identity");
  }
  auto *raw = AServiceManager_waitForService("dev.artzig.facade.v2");
  assert(raw);
  auto server = IBinder::fromNative(raw);
  releaseReaderProgressTest(server);
  poolMonitorTest(server);
  onewayQuotaTest(server);
  deathLinkQuotaTest(server);
  serviceRequestQuotaTest(server);
  {
    const std::optional<std::vector<std::optional<std::string>>> values{
        std::vector<std::optional<std::string>>{std::nullopt, std::string{},
                                                std::string("task \xce\xbb")}};
    Parcel request, reply;
    assert(request.writeUtf8VectorAsUtf16Vector(values) == OK);
    assert(server->transact(7, request, &reply) == OK);
    std::optional<std::vector<std::optional<std::string>>> returned;
    assert(reply.readUtf8VectorFromUtf16Vector(&returned) == OK);
    assert(returned == values && reply.dataAvail() == 0);
    pass("nullable Unicode string vectors across AZKB service process");
  }

  assert(server->remoteBinder() && !server->localBinder());
  {
    auto *native = AIBinder_fromPlatformBinder(server);
    auto converted = AIBinder_toPlatformBinder(native);
    assert(converted == server);
    AIBinder_decStrong(native);
    assert(converted->pingBinder() == OK);
    assert(AIBinder_toPlatformBinder(nullptr) == nullptr);
    assert(AIBinder_fromPlatformBinder(nullptr) == nullptr);
    pass("NDK/platform Binder conversion preserves remote identity and "
         "ownership");
  }
  {
    int descriptors[2];
    assert(pipe2(descriptors, O_CLOEXEC | O_NONBLOCK) == 0);
    Vector<String16> args;
    args.add(String16("--layers"));
    args.add(String16(u"task \u03bb"));
    assert(server->dump(descriptors[1], args) == OK);
    char content[7];
    assert(read(descriptors[0], content, sizeof(content)) == sizeof(content));
    assert(std::string(content, sizeof(content)) == "layers\n");
    args.clear();
    assert(server->dump(descriptors[1], args) == BAD_VALUE);
    // Serialization borrows the caller FD; the receiver owns its transferred
    // copy only for the transaction. Closing here must produce EOF.
    assert(fcntl(descriptors[1], F_GETFD) >= 0);
    close(descriptors[1]);
    assert(read(descriptors[0], content, sizeof(content)) == 0);
    close(descriptors[0]);
    pass("remote dump Unicode arguments, status, identity and FD ownership");
  }
  auto same = IBinder::fromNative(
      AServiceManager_waitForService("dev.artzig.facade.v2"));
  assert(server == same);
  same.clear();
  // Stock IMemory/IMemoryHeap protocol, including a nonzero allocation offset.
  {
    auto dealer = sp<MemoryDealer>::make(64 * 1024, "binder-facade-memory");
    auto first = dealer->allocate(32 * 1024);
    auto memory = dealer->allocate(8192);
    assert(first && memory && !dealer->allocate(64 * 1024));
    auto *bytes = static_cast<uint8_t *>(memory->unsecurePointer());
    for (size_t i = 0; i < memory->size(); ++i) bytes[i] = uint8_t(i);
    // The allocation retains the dealer and heap after the creator releases them.
    first.clear();
    dealer.clear();
    Parcel request, response;
    assert(request.writeStrongBinder(IInterface::asBinder(memory)) == OK);
    assert(server->transact(11, request, &response) == OK);
    auto returned = interface_cast<IMemory>(response.readStrongBinder());
    assert(returned == memory && bytes[0] == 93);
    pass("stock shared-memory Binder, allocation lifetime and remote writes");
  }
  for (bool mutableCopy : {false, true}) {
    // Larger than the broker's inline frame limit: bytes must travel through an FD.
    constexpr int size = 2 * 1024 * 1024;
    Parcel request, response;
    assert(request.writeInt32(size) == OK);
    Parcel::WritableBlob writer;
    assert(request.writeBlob(size, mutableCopy, &writer) == OK);
    assert(writer.fd() >= 0 && writer.isMutable() == mutableCopy);
    auto *bytes = static_cast<uint8_t *>(writer.data());
    for (int i = 0; i < size; ++i) bytes[i] = uint8_t(i);
    assert(request.dataSize() < 128 && request.hasFileDescriptors());
    assert(server->transact(BLOB_ROUND_TRIP, request, &response) == OK);
    assert(bytes[0] == (mutableCopy ? 93 : 0));
    writer.release();
    request.freeData();
    assert(response.readInt32() == size);
    Parcel::ReadableBlob reader;
    assert(response.readBlob(size, &reader) == OK);
    assert(!reader.isMutable() && reader.mutableData() == nullptr);
    bytes = const_cast<uint8_t *>(static_cast<const uint8_t *>(reader.data()));
    assert(bytes[0] == (mutableCopy ? 93 : 0) && bytes[size - 1] == 255);
    const int receivedFd = reader.fd();
    response.freeData();
    assert(fcntl(receivedFd, F_GETFD) == -1);
    assert(bytes[size - 1] == 255); // A mapping outlives the Parcel's FD.
    reader.release();
  }
  pass("large immutable and mutable blobs cross Binder; mapping and FD lifetimes");
  {
    // Receiving a Binder owns its delivery even if no client ever reads it.
    Parcel request, response;
    assert(server->transact(4, request, &response) == OK);
    response.freeData();
    auto deadline = std::chrono::steady_clock::now() + 5s;
    int count = 0;
    do {
      assert(server->transact(5, request, &response) == OK);
      count = response.readInt32();
      if (count == 1)
        break;
      std::this_thread::sleep_for(10ms);
    } while (std::chrono::steady_clock::now() < deadline);
    assert(count == 1);
    pass("unread received Binder releases broker delivery and remote object");
  }
  {
    Parcel tooLarge, reply;
    tooLarge.setDataSize(1024 * 1024 + 1);
    assert(server->transact(EXCHANGE, tooLarge, &reply) == FAILED_TRANSACTION);
    assert(server->pingBinder() == OK);
    Parcel manyFds;
    int fds[2];
    assert(pipe(fds) == 0);
    for (int i = 0; i < 65; i++)
      assert(manyFds.writeDupFileDescriptor(fds[0]) == OK);
    close(fds[0]);
    close(fds[1]);
    assert(server->transact(EXCHANGE, manyFds, &reply) == FAILED_TRANSACTION);
    assert(server->pingBinder() == OK);
    pass("transport limits fail without exports, disconnect or descriptor "
         "overflow");
  }
  auto listener = sp<Listener>::make();
  {
    int fds[2];
    assert(pipe(fds) == 0);
    base::unique_fd writer(fds[1]);
    Parcel request, response;
    {
      os::ParcelFileDescriptor original{binder::unique_fd(fds[0])};
      assert(original.writeToParcel(&request) == OK);
    }
    assert(server->transact(6, request, &response) == OK);
    os::ParcelFileDescriptor returned;
    assert(returned.readFromParcel(&response) == OK);
    request.freeData();
    response.freeData();
    assert((fcntl(returned.get(), F_GETFD) & FD_CLOEXEC) != 0);
    assert(write(writer.get(), "p", 1) == 1);
    char value = 0;
    assert(read(returned.get(), &value, 1) == 1 && value == 'p');
    pass("stock ParcelFileDescriptor survives cross-process parcel ownership");
  }
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
    auto fence = std::make_unique<PipeValue>(fds[0]);
    assert(data.write(*fence) == OK);
  } // Original FD closed before transact.
  int ownPid = IPCThreadState::self()->getCallingPid(),
      ownUid = IPCThreadState::self()->getCallingUid();
  assert(server->transact(EXCHANGE, data, &reply) == OK);
  assert(reply.readStrongBinder() == local);
  assert(reply.readInt32() == getpid());
  assert(reply.readInt32() == ownUid);
  int serverPid = reply.readInt32(), serverUid = reply.readInt32();
  {
    auto fence = std::make_unique<PipeValue>();
    assert(reply.read(*fence) == OK);
    assert(fence->isValid());
    char c = 0;
    assert(read(fence->get(), &c, 1) == 1 && c == 'R');
  }
  assert(reply.readInt32() == 0x12345678);
  listener->wait(3);
  assert((listener->values == std::vector<float>{59.5f, 60.f, 120.f}));
  assert(listener->pid == 0 && listener->uid == serverUid &&
         serverUid != ownUid);
  assert(IPCThreadState::self()->getCallingPid() == ownPid &&
         IPCThreadState::self()->getCallingUid() == ownUid);
  pass("unmodified generated IFpsListener Bp/Bn through AZKB; ordered oneway "
       "callbacks and caller identity");
  pass("nested synchronous callback returns on originating thread; caller "
       "context restored");
  pass("canonical C++ Binder identity, remote Binder reply without application "
       "keepalive");
  pass("Flattenable framing; pipe FD ownership and trailing "
       "data (not a sync fence)");
  Parcel bad, badReply;
  bad.writeInterfaceToken(String16(u"wrong.interface"));
  bad.writeFloat(1);
  assert(local->transact(1, bad, &badReply) == BAD_TYPE);
  Parcel unknown, unused;
  assert(server->transact(999, unknown, &unused) == UNKNOWN_TRANSACTION);
  pass("interface rejection and exact UNKNOWN_TRANSACTION status");

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

void blobTests() {
  {
    int fds[2];
    assert(pipe(fds) == 0);
    Parcel borrowed, copied, owned;
    assert(borrowed.writeFileDescriptor(fds[0], false) == OK);
    borrowed.setDataPosition(0);
    assert(borrowed.readFileDescriptor() == fds[0]);
    assert(copied.appendFrom(&borrowed, 0, borrowed.dataSize()) == OK);
    borrowed.freeData();
    assert(fcntl(fds[0], F_GETFD) != -1);
    assert(owned.writeFileDescriptor(fds[0], true) == OK);
    owned.setDataPosition(0);
    assert(owned.readFileDescriptor() == fds[0]);
    owned.freeData();
    assert(fcntl(fds[0], F_GETFD) == -1);
    copied.setDataPosition(0);
    int duplicate = copied.readFileDescriptor();
    assert(duplicate >= 0 && duplicate != fds[0]);
    assert(write(fds[1], "B", 1) == 1);
    char value;
    assert(read(duplicate, &value, 1) == 1 && value == 'B');
    copied.freeData();
    assert(fcntl(duplicate, F_GETFD) == -1);
    close(fds[1]);
  }
  for (size_t size : {size_t(0), size_t(13), size_t(16 * 1024), size_t(16 * 1024 + 1)}) {
    for (bool allowFds : {false, true}) {
      Parcel parcel;
      parcel.pushAllowFds(allowFds);
      Parcel::WritableBlob writer;
      assert(parcel.writeBlob(size, false, &writer) == OK);
      assert(writer.size() == size && !writer.isMutable());
      const bool shared = allowFds && size > 16 * 1024;
      assert((writer.fd() >= 0) == shared && parcel.hasFileDescriptors() == shared);
      memset(writer.data(), 0x5a, size);
      const int ownedFd = writer.fd();
      writer.release();
      writer.release(); // Idempotent; releasing the mapping does not close the Parcel's FD.
      if (shared) assert(fcntl(ownedFd, F_GETFD) != -1);
      assert(parcel.writeInt32(1234) == OK);
      parcel.setDataPosition(0);
      Parcel::ReadableBlob reader;
      assert(parcel.readBlob(size, &reader) == OK);
      assert(reader.size() == size && reader.mutableData() == nullptr);
      for (size_t i = 0; i < size; ++i)
        assert(static_cast<const uint8_t *>(reader.data())[i] == 0x5a);
      assert(parcel.readInt32() == 1234);
      reader.release();
      parcel.freeData();
      if (shared) assert(fcntl(ownedFd, F_GETFD) == -1);
    }
  }
  {
    Parcel parcel;
    Parcel::WritableBlob writer;
    assert(parcel.writeBlob(size_t(INT32_MAX) + 1, false, &writer) == BAD_VALUE);
    assert(parcel.dataSize() == 0);
    parcel.pushAllowFds(false);
    assert(parcel.writeDupImmutableBlobFileDescriptor(-1) == FDS_NOT_ALLOWED);
    assert(parcel.dataSize() == 0);
    parcel.writeInt32(0); // Inline blob without its advertised bytes.
    parcel.setDataPosition(0);
    Parcel::ReadableBlob reader;
    assert(parcel.readBlob(16, &reader) == BAD_VALUE);
  }
  {
    int fds[2];
    assert(pipe(fds) == 0);
    Parcel parcel;
    parcel.writeInt32(1);
    assert(parcel.writeDupFileDescriptor(fds[0]) == OK);
    close(fds[0]);
    close(fds[1]);
    parcel.setDataPosition(0);
    Parcel::ReadableBlob reader;
    assert(parcel.readBlob(16, &reader) == BAD_VALUE); // A pipe is not shared memory.
  }
  pass("AOSP blob size threshold, descriptor policy, malformed input and release");
}

struct Tracked final : BBinder {
  std::atomic<int> &destroyed;
  explicit Tracked(std::atomic<int> &count) : destroyed(count) {}
  ~Tracked() override { ++destroyed; }
};
void parcelTests() {
  {
    // GPU AIDL out vectors carry only a count, with the stock allocation cap.
    Parcel p;
    assert(p.writeVectorSize(std::vector<int32_t>{8, 9}) == OK);
    assert(p.dataSize() == sizeof(int32_t));
    p.setDataPosition(0);
    std::vector<int32_t> output{7};
    assert(p.resizeOutVector(&output) == OK);
    assert((output == std::vector<int32_t>{7, 0}));
    for (int32_t count : {-1, 250000, INT32_MAX}) {
      p.freeData();
      assert(p.writeInt32(count) == OK);
      p.setDataPosition(0);
      assert(p.resizeOutVector(&output) == (count < 0 ? UNEXPECTED_NULL : NO_MEMORY));
      assert((output == std::vector<int32_t>{7, 0}));
    }
    p.freeData();
    std::optional<std::vector<int32_t>> optional;
    std::unique_ptr<std::vector<int32_t>> pointer;
    assert(p.writeVectorSize(optional) == OK);
    assert(p.writeVectorSize(pointer) == OK);
    assert(p.writeVectorSize(output) == OK);
    assert(p.writeVectorSize(output) == OK);
    p.setDataPosition(0);
    optional.emplace(1, 7);
    pointer = std::make_unique<std::vector<int32_t>>(1, 7);
    assert(p.resizeOutVector(&optional) == OK && !optional);
    assert(p.resizeOutVector(&pointer) == OK && !pointer);
    assert(p.resizeOutVector(&optional) == OK && *optional == std::vector<int32_t>(2));
    assert(p.resizeOutVector(&pointer) == OK && *pointer == std::vector<int32_t>(2));
    pass("AIDL out-vector count, nullability, overflow and allocation limits");
  }
  {
    Parcel p;
    const std::vector<std::string> strings{"", "layers", "\xc3\xa9\xf0\x9f\x98\x80"};
    assert(p.writeUtf8VectorAsUtf16Vector(strings) == OK);
    p.setDataPosition(0);
    assert(p.readInt32() == 3);
    assert(p.readString16() == String16(u""));
    assert(p.readString16() == String16(u"layers"));
    assert(p.readString16() == String16(u"\u00e9\U0001f600"));
    p.setDataPosition(0);
    std::vector<std::string> decoded;
    assert(p.readUtf8VectorFromUtf16Vector(&decoded) == OK && decoded == strings);
    p.freeData();
    using OptionalStrings = std::optional<std::vector<std::optional<std::string>>>;
    OptionalStrings nullable{std::vector<std::optional<std::string>>{std::nullopt, "", strings[2]}};
    assert(p.writeUtf8VectorAsUtf16Vector(nullable) == OK);
    assert(p.writeUtf8VectorAsUtf16Vector(OptionalStrings{}) == OK);
    p.setDataPosition(0);
    OptionalStrings optional;
    assert(p.readUtf8VectorFromUtf16Vector(&optional) == OK && optional == nullable);
    assert(p.readUtf8VectorFromUtf16Vector(&optional) == OK && !optional);
    p.setDataPosition(0);
    std::unique_ptr<std::vector<std::unique_ptr<std::string>>> pointer;
    assert(p.readUtf8VectorFromUtf16Vector(&pointer) == OK);
    assert(pointer && pointer->size() == 3 && !(*pointer)[0]);
    assert((*pointer)[1] && (*pointer)[1]->empty() && *(*pointer)[2] == strings[2]);
    Parcel copied;
    assert(copied.writeUtf8VectorAsUtf16Vector(pointer) == OK);
    copied.setDataPosition(0);
    assert(copied.readUtf8VectorFromUtf16Vector(&optional) == OK && optional == nullable);
    assert(p.readUtf8VectorFromUtf16Vector(&pointer) == OK && !pointer);
    p.freeData();
    assert(p.writeInt32(2) == OK && p.writeUtf8AsUtf16(std::string("first")) == OK);
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&decoded) == UNEXPECTED_NULL);
    assert((decoded == std::vector<std::string>{"first", ""}));
    p.freeData();
    assert(p.writeUtf8AsUtf16(std::string("\xff", 1)) == BAD_VALUE && p.dataSize() == 0);
    pass("GPU string vectors preserve UTF-16 wire format, nulls and partial reads");
  }
  {
    struct Receiver final : BnResultReceiver {
      int32_t code = 0;
      void send(int32_t value) override { code = value; }
    };
    auto receiver = sp<Receiver>::make();
    Parcel p;
    assert(p.writeStrongBinder(IInterface::asBinder(receiver)) == OK);
    assert(p.writeStrongBinder(nullptr) == OK);
    assert(p.writeStrongBinder(nullptr) == OK);
    p.setDataPosition(0);
    sp<IResultReceiver> decoded;
    assert(p.readNullableStrongBinder(&decoded) == OK && decoded.get() == receiver.get());
    decoded->send(42);
    assert(receiver->code == 42);
    assert(p.readNullableStrongBinder(&decoded) == OK && !decoded);
    decoded = receiver;
    assert(p.readStrongBinder(&decoded) == UNEXPECTED_NULL && decoded == receiver);
    Parcel request, reply;
    assert(request.writeInterfaceToken(receiver->getInterfaceDescriptor()) == OK);
    assert(request.writeInt32(81) == OK);
    assert(receiver->transact(IResultReceiver::OP_SEND, request, &reply) == OK);
    binder::Status status;
    assert(status.readFromParcel(reply) == OK && status.isOk() && receiver->code == 81);
    pass("typed result receiver preserves Binder identity and null contracts");
  }
  {
    enum class ByteKey : int8_t { Zero = 0, Last = 127, Negative = -1 };
    enum class WordKey : int32_t { Zero = 0, Large = 0x12345678 };
    const std::vector<ByteKey> bytes{ByteKey::Zero, ByteKey::Last, ByteKey::Negative};
    const std::vector<WordKey> words{WordKey::Large, static_cast<WordKey>(-9)};
    Parcel p;
    assert(p.writeEnumVector(bytes) == OK && p.dataSize() == 8);
    assert(p.writeEnumVector(words) == OK && p.dataSize() == 20);
    p.setDataPosition(0);
    assert(p.readInt32() == 3);
    uint8_t raw[3];
    assert(p.read(raw, sizeof(raw)) == OK);
    assert(raw[0] == 0 && raw[1] == 127 && raw[2] == 255);
    assert(p.readInt32() == 2 && p.readInt32() == 0x12345678 && p.readInt32() == -9);
    p.setDataPosition(0);
    std::vector<ByteKey> decodedBytes;
    std::vector<WordKey> decodedWords;
    assert(p.readEnumVector(&decodedBytes) == OK && decodedBytes == bytes);
    assert(p.readEnumVector(&decodedWords) == OK && decodedWords == words);
    assert(p.dataAvail() == 0);
    p.freeData();
    p.writeInt32(-1);
    p.setDataPosition(0);
    assert(p.readEnumVector(&decodedWords) == UNEXPECTED_NULL && decodedWords == words);
    p.freeData();
    p.writeInt32(2);
    p.writeInt32(7); // Length passes the coarse bound, but the second word is absent.
    p.setDataPosition(0);
    assert(p.readEnumVector(&decodedWords) == BAD_VALUE && decodedWords.empty());
    pass("enum vectors preserve packed byte and full-width AIDL encodings");
  }
  {
    const std::vector<std::string> text{"", "task \xce\xbb", "\xf0\x9f\x98\x80",
                                        std::string("a\0b", 3)};
    Parcel p, wire;
    assert(p.writeUtf8VectorAsUtf16Vector(text) == OK);
    // Independent UTF-16 framing, including a surrogate pair and embedded NUL.
    assert(wire.writeInt32(4) == OK);
    assert(wire.writeString16(u"", 0) == OK);
    assert(wire.writeString16(u"task \u03bb", 6) == OK);
    assert(wire.writeString16(u"\U0001f600", 2) == OK);
    assert(wire.writeString16(u"a\0b", 3) == OK);
    assert(p.dataSize() == wire.dataSize());
    assert(memcmp(p.data(), wire.data(), p.dataSize()) == 0);
    p.setDataPosition(0);
    std::vector<std::string> decoded{"old"};
    assert(p.readUtf8VectorFromUtf16Vector(&decoded) == OK && decoded == text);
    assert(p.dataAvail() == 0);
    const size_t before = p.dataSize();
    assert(p.writeUtf8AsUtf16(std::string("\xf0\x9f", 2)) == BAD_VALUE);
    assert(p.dataSize() == before && p.dataPosition() == before);

    using OptionalVector =
        std::optional<std::vector<std::optional<std::string>>>;
    OptionalVector values{std::vector<std::optional<std::string>>{
        std::nullopt, std::string{}, text[2]}},
        copy;
    p.freeData();
    assert(p.writeUtf8VectorAsUtf16Vector(values) == OK);
    assert(p.writeUtf8VectorAsUtf16Vector(OptionalVector{}) == OK);
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&copy) == OK && copy == values);
    assert(p.readUtf8VectorFromUtf16Vector(&copy) == OK && !copy);
    using OwnedVector =
        std::unique_ptr<std::vector<std::unique_ptr<std::string>>>;
    OwnedVector owned;
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&owned) == OK);
    assert(owned && owned->size() == 3 && !(*owned)[0] &&
           (*owned)[1]->empty() && *(*owned)[2] == text[2]);
    wire.freeData();
    assert(wire.writeUtf8VectorAsUtf16Vector(owned) == OK);
    wire.setDataPosition(0);
    assert(wire.readUtf8VectorFromUtf16Vector(&copy) == OK && copy == values);
    assert(p.readUtf8VectorFromUtf16Vector(&owned) == OK && !owned);
    assert(wire.writeUtf8VectorAsUtf16Vector(owned) == OK);

    // AOSP leaves a resized, partially decoded vector on an element failure.
    p.freeData();
    assert(p.writeInt32(3) == OK);
    assert(p.writeUtf8AsUtf16(std::string("first")) == OK);
    assert(p.writeInt32(-1) == OK);
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&decoded) == UNEXPECTED_NULL);
    assert((decoded == std::vector<std::string>{"first", "", ""}));
    p.freeData();
    assert(p.writeInt32(100) == OK);
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&decoded) == BAD_VALUE);
    assert(decoded.size() == 3 && decoded[0] == "first");
    p.freeData();
    assert(p.writeInt32(-2) == OK);
    p.setDataPosition(0);
    assert(p.readUtf8VectorFromUtf16Vector(&copy) == UNEXPECTED_NULL);
    assert(copy && copy->empty());
    p.freeData();
    assert(p.writeInt32(2) == OK); // Truncated UTF-16 payload.
    p.setDataPosition(0);
    std::string unchanged = "unchanged";
    assert(p.readUtf8FromUtf16(&unchanged) == UNEXPECTED_NULL);
    assert(unchanged == "unchanged");
    pass("UTF-16 string-vector framing, nullable ownership and AOSP failure "
         "mutation");
  }
  {
    Parcel p;
    auto listener = sp<Listener>::make();
    sp<gui::IFpsListener> input = listener, decoded;
    assert(p.writeStrongBinder(input) == OK);
    assert(p.writeStrongBinder(sp<gui::IFpsListener>{}) == OK);
    p.setDataPosition(0);
    assert(p.readStrongBinder(&decoded) == OK && decoded == input);
    assert(p.readNullableStrongBinder(&decoded) == OK && !decoded);
    p.setDataPosition(0);
    std::vector<sp<IBinder>> binders{IInterface::asBinder(input)},
        decodedBinders;
    p.freeData();
    assert(p.writeStrongBinderVector(binders) == OK);
    p.setDataPosition(0);
    assert(p.readStrongBinderVector(&decodedBinders) == OK &&
           decodedBinders == binders);
    enum class Byte : int8_t { first = 1, last = 127 };
    std::vector<Byte> values{Byte::first, Byte::last}, decodedValues;
    p.freeData();
    assert(p.writeEnumVector(values) == OK && p.dataSize() == 8);
    p.setDataPosition(0);
    assert(p.readEnumVector(&decodedValues) == OK && decodedValues == values);
    std::optional<std::vector<Byte>> absent;
    p.freeData();
    assert(p.writeEnumVector(absent) == OK);
    p.setDataPosition(0);
    absent = values;
    assert(p.readEnumVector(&absent) == OK && !absent);
    pass("typed Binder references, Binder vectors and nullable byte enum "
         "vectors");
  }
  {
    Parcel reply;
    assert(reply.writeNoException() == OK);
    assert(reply.writeInt32(42) == OK);
    reply.setDataPosition(0);
    assert(reply.readExceptionCode() == 0 && reply.readInt32() == 42);
    reply.freeData();
    auto denied =
        binder::Status::fromExceptionCode(binder::Status::EX_SECURITY);
    assert(denied.writeToParcel(&reply) == OK);
    reply.setDataPosition(0);
    assert(reply.readExceptionCode() == binder::Status::EX_SECURITY);
    reply.freeData();
    assert(reply.readExceptionCode() != 0);
  }
  {
    Parcel sizes;
    const std::vector<int32_t> original{1, 2, 3};
    assert(sizes.writeVectorSize(original) == OK);
    sizes.setDataPosition(0);
    std::vector<int32_t> out;
    assert(sizes.resizeOutVector(&out) == OK && out.size() == 3);
    // Unlike an input vector, no element bytes accompany an out-vector count.
    assert(sizes.dataAvail() == 0);
    sizes.freeData();
    sizes.writeInt32(250000); // AOSP refuses allocations of one million bytes.
    sizes.setDataPosition(0);
    assert(sizes.resizeOutVector(&out) == NO_MEMORY && out.size() == 3);
    sizes.freeData();
    sizes.writeInt32(-1);
    sizes.setDataPosition(0);
    assert(sizes.resizeOutVector(&out) == UNEXPECTED_NULL);
    sizes.setDataPosition(0);
    std::optional<std::vector<int32_t>> optional{original};
    assert(sizes.resizeOutVector(&optional) == OK && !optional);
    sizes.freeData();
    assert(sizes.resizeOutVector(&out) != OK && out.size() == 3);
  }
  // A native Parcel has its own durable language view, also across reset.
  auto *first = AParcel_create();
  auto *second = AParcel_create();
  auto *a = Parcel::forNative(first);
  auto *b = Parcel::forNative(second);
  assert(a != b && Parcel::forNative(first) == a);
  a->writeInt32(11);
  b->writeInt32(22);
  a->setDataPosition(0);
  b->setDataPosition(0);
  assert(a->readInt32() == 11 && b->readInt32() == 22);
  AParcel_reset(first);
  assert(Parcel::forNative(first) == a);
  AParcel_delete(first);
  AParcel_delete(second);
  {
    auto *cls = AIBinder_Class_define(
        "dev.artzig.foreign", [](void *p) { return p; }, [](void *) {},
        [](AIBinder *, transaction_code_t, const AParcel *, AParcel *)
            -> binder_status_t { return STATUS_UNKNOWN_TRANSACTION; });
    auto *native = AIBinder_new(cls, nullptr);
    auto wrapper = IBinder::fromNative(native);
    assert(wrapper->localBinder() && !wrapper->remoteBinder());
    assert(wrapper->getInterfaceDescriptor() ==
           String16(u"dev.artzig.foreign"));
    assert(wrapper->pingBinder() == OK);
  }
  std::atomic<int> destroyed{0};
  {
    Parcel source, copy;
    auto binder = sp<Tracked>::make(destroyed);
    assert(source.objectsCount() == 0);
    assert(source.writeStrongBinder(binder) == OK);
    assert(source.objectsCount() == 1);
    binder.clear();
    assert(destroyed == 0);
    assert(copy.appendFrom(&source, 0, source.dataSize()) == OK);
    assert(copy.objectsCount() == 1);
    assert(copy.appendFrom(&source, 1, 3) == BAD_VALUE);
    source.freeData();
    assert(source.objectsCount() == 0 && copy.objectsCount() == 1);
    assert(destroyed == 0);
    copy.setDataPosition(0);
    auto one = copy.readStrongBinder();
    copy.setDataPosition(0);
    auto two = copy.readStrongBinder();
    assert(one == two && copy.objectsCount() == 1);
    one.clear();
    two.clear();
    copy.setDataPosition(0);
    copy.writeInt32(7);
    assert(copy.objectsCount() == 0);
    assert(destroyed == 1);
    copy.setDataPosition(0);
    sp<IBinder> invalid;
    assert(copy.readNullableStrongBinder(&invalid) == BAD_TYPE);
  }
  {
    Parcel p;
    auto binder = sp<Tracked>::make(destroyed);
    p.writeStrongBinder(binder);
    binder.clear();
    p.setDataSize(0);
    assert(p.objectsCount() == 0);
    assert(destroyed == 2);
  }
  {
    int fds[2];
    assert(pipe(fds) == 0);
    Parcel p, q;
    assert(p.writeDupFileDescriptor(fds[0]) == OK);
    assert(p.objectsCount() == 1);
    close(fds[0]);
    p.setDataPosition(0);
    int borrowed = p.readFileDescriptor();
    assert(borrowed >= 0);
    assert((fcntl(borrowed, F_GETFD) & FD_CLOEXEC) != 0);
    assert(q.appendFrom(&p, 0, p.dataSize()) == OK);
    p.freeData();
    assert(fcntl(borrowed, F_GETFD) == -1);
    q.setDataPosition(0);
    int owned = q.readFileDescriptor();
    assert(owned >= 0);
    assert(write(fds[1], "x", 1) == 1);
    char x;
    assert(read(owned, &x, 1) == 1 && x == 'x');
    q.freeData();
    assert(fcntl(owned, F_GETFD) == -1);
    p.pushAllowFds(false);
    assert(p.writeDupFileDescriptor(fds[1]) == FDS_NOT_ALLOWED);
    close(fds[1]);
  }
  {
    int data[2], comm[2];
    assert(pipe(data) == 0 && pipe(comm) == 0);
    base::unique_fd reader(data[0]), writer(data[1]);
    base::unique_fd commReader(comm[0]), commWriter(comm[1]);
    Parcel p;
    assert(p.writeInt32(1) == OK);
    assert(p.writeDupFileDescriptor(reader.get()) == OK);
    assert(p.writeDupFileDescriptor(commWriter.get()) == OK);
    p.setDataPosition(0);
    os::ParcelFileDescriptor decoded;
    assert(decoded.readFromParcel(&p) == OK);
    unsigned char detached[4] = {};
    assert(read(commReader.get(), detached, sizeof(detached)) == 4);
    assert(detached[0] == 0 && detached[1] == 0 && detached[2] == 0 &&
           detached[3] == 2);
    assert(fcntl(commWriter.get(), F_GETFD) >= 0);
    p.freeData();
    assert(write(writer.get(), "r", 1) == 1);
    char value = 0;
    assert(read(decoded.get(), &value, 1) == 1 && value == 'r');
    const int previous = decoded.get();
    assert(p.writeInt32(1) == OK);
    assert(p.writeDupFileDescriptor(reader.get()) == OK);
    assert(p.writeDupFileDescriptor(commReader.get()) == OK);
    p.setDataPosition(0);
    assert(decoded.readFromParcel(&p) == BAD_TYPE);
    assert(decoded.get() == previous && fcntl(previous, F_GETFD) >= 0);
    p.freeData();
    assert(p.writeDupParcelFileDescriptor(-1) == -EBADF);
    assert(p.dataPosition() == 0 && p.dataSize() == 0);
    pass("reliable ParcelFileDescriptor detach framing and failure ownership");
  }
  {
    Parcel p;
    auto status = binder::Status::fromServiceSpecificError(47, "failure");
    assert(status.writeToParcel(&p) == OK);
    p.setDataPosition(0);
    binder::Status decoded;
    assert(decoded.readFromParcel(p) == OK);
    assert(decoded.serviceSpecificErrorCode() == 47);
    assert(decoded.exceptionMessage() == String8("failure"));
    p.setDataPosition(0);
    AStatus *ndkStatus = nullptr;
    assert(AParcel_readStatusHeader(p.native(), &ndkStatus) == OK);
    assert(AStatus_getServiceSpecificError(ndkStatus) == 47);
    AStatus_delete(ndkStatus);
    p.freeData();
    ndkStatus = AStatus_fromServiceSpecificErrorWithMessage(81, "ndk failure");
    assert(AParcel_writeStatusHeader(p.native(), ndkStatus) == OK);
    AStatus_delete(ndkStatus);
    p.setDataPosition(0);
    assert(decoded.readFromParcel(p) == OK &&
           decoded.serviceSpecificErrorCode() == 81);
    assert(decoded.exceptionMessage() == String8("ndk failure"));
    p.freeData();
    p.writeString16(u"a\xd800z", 3);
    p.setDataPosition(0);
    auto text = p.readString16();
    assert(text.size() == 3 && text[1] == 0xd800);
    p.freeData();
    const std::optional<std::string> present("café");
    assert(p.writeUtf8AsUtf16(present) == OK);
    assert(p.writeUtf8AsUtf16(std::optional<std::string>{}) == OK);
    p.setDataPosition(0);
    std::optional<std::string> nullable("old");
    assert(p.readUtf8FromUtf16(&nullable) == OK && nullable == present);
    assert(p.readUtf8FromUtf16(&nullable) == OK && !nullable);
    nullable = "unchanged";
    assert(p.readUtf8FromUtf16(&nullable) != OK && nullable == "unchanged");
    p.freeData();
    const uint8_t raw[] = {4, 5, 6};
    assert(p.writeByteArray(sizeof(raw), raw) == OK);
    assert(p.writeByteArray(0, nullptr) == OK);
    const auto end = p.dataPosition();
    assert(p.writeByteArray(static_cast<size_t>(INT32_MAX) + 1, raw) == BAD_VALUE);
    assert(p.dataPosition() == end);
    p.setDataPosition(0);
    assert(p.readInt32() == 3);
    uint8_t restored[3];
    assert(p.read(restored, 3) == OK && memcmp(raw, restored, 3) == 0);
    assert(p.readInt32() == -1);
    p.freeData();
    assert(status.writeToParcel(&p) == OK);
    assert(p.writeInt32(71) == OK);
    p.setDataPosition(0);
    assert(p.readExceptionCode() == binder::Status::EX_SERVICE_SPECIFIC);
    assert(p.readInt32() == 71);
    p.freeData();
    p.writeByteVector({1, 2, 3});
    p.writeInt32(99);
    p.setDataPosition(0);
    std::vector<uint8_t> bytes;
    assert(p.readByteVector(&bytes) == OK);
    assert((bytes == std::vector<uint8_t>{1, 2, 3}) && p.readInt32() == 99);
    p.setDataPosition(INT32_MAX);
    assert(p.writeInt64(1) == BAD_VALUE);
  }
  {
    // Apps cannot register system service names; the export still needs
    // release.
    auto binder = sp<Tracked>::make(destroyed);
    auto *native = binder->acquireNative();
    {
      auto converted = AIBinder_toPlatformBinder(native);
      assert(converted == binder);
      auto *roundtrip = AIBinder_fromPlatformBinder(converted);
      assert(roundtrip == native);
      AIBinder_decStrong(roundtrip);
    }
    assert(AServiceManager_addService(native, "forbidden.system.service") !=
           OK);
    AIBinder_decStrong(native);
    binder.clear();
    // Synchronize a later broker request with the release reader.
    for (int i = 0; i < 100 && destroyed != 3; i++)
      std::this_thread::sleep_for(10ms);
    assert(destroyed == 3);
  }
  pass("independent Parcel views; owned Binder copy/overwrite/truncate; FD "
       "ownership; real AOSP Status; UTF-16 and bounds");
}
