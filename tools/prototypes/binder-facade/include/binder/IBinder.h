#pragma once
// Experimental source API, backed by the shared Zig runtime. Not a binary ABI
// replacement.
#include <android/binder_ibinder.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
namespace android {
class Parcel;
class IInterface;
class BBinder;
class BpBinder;
class IBinder : public virtual RefBase {
 public:
  static constexpr uint32_t FIRST_CALL_TRANSACTION = 1, FLAG_ONEWAY = 1;
  class DeathRecipient : public virtual RefBase {
   public:
    virtual void binderDied(const wp<IBinder>&) = 0;
  };
  virtual status_t transact(uint32_t, const Parcel&, Parcel*, uint32_t = 0) = 0;
  virtual const String16& getInterfaceDescriptor() const = 0;
  virtual sp<IInterface> queryLocalInterface(const String16&);
  virtual BBinder* localBinder() { return nullptr; }
  virtual BpBinder* remoteBinder() { return nullptr; }
  virtual status_t linkToDeath(const sp<DeathRecipient>&) {
    return INVALID_OPERATION;
  }
  virtual status_t unlinkToDeath(const sp<DeathRecipient>&) {
    return INVALID_OPERATION;
  }
  // Caller owns one native strong reference returned by this prototype seam.
  virtual AIBinder* acquireNative() = 0;
  static sp<IBinder> fromNative(AIBinder* owned);

 protected:
  ~IBinder() override = default;
};
}  // namespace android
