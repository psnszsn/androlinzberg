// Experimental host adapter for the pinned Binder/IInterface contracts.
#include <android/binder_ibinder_platform.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <utils/String8.h>

#include <cassert>
#include <map>
#include <memory>
namespace android {
namespace {
std::mutex cacheMutex;
std::map<AIBinder*, wp<IBinder>> cache;
AIBinder_Class* localClass(const String16& descriptor) {
  static std::mutex mutex;
  static std::map<std::string, AIBinder_Class*> classes;
  std::lock_guard lock(mutex);
  std::string name = String8(descriptor).c_str();
  auto& cls = classes[name];
  if (cls) return cls;
  cls = AIBinder_Class_define(
      name.c_str(), [](void* p) { return p; },
      [](void* p) { delete static_cast<sp<BBinder>*>(p); },
      [](AIBinder* n, transaction_code_t code, const AParcel* input,
         AParcel* output) -> binder_status_t {
        auto& target = *static_cast<sp<BBinder>*>(AIBinder_getUserData(n));
        Parcel in(const_cast<AParcel*>(input)), out(output);
        return target->onTransact(code, in, &out, 0);
      });
  AIBinder_Class_disableInterfaceTokenHeader(cls);
  return cls;
}
}  // namespace
sp<IInterface> IBinder::queryLocalInterface(const String16&) { return nullptr; }
BBinder::~BBinder() {
  if (weak) AIBinder_Weak_delete(weak);
}
const String16& BBinder::getInterfaceDescriptor() const {
  static String16 name(u"dev.artzig.FacadeProbe");
  return name;
}
AIBinder* BBinder::acquireNative() {
  std::lock_guard lock(mutex);
  if (weak)
    if (auto* n = AIBinder_Weak_promote(weak)) return n;
  auto* n = AIBinder_new(localClass(getInterfaceDescriptor()),
                         new sp<BBinder>(sp<BBinder>::fromExisting(this)));
  if (weak) AIBinder_Weak_delete(weak);
  weak = AIBinder_Weak_new(n);
  {
    std::lock_guard lock(cacheMutex);
    cache[n] = sp<IBinder>::fromExisting(this);
  }
  return n;
}
status_t BBinder::transact(uint32_t code, const Parcel& input, Parcel* output,
                           uint32_t flags) {
  // AOSP BBinder local transact rewinds request and reply. Preserve caller
  // cursor.
  size_t position = input.dataPosition();
  input.setDataPosition(0);
  auto rc = onTransact(code, input, output, flags);
  input.setDataPosition(position);
  if (output) output->setDataPosition(0);
  return rc;
}
class BpBinder final : public IBinder {
  AIBinder* native;
  struct Link {
    wp<IBinder> who;
    sp<DeathRecipient> observer;
    AIBinder_DeathRecipient* recipient;
    Link(const sp<IBinder>& b, const sp<DeathRecipient>& d)
        : who(b), observer(d) {
      recipient = AIBinder_DeathRecipient_new([](void* p) {
        auto* l = static_cast<Link*>(p);
        l->observer->binderDied(l->who);
      });
    }
    ~Link() { AIBinder_DeathRecipient_delete(recipient); }
  };
  std::vector<std::unique_ptr<Link>> links;

 public:
  explicit BpBinder(AIBinder* n) : native(n) {}
  ~BpBinder() override {
    links.clear();
    AIBinder_decStrong(native);
  }
  BpBinder* remoteBinder() override { return this; }
  AIBinder* acquireNative() override {
    AIBinder_incStrong(native);
    return native;
  }
  const String16& getInterfaceDescriptor() const override {
    static String16 unknown(u"prototype.remote");
    return unknown;
  }
  status_t transact(uint32_t code, const Parcel& input, Parcel* output,
                    uint32_t flags) override {
    auto* in = AZKPrototype_Parcel_clone(input.native());
    AParcel* out = nullptr;
    auto rc = AIBinder_transact(native, code, &in, &out, flags);
    if (out) {
      if (output)
        output->adopt(out);
      else
        AParcel_delete(out);
    }
    return rc;
  }
  status_t linkToDeath(const sp<DeathRecipient>& d) override {
    auto link = std::make_unique<Link>(sp<IBinder>::fromExisting(this), d);
    auto rc = AIBinder_linkToDeath(native, link->recipient, link.get());
    if (!rc) links.push_back(std::move(link));
    return rc;
  }
  status_t unlinkToDeath(const sp<DeathRecipient>& d) override {
    for (auto i = links.begin(); i != links.end(); ++i)
      if ((*i)->observer == d) {
        auto rc = AIBinder_unlinkToDeath(native, (*i)->recipient, i->get());
        if (!rc) links.erase(i);
        return rc;
      }
    return NAME_NOT_FOUND;
  }
};
sp<IBinder> IBinder::fromNative(AIBinder* n) {
  if (!n) return nullptr;
  sp<IBinder> result;
  {
    std::lock_guard lock(cacheMutex);
    if (auto i = cache.find(n); i != cache.end()) result = i->second.promote();
    if (!result) {
      assert(AIBinder_isRemote(n));
      result = sp<BpBinder>::make(n);
      cache[n] = result;
      return result;
    }
  }
  AIBinder_decStrong(n);
  return result;
}
}  // namespace android
