#pragma once
#include <android/binder_manager.h>
#include <binder/IBinder.h>
#include <binder/IPermissionController.h>
#include <utils/String8.h>
#include <log/log.h>
namespace android {
class IServiceManager : public virtual RefBase {
public:
  static constexpr int DUMP_FLAG_PRIORITY_DEFAULT = 1 << 3;
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
// Pinned IServiceManager.checkPermission: resolve the permission controller and retry
// after Binder death. The hosted registry owns waiting instead of a kernel-service poll.
inline bool checkPermission(const String16& permission, pid_t pid, uid_t uid,
                            bool logPermissionFailure = true) {
  for (;;) {
    sp<IBinder> binder = defaultServiceManager()->waitForService(String16("permission"));
    if (!binder) return false;
    sp<IPermissionController> controller = interface_cast<IPermissionController>(binder);
    if (!controller) return false;
    bool granted = controller->checkPermission(permission, pid, uid);
    if (granted) return true;
    if (binder->isBinderAlive()) {
      if (logPermissionFailure) ALOGW("Permission failure: %s from uid=%d pid=%d",
          String8(permission).c_str(), uid, pid);
      return false;
    }
  }
}
} // namespace android
