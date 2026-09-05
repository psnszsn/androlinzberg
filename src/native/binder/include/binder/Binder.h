#pragma once
#include <binder/IBinder.h>

#include <mutex>
namespace android {
class BBinder : public IBinder {
  AIBinder_Weak *weak = nullptr;
  std::mutex mutex;

public:
  BBinder() = default;
  ~BBinder() override;
  const String16 &getInterfaceDescriptor() const override;
  BBinder *localBinder() override { return this; }
  AIBinder *acquireNative() override;
  status_t transact(uint32_t, const Parcel &, Parcel *, uint32_t = 0) override;
  virtual status_t onTransact(uint32_t, const Parcel &, Parcel *,
                              uint32_t = 0) {
    return UNKNOWN_TRANSACTION;
  }
};
// Source note: pinned Binder.cpp BpRefBase lifecycle. The surrounding file
// depends on kernel Binder; these reference transitions are transport agnostic.
class BpRefBase : public virtual RefBase {
  IBinder *mRemote;
  RefBase::weakref_type *mRefs;
  std::atomic<uint32_t> mState;

public:
  explicit BpRefBase(const sp<IBinder> &);
  IBinder *remote() const { return mRemote; }
  sp<IBinder> remoteStrong() const {
    return sp<IBinder>::fromExisting(mRemote);
  }

protected:
  ~BpRefBase() override;
  void onFirstRef() override;
  void onLastStrongRef(const void *) override;
  bool onIncStrongAttempted(uint32_t, const void *) override;
};
} // namespace android
