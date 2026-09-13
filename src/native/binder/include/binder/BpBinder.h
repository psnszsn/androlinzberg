#pragma once
// Hosted BpBinder has one definition for all source-built consumers, including
// pinned android_util_Binder JNI. AZKB owns transport and native references.
#include <azk/binder.h>
#include <binder/IBinder.h>
#include <memory>
#include <vector>
namespace android {
using binder_proxy_limit_callback = std::function<void(int)>;
using binder_proxy_warning_callback = std::function<void(int)>;
class BpBinder final : public IBinder {
  AIBinder *native;
  mutable std::once_flag descriptorOnce;
  mutable String16 descriptor;
  struct Link {
    wp<IBinder> who;
    wp<DeathRecipient> observer;
    void *cookie;
    AIBinder_DeathRecipient *recipient = nullptr;
    Link(const sp<IBinder> &b, const sp<DeathRecipient> &d, void *c)
        : who(b), observer(d), cookie(c) {}
    ~Link() {
      if (recipient)
        AIBinder_DeathRecipient_delete(recipient);
    }
  };
  std::mutex linksMutex;
  bool obitsSent = false;
  std::vector<std::shared_ptr<Link>> links;
  int32_t trackedUid;
  void forget(const std::shared_ptr<Link> &);
public:
  BpBinder(AIBinder *n, int32_t uid) : native(n), trackedUid(uid) {}
  ~BpBinder() override;
  static sp<BpBinder> create(AIBinder *, std::function<void()> *);
  BpBinder *remoteBinder() override { return this; }
  AIBinder *acquireNative() override;
  const String16 &getInterfaceDescriptor() const override;
  status_t transact(uint32_t, const Parcel &, Parcel *, uint32_t = 0) override;
  status_t linkToDeath(const sp<DeathRecipient> &, void * = nullptr, uint32_t = 0) override;
  status_t unlinkToDeath(const wp<DeathRecipient> &, void * = nullptr, uint32_t = 0, wp<DeathRecipient> * = nullptr) override;
  static uint32_t getBinderProxyCount();
  static uint32_t getBinderProxyCount(uint32_t);
  static void getCountByUid(Vector<uint32_t> &, Vector<uint32_t> &);
  static void setCountByUidEnabled(bool);
  static void enableCountByUid() { setCountByUidEnabled(true); }
  static void disableCountByUid() { setCountByUidEnabled(false); }
  static void setBinderProxyCountEventCallback(binder_proxy_limit_callback, binder_proxy_warning_callback);
  static void setBinderProxyCountWatermarks(int, int, int);
};
} // namespace android
