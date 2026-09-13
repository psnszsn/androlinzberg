#pragma once
#include <assert.h>
// Source-built Android Binder API over the shared Zig runtime.
struct AIBinder;
struct AIBinder_Weak;
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <utils/String16.h>
// Pinned IBinder's transaction-code packing, also used by original HIDL adapters.
#ifndef B_PACK_CHARS
#define B_PACK_CHARS(c1, c2, c3, c4) \
    ((((c1)<<24)) | (((c2)<<16)) | (((c3)<<8)) | (c4))
#endif
#include <functional>
#include <map>
#include <mutex>
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
                            DUMP_TRANSACTION = 0x5f444d50,
                            SHELL_COMMAND_TRANSACTION = 0x5f434d44,
                            SYSPROPS_TRANSACTION = 0x5f535052;
  static constexpr uint32_t FLAG_CLEAR_BUF = 0x20,
                            FLAG_PRIVATE_LOCAL = 0x10000000;
  virtual bool isBinderAlive() const;
  virtual status_t pingBinder();
  virtual status_t dump(int fd, const Vector<String16> &args);
  virtual bool checkSubclass(const void *) const { return false; }
  status_t getExtension(sp<IBinder> *);
  class FrozenStateChangeCallback : public virtual RefBase {
  public:
    enum class State { FROZEN, UNFROZEN };
    virtual void onStateChanged(const wp<IBinder> &, State) = 0;
  };
  // AZKB has no freezer notification source. Match libbinder's unsupported
  // driver result; ProcessState does not advertise this feature.
  status_t addFrozenStateChangeCallback(const wp<FrozenStateChangeCallback> &) { return INVALID_OPERATION; }
  status_t removeFrozenStateChangeCallback(const wp<FrozenStateChangeCallback> &) { return INVALID_OPERATION; }
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
  using object_cleanup_func = void (*)(const void *, void *, void *);
  using object_make_func = sp<IBinder> (*)(const void *);
  [[nodiscard]] virtual void *attachObject(const void *, void *, void *,
                                           object_cleanup_func);
  [[nodiscard]] virtual void *findObject(const void *) const;
  [[nodiscard]] virtual void *detachObject(const void *);
  sp<IBinder> lookupOrCreateWeak(const void *, object_make_func, const void *);
  void withLock(const std::function<void()> &);

protected:
  ~IBinder() override;

private:
  struct Object {
    void *value = nullptr;
    void *cookie = nullptr;
    object_cleanup_func cleanup = nullptr;
  };
  mutable std::mutex objectsMutex;
  std::map<const void *, Object> objects;
};
} // namespace android
