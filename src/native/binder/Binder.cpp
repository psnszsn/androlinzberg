#include <azk/binder.h>
// Source note: follows pinned Binder/IInterface object contracts over AZKB.
// Kernel IPCThreadState/BpBinder cannot be used on this hosted transport.
#include <android/binder_ibinder_platform.h>
#include <azk/binder.h>
#include <binder/IInterface.h>
#include <binder/BpBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <utils/String8.h>

#include <cassert>
#include <map>
#include <memory>
namespace android {
namespace {
std::mutex cacheMutex;
std::map<AIBinder *, wp<IBinder>> cache;
AIBinder_Class *localClass(const String16 &descriptor) {
  static std::mutex mutex;
  static std::map<std::string, AIBinder_Class *> classes;
  std::lock_guard lock(mutex);
  std::string name = String8(descriptor).c_str();
  auto &cls = classes[name];
  if (cls)
    return cls;
  cls = AIBinder_Class_define(
      name.c_str(), [](void *p) { return p; },
      [](void *p) { delete static_cast<sp<BBinder> *>(p); },
      [](AIBinder *n, transaction_code_t code, const AParcel *input,
         AParcel *output) -> binder_status_t {
        auto &target = *static_cast<sp<BBinder> *>(AIBinder_getUserData(n));
        Parcel in(const_cast<AParcel *>(input)), out(output);
        return target->onTransact(code, in, &out, azk_binder_callFlags());
      });
  AIBinder_Class_disableInterfaceTokenHeader(cls);
  AIBinder_Class_setOnDump(cls, [](AIBinder *n, int fd, const char **args,
                                  uint32_t count) -> binder_status_t {
    auto &target = *static_cast<sp<BBinder> *>(AIBinder_getUserData(n));
    if (count > INT32_MAX) return BAD_VALUE;
    // Remote dumps enter onTransact: JavaBBinder handles them in Java while
    // its direct dump() override is intentionally empty in pinned AOSP.
    Parcel input, output;
    status_t status = input.writeFileDescriptor(fd, false);
    if (status != OK) return status;
    status = input.writeInt32(static_cast<int32_t>(count));
    if (status != OK) return status;
    for (uint32_t i = 0; i < count; ++i) {
      status = input.writeString16(String16(args[i]));
      if (status != OK) return status;
    }
    input.setDataPosition(0);
    return target->onTransact(IBinder::DUMP_TRANSACTION, input, &output,
                              azk_binder_callFlags());
  });
  return cls;
}
} // namespace
// Follows pinned BpBinder::ObjectManager and BBinder's locking. The registry
// belongs to the cached C++ object view; its tags never enter AZKB parcels.
IBinder::~IBinder() {
  for (const auto &[id, object] : objects)
    if (object.cleanup)
      object.cleanup(id, object.value, object.cookie);
}
void *IBinder::attachObject(const void *id, void *value, void *cookie,
                            object_cleanup_func cleanup) {
  std::lock_guard lock(objectsMutex);
  auto [entry, inserted] = objects.emplace(id, Object{value, cookie, cleanup});
  return inserted ? nullptr : entry->second.value;
}
void *IBinder::findObject(const void *id) const {
  std::lock_guard lock(objectsMutex);
  auto entry = objects.find(id);
  return entry == objects.end() ? nullptr : entry->second.value;
}
void *IBinder::detachObject(const void *id) {
  std::lock_guard lock(objectsMutex);
  auto entry = objects.find(id);
  if (entry == objects.end())
    return nullptr;
  auto value = entry->second.value;
  objects.erase(entry);
  return value;
}
void IBinder::withLock(const std::function<void()> &action) {
  std::lock_guard lock(objectsMutex);
  action();
}
sp<IBinder> IBinder::lookupOrCreateWeak(const void *id, object_make_func make,
                                        const void *args) {
  std::lock_guard lock(objectsMutex);
  auto &entry = objects[id];
  if (entry.value) {
    if (auto live = static_cast<wp<IBinder> *>(entry.value)->promote())
      return live;
  } else {
    entry.value = new wp<IBinder>();
  }
  auto created = make(args);
  *static_cast<wp<IBinder> *>(entry.value) = created;
  entry.cookie = nullptr;
  entry.cleanup = [](const void *, void *value, void *) {
    delete static_cast<wp<IBinder> *>(value);
  };
  return created;
}
sp<IInterface> IBinder::queryLocalInterface(const String16 &) {
  return nullptr;
}
BBinder::~BBinder() {
  {
    std::lock_guard lock(cacheMutex);
    for (auto i = cache.begin(); i != cache.end();) {
      if (i->second.unsafe_get() == this)
        i = cache.erase(i);
      else
        ++i;
    }
  }
  if (weak)
    AIBinder_Weak_delete(weak);
}
const String16 &BBinder::getInterfaceDescriptor() const {
  static String16 name;
  return name;
}
AIBinder *BBinder::acquireNative() {
  std::unique_lock lock(mutex);
  if (weak)
    if (auto *n = AIBinder_Weak_promote(weak))
      return n;
  auto *n = AIBinder_new(localClass(getInterfaceDescriptor()),
                         new sp<BBinder>(sp<BBinder>::fromExisting(this)));
  if (weak)
    AIBinder_Weak_delete(weak);
  weak = AIBinder_Weak_new(n);
  {
    std::lock_guard lock(cacheMutex);
    // Retire an earlier native incarnation while the C++ object is still live.
    for (auto i = cache.begin(); i != cache.end();) {
      if (i->second.unsafe_get() == this)
        i = cache.erase(i);
      else
        ++i;
    }
    cache[n] = sp<IBinder>::fromExisting(this);
  }
  auto ext = extension;
  lock.unlock();
  if (ext) {
    auto *e = ext->acquireNative();
    AIBinder_setExtension(n, e);
    AIBinder_decStrong(e);
  }
  return n;
}
// Pinned BBinder stores the extension even before its first native export.
void BBinder::setExtension(const sp<IBinder> &ext) {
  AIBinder *live = nullptr;
  sp<IBinder> old;
  {
    std::lock_guard lock(mutex);
    old = std::move(extension);
    extension = ext;
    if (weak) live = AIBinder_Weak_promote(weak);
  }
  if (live) {
    auto *e = ext ? ext->acquireNative() : nullptr;
    AIBinder_setExtension(live, e);
    if (e) AIBinder_decStrong(e);
    AIBinder_decStrong(live);
  }
}
status_t IBinder::getExtension(sp<IBinder> *out) {
  auto *native = acquireNative();
  AIBinder *extension = nullptr;
  status_t status = AIBinder_getExtension(native, &extension);
  AIBinder_decStrong(native);
  *out = IBinder::fromNative(extension);
  return status;
}
bool IBinder::isBinderAlive() const {
  auto *n = const_cast<IBinder *>(this)->acquireNative();
  bool alive = AIBinder_isAlive(n);
  AIBinder_decStrong(n);
  return alive;
}
status_t IBinder::pingBinder() {
  Parcel in, out;
  return transact(PING_TRANSACTION, in, &out);
}
// Pinned BpBinder::dump and BBinder::onTransact use the ordinary Parcel FD
// ownership path. This also makes SurfaceFlinger's priority dump reachable
// through its real Binder object instead of a host-only diagnostics callback.
status_t IBinder::dump(int fd, const Vector<String16> &args) {
  if (args.size() > INT32_MAX)
    return BAD_VALUE;
  Parcel input, output;
  status_t status = input.writeFileDescriptor(fd);
  if (status != OK)
    return status;
  if ((status = input.writeInt32(static_cast<int32_t>(args.size()))) != OK)
    return status;
  for (const auto &arg : args)
    if ((status = input.writeString16(arg)) != OK)
      return status;
  return transact(DUMP_TRANSACTION, input, &output);
}
status_t BBinder::dump(int, const Vector<String16> &) { return OK; }
status_t BBinder::onTransact(uint32_t code, const Parcel &input, Parcel *output,
                             uint32_t) {
  switch (code) {
  case INTERFACE_TRANSACTION:
    return output ? output->writeString16(getInterfaceDescriptor()) : BAD_VALUE;
  case DUMP_TRANSACTION: {
    int fd = input.readFileDescriptor();
    if (fd < 0)
      return BAD_VALUE;
    int32_t count = 0;
    if (input.readInt32(&count) != OK)
      return BAD_VALUE;
    Vector<String16> args;
    // Follow AOSP's bounded-by-available-data argument traversal.
    for (int32_t i = 0; i < count && input.dataAvail() > 0; ++i) {
      String16 arg;
      if (input.readString16(&arg) != OK)
        return BAD_VALUE;
      if (args.add(arg) < 0)
        return NO_MEMORY;
    }
    return dump(fd, args);
  }
  default:
    return UNKNOWN_TRANSACTION;
  }
}
status_t BBinder::transact(uint32_t code, const Parcel &input, Parcel *output,
                           uint32_t flags) {
  auto *n = acquireNative();
  auto rc = azk_binder_transact(n, code, input.native(),
                                output ? output->native() : nullptr, flags);
  AIBinder_decStrong(n);
  return rc;
}
namespace {
std::mutex trackingMutex;
struct ProxyCount { uint32_t count = 0, lastLimit = 0; bool warned = false, limited = false; };
std::map<uint32_t, ProxyCount> proxyCounts;
uint32_t totalProxies = 0, highWatermark = 2500, lowWatermark = 2000, warningWatermark = 2250;
bool countByUid = false;
binder_proxy_limit_callback limitCallback;
binder_proxy_warning_callback warningCallback;
}
// Follows pinned BpBinder::create/destructor accounting and callback hysteresis.
// No kernel proxy throttling is enabled on the hosted process domain.
sp<BpBinder> BpBinder::create(AIBinder *native, std::function<void()> *postTask) {
  int32_t uid = -1;
  {
    std::lock_guard lock(trackingMutex);
    if (countByUid) {
      uid = IPCThreadState::self()->getCallingUid();
      auto &value = proxyCounts[uid];
      std::function<void(int)> callback;
      if (value.limited) {
        if (value.count > value.lastLimit && value.count - value.lastLimit > highWatermark) {
          callback = limitCallback;
          value.lastLimit = value.count;
        }
      } else if (value.count >= warningWatermark && value.count < highWatermark && !value.warned) {
        value.warned = true;
        callback = warningCallback;
      } else if (value.count >= highWatermark) {
        value.limited = true;
        value.lastLimit = value.count;
        callback = limitCallback;
      }
      if (callback) *postTask = [callback, uid] { callback(uid); };
      ++value.count;
    }
    ++totalProxies;
  }
  return sp<BpBinder>::make(native, uid);
}
uint32_t BpBinder::getBinderProxyCount() { std::lock_guard lock(trackingMutex); return totalProxies; }
uint32_t BpBinder::getBinderProxyCount(uint32_t uid) {
  std::lock_guard lock(trackingMutex);
  auto i = proxyCounts.find(uid);
  return i == proxyCounts.end() ? 0 : i->second.count;
}
void BpBinder::getCountByUid(Vector<uint32_t> &uids, Vector<uint32_t> &counts) {
  std::lock_guard lock(trackingMutex);
  for (const auto &[uid, value] : proxyCounts) { uids.push_back(uid); counts.push_back(value.count); }
}
void BpBinder::setCountByUidEnabled(bool enabled) { std::lock_guard lock(trackingMutex); countByUid = enabled; }
void BpBinder::setBinderProxyCountEventCallback(binder_proxy_limit_callback limit, binder_proxy_warning_callback warning) {
  std::lock_guard lock(trackingMutex); limitCallback = std::move(limit); warningCallback = std::move(warning);
}
void BpBinder::setBinderProxyCountWatermarks(int high, int low, int warning) {
  std::lock_guard lock(trackingMutex); highWatermark = high; lowWatermark = low; warningWatermark = warning;
}
void BpBinder::forget(const std::shared_ptr<Link> &link) {
    std::lock_guard lock(linksMutex);
    std::erase(links, link);
  }
BpBinder::~BpBinder() {
    {
      std::lock_guard lock(trackingMutex);
      --totalProxies;
      if (trackedUid >= 0) {
        auto i = proxyCounts.find(trackedUid);
        assert(i != proxyCounts.end() && i->second.count);
        auto &value = i->second;
        if (value.count <= lowWatermark) { value.warned = value.limited = false; value.lastLimit = 0; }
        if (--value.count == 0) proxyCounts.erase(i);
      }
    }
    std::vector<std::shared_ptr<Link>> removed;
    {
      std::lock_guard lock(linksMutex);
      removed.swap(links);
    }
    for (auto &l : removed)
      AIBinder_DeathRecipient_delete(std::exchange(l->recipient, nullptr));
    {
      std::lock_guard lock(cacheMutex);
      auto i = cache.find(native);
      if (i != cache.end() && i->second.unsafe_get() == this)
        cache.erase(i);
    }
    AIBinder_decStrong(native);
  }
AIBinder *BpBinder::acquireNative() {
    AIBinder_incStrong(native);
    return native;
  }
const String16 &BpBinder::getInterfaceDescriptor() const {
    std::call_once(descriptorOnce, [this] {
      Parcel in, out;
      if (azk_binder_transact(native, INTERFACE_TRANSACTION, in.native(),
                              out.native(), 0) == OK)
        out.readString16(&descriptor);
    });
    return descriptor;
  }
status_t BpBinder::transact(uint32_t code, const Parcel &input, Parcel *output, uint32_t flags) {
    return azk_binder_transact(native, code, input.native(),
                               output ? output->native() : nullptr, flags);
  }
status_t BpBinder::linkToDeath(const sp<DeathRecipient> &d, void *cookie, uint32_t flags) {
    if (!d)
      return BAD_VALUE;
    if (flags)
      return BAD_VALUE;
    auto link =
        std::make_shared<Link>(sp<IBinder>::fromExisting(this), d, cookie);
    link->recipient = AIBinder_DeathRecipient_new([](void *p) {
      auto link = *static_cast<std::shared_ptr<Link> *>(p);
      auto binder = link->who.promote();
      if (binder) {
        auto *proxy = static_cast<BpBinder *>(binder.get());
        {
          std::lock_guard lock(proxy->linksMutex);
          proxy->obitsSent = true;
        }
        proxy->forget(link);
      }
      if (auto observer = link->observer.promote())
        observer->binderDied(link->who);
    });
    AIBinder_DeathRecipient_setOnUnlinked(link->recipient, [](void *p) {
      delete static_cast<std::shared_ptr<Link> *>(p);
    });
    auto *keep = new std::shared_ptr<Link>(link);
    {
      std::lock_guard lock(linksMutex);
      links.push_back(link);
    }
    auto rc = AIBinder_linkToDeath(native, link->recipient, keep);
    // The shared runtime invokes onUnlinked on failed remote registrations too.
    if (rc)
      forget(link);
    return rc;
  }
status_t BpBinder::unlinkToDeath(const wp<DeathRecipient> &d, void *cookie, uint32_t flags, wp<DeathRecipient> *out) {
    if (flags)
      return BAD_VALUE;
    std::shared_ptr<Link> found;
    {
      std::lock_guard lock(linksMutex);
      // Pinned BpBinder returns DEAD_OBJECT once its obituary was delivered,
      // even though the corresponding registration has already been removed.
      if (obitsSent)
        return DEAD_OBJECT;
      auto i = std::find_if(links.begin(), links.end(), [&](const auto &l) {
        return (d.unsafe_get() ? l->observer == d : l->cookie == cookie);
      });
      if (i == links.end())
        return NAME_NOT_FOUND;
      found = *i;
      links.erase(i);
    }
    if (out)
      *out = found->observer;
    // Deleting this per-registration recipient unlinks and defers cookie
    // disposal until an in-flight callback finishes, including self-unlink from
    // binderDied.
    AIBinder_DeathRecipient_delete(std::exchange(found->recipient, nullptr));
    return OK;
  }
// A local Binder created through NDK/Java still has local Binder semantics.
// It cannot expose a C++ IInterface, so generated callers use their Bp helper.
class NativeLocalBinder final : public BBinder {
  AIBinder *native;
  mutable std::once_flag once;
  mutable String16 descriptor;

public:
  explicit NativeLocalBinder(AIBinder *n) : native(n) {}
  ~NativeLocalBinder() override {
    {
      std::lock_guard lock(cacheMutex);
      auto i = cache.find(native);
      if (i != cache.end() && i->second.unsafe_get() == this)
        cache.erase(i);
    }
    AIBinder_decStrong(native);
  }
  AIBinder *acquireNative() override {
    AIBinder_incStrong(native);
    return native;
  }
  const String16 &getInterfaceDescriptor() const override {
    std::call_once(once, [this] {
      Parcel in, out;
      if (azk_binder_transact(native, INTERFACE_TRANSACTION, in.native(),
                              out.native(), 0) == OK)
        out.readString16(&descriptor);
    });
    return descriptor;
  }
};
sp<IBinder> IBinder::fromNative(AIBinder *n) {
  if (!n) return nullptr;
  sp<IBinder> result;
  bool adopted = false;
  std::function<void()> postTask;
  {
    std::lock_guard lock(cacheMutex);
    if (auto i = cache.find(n); i != cache.end()) result = i->second.promote();
    if (!result) {
      result = AIBinder_isRemote(n) ? sp<IBinder>(BpBinder::create(n, &postTask))
                                    : sp<IBinder>(sp<NativeLocalBinder>::make(n));
      cache[n] = result;
      adopted = true;
    }
  }
  if (!adopted) AIBinder_decStrong(n);
  // Pinned ProcessState executes proxy-count callbacks outside the handle lock.
  // JNI callbacks can trigger GC and reenter this cache.
  if (postTask) postTask();
  return result;
}
} // namespace android
#include <android/binder_process.h>
#include <android/binder_libbinder.h>
#include <binder/IPCThreadState.h>
// Pinned ndk/libbinder.cpp conversions borrow their input and return a strong
// reference. The host registry replaces AOSP's kernel Binder wrapper registry.
android::sp<android::IBinder> AIBinder_toPlatformBinder(AIBinder *binder) {
  if (!binder)
    return nullptr;
  AIBinder_incStrong(binder);
  return android::IBinder::fromNative(binder);
}

