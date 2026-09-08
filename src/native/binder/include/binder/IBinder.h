#pragma once
#include <assert.h>
// Source-built Android Binder API over the shared Zig runtime.
struct AIBinder;
struct AIBinder_Weak;
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/Vector.h>
// Pinned IBinder's transaction-code packing, also used by original HIDL adapters.
#ifndef B_PACK_CHARS
#define B_PACK_CHARS(c1, c2, c3, c4) \
    ((((c1)<<24)) | (((c2)<<16)) | (((c3)<<8)) | (c4))
#endif
namespace android {
class Parcel;
class IInterface;
class BBinder;
class BpBinder;
class IBinder : public virtual RefBase {
public:
  static constexpr uint32_t FIRST_CALL_TRANSACTION = 1,
                            LAST_CALL_TRANSACTION = 0x00ffffff, FLAG_ONEWAY = 1;
  static constexpr uint32_t INTERFACE_TRANSACTION = 0x5f4e5446,
                            PING_TRANSACTION = 0x5f504e47,
                            SHELL_COMMAND_TRANSACTION = 0x5f434d44;
  static constexpr uint32_t FLAG_CLEAR_BUF = 0x20,
                            FLAG_PRIVATE_LOCAL = 0x10000000;
  virtual bool isBinderAlive() const;
  virtual status_t pingBinder();
  class DeathRecipient : public virtual RefBase {
  public:
    virtual void binderDied(const wp<IBinder> &) = 0;
  };
  virtual status_t transact(uint32_t, const Parcel &, Parcel *,
                            uint32_t = 0) = 0;
  virtual const String16 &getInterfaceDescriptor() const = 0;
  virtual sp<IInterface> queryLocalInterface(const String16 &);
  virtual BBinder *localBinder() { return nullptr; }
  virtual BpBinder *remoteBinder() { return nullptr; }
  virtual status_t linkToDeath(const sp<DeathRecipient> &, void * = nullptr,
                               uint32_t = 0) {
    return INVALID_OPERATION;
  }
  virtual status_t unlinkToDeath(const wp<DeathRecipient> &, void * = nullptr,
                                 uint32_t = 0, wp<DeathRecipient> * = nullptr) {
    return INVALID_OPERATION;
  }
  // Caller owns one native strong reference returned by this language seam.
  virtual AIBinder *acquireNative() = 0;
  static sp<IBinder> fromNative(AIBinder *owned);

protected:
  ~IBinder() override = default;
};
} // namespace android
