#pragma once
#include <binder/IBinder.h>

#include <mutex>
namespace android {
class BBinder : public IBinder {
  AIBinder_Weak* weak = nullptr;
  std::mutex mutex;

 public:
  BBinder() = default;
  ~BBinder() override;
  const String16& getInterfaceDescriptor() const override;
  BBinder* localBinder() override { return this; }
  AIBinder* acquireNative() override;
  status_t transact(uint32_t, const Parcel&, Parcel*, uint32_t = 0) override;
  virtual status_t onTransact(uint32_t, const Parcel&, Parcel*, uint32_t = 0) {
    return UNKNOWN_TRANSACTION;
  }
};
// Reuse the pinned IInterface.h Bp/Bn templates. This transport-independent
// holder deliberately uses ordinary strong ownership; weak resurrection is
// unproven.
class BpRefBase : public virtual RefBase {
  sp<IBinder> object;

 public:
  explicit BpRefBase(const sp<IBinder>& b) : object(b) {}
  IBinder* remote() const { return object.get(); }
  const sp<IBinder>& remoteStrong() const { return object; }
};
}  // namespace android