AIBinder *
AIBinder_fromPlatformBinder(const android::sp<android::IBinder> &binder) {
  return binder ? binder->acquireNative() : nullptr;
}

namespace android {
void IPCThreadState::joinThreadPool(bool) { ABinderProcess_joinThreadPool(); }
} // namespace android

namespace android {
BpRefBase::BpRefBase(const sp<IBinder> &o)
    : mRemote(o.get()), mRefs(nullptr), mState(0) {
  extendObjectLifetime(OBJECT_LIFETIME_WEAK);

  if (mRemote) {
    mRemote->incStrong(this);          // Removed on first IncStrong().
    mRefs = mRemote->createWeak(this); // Held for our entire lifetime.
  }
}

BpRefBase::~BpRefBase() {
  if (mRemote) {
    if (!(mState.load(std::memory_order_relaxed) & 1)) {
      mRemote->decStrong(this);
    }
    mRefs->decWeak(this);
  }
}

void BpRefBase::onFirstRef() { mState.fetch_or(1, std::memory_order_relaxed); }

void BpRefBase::onLastStrongRef(const void * /*id*/) {
  if (mRemote) {
    mRemote->decStrong(this);
  }
}

bool BpRefBase::onIncStrongAttempted(uint32_t /*flags*/, const void * /*id*/) {
  return mRemote ? mRefs->attemptIncStrong(this) : false;
}

} // namespace android
