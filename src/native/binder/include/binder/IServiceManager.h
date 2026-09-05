#pragma once
#include <android/binder_manager.h>
#include <binder/IBinder.h>
#include <utils/String8.h>
namespace android {
class IServiceManager : public virtual RefBase {
public:
  sp<IBinder> checkService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_checkService(String8(name).c_str()));
  }
  sp<IBinder> getService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_getService(String8(name).c_str()));
  }
  sp<IBinder> waitForService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_waitForService(String8(name).c_str()));
  }
  status_t addService(const String16 &name, const sp<IBinder> &service,
                      bool allowIsolated = false, int dumpsysFlags = 0) {
    if (!service)
      return BAD_VALUE;
    if (allowIsolated || dumpsysFlags)
      return INVALID_OPERATION;
    auto *n = service->acquireNative();
    auto s = AServiceManager_addService(n, String8(name).c_str());
    AIBinder_decStrong(n);
    return s;
  }
};
inline sp<IServiceManager> defaultServiceManager() {
  static sp<IServiceManager> manager = sp<IServiceManager>::make();
  return manager;
}
} // namespace android
