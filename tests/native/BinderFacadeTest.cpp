// Keep host NDK assertion macros contained before loading generated headers.
#include <azk/binder.h>
#include <cassert>

#include <android/binder_manager.h>
#include <android/gui/BnFpsListener.h>
#include <binder/IPCThreadState.h>
#include <binder/IResultReceiver.h>
#include <binder/Parcel.h>
#include <fcntl.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <thread>
using namespace android;
using namespace std::chrono_literals;
constexpr uint32_t EXCHANGE = 1, NESTED = 2, GRAPHICS = 3;
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
  int ownUid = IPCThreadState::self()->getCallingUid();
  status_t onTransact(uint32_t code, const Parcel &in, Parcel *out,
                      uint32_t) override {
    if (code == 4)
      return out->writeStrongBinder(sp<RemoteTracked>::make());
    if (code == 5)
      return out->writeInt32(remoteDestroyed.load());
    if (code == NESTED) {
      return out->writeInt32(in.readInt32() + 1);
    }
    if (code != EXCHANGE)
      return UNKNOWN_TRANSACTION;
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
int main(int argc, char **argv) {
  if (argc > 1 && std::string(argv[1]) == "server") {
    auto service = sp<Service>::make();
    auto *n = service->acquireNative();
    assert(AServiceManager_addService(n, "dev.artzig.facade.v2") == OK);
    AIBinder_decStrong(n);
    std::cout << "ready" << std::endl;
    char c;
    while (std::cin.get(c)) {
    }
    return 0;
  }
  parcelTests();
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
  auto *raw = AServiceManager_waitForService("dev.artzig.facade.v2");
  assert(raw);
  auto server = IBinder::fromNative(raw);
  assert(server->remoteBinder() && !server->localBinder());
  auto same = IBinder::fromNative(
      AServiceManager_waitForService("dev.artzig.facade.v2"));
  assert(server == same);
  same.clear();
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
    assert(p.writeInt32(2) == OK && p.writeUtf8AsUtf16("first") == OK);
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
    assert(source.writeStrongBinder(binder) == OK);
    binder.clear();
    assert(destroyed == 0);
    assert(copy.appendFrom(&source, 0, source.dataSize()) == OK);
    assert(copy.appendFrom(&source, 1, 3) == BAD_VALUE);
    source.freeData();
    assert(destroyed == 0);
    copy.setDataPosition(0);
    auto one = copy.readStrongBinder();
    copy.setDataPosition(0);
    auto two = copy.readStrongBinder();
    assert(one == two);
    one.clear();
    two.clear();
    copy.setDataPosition(0);
    copy.writeInt32(7);
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
    assert(destroyed == 2);
  }
  {
    int fds[2];
    assert(pipe(fds) == 0);
    Parcel p, q;
    assert(p.writeDupFileDescriptor(fds[0]) == OK);
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
    Parcel p;
    auto status = binder::Status::fromServiceSpecificError(47, "failure");
    assert(status.writeToParcel(&p) == OK);
    p.setDataPosition(0);
    binder::Status decoded;
    assert(decoded.readFromParcel(p) == OK);
    assert(decoded.serviceSpecificErrorCode() == 47);
    assert(decoded.exceptionMessage() == String8("failure"));
    p.setDataPosition(0);
    AStatus* ndkStatus=nullptr;
    assert(AParcel_readStatusHeader(p.native(),&ndkStatus)==OK);
    assert(AStatus_getServiceSpecificError(ndkStatus)==47);
    AStatus_delete(ndkStatus);
    p.freeData();
    ndkStatus=AStatus_fromServiceSpecificErrorWithMessage(81,"ndk failure");
    assert(AParcel_writeStatusHeader(p.native(),ndkStatus)==OK);
    AStatus_delete(ndkStatus);
    p.setDataPosition(0);
    assert(decoded.readFromParcel(p)==OK&&decoded.serviceSpecificErrorCode()==81);
    assert(decoded.exceptionMessage()==String8("ndk failure"));
    p.freeData();
    p.writeString16(u"a\xd800z", 3);
    p.setDataPosition(0);
    auto text = p.readString16();
    assert(text.size() == 3 && text[1] == 0xd800);
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
