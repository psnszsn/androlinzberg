#pragma once
#include <android/binder_manager.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IPermissionController.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include <log/log.h>
#include <functional>
#include <variant>
namespace android {
class IServiceManager : public virtual RefBase {
public:
  static constexpr int DUMP_FLAG_PRIORITY_DEFAULT = 1 << 3;
  sp<IBinder> checkService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_checkService(String8(name).c_str()));
  }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  sp<IBinder> getService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_getService(String8(name).c_str()));
  }
#pragma clang diagnostic pop
  sp<IBinder> waitForService(const String16 &name) {
    return IBinder::fromNative(
        AServiceManager_waitForService(String8(name).c_str()));
  }
  bool isDeclared(const String16 &name) {
    return AServiceManager_isDeclared(String8(name).c_str());
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
  // Follows AOSP CppBackendShim::registerForNotifications over the broker's
  // IServiceCallback route (ServiceNotifications.cpp in the native island).
  class LocalRegistrationCallback : public virtual RefBase {
  public:
    virtual void onServiceRegistration(const String16 &name,
                                       const sp<IBinder> &binder) = 0;
    virtual ~LocalRegistrationCallback() {}
  };
  status_t registerForNotifications(const String16 &name,
                                    const sp<LocalRegistrationCallback> &cb);
  status_t unregisterForNotifications(const String16 &name,
                                      const sp<LocalRegistrationCallback> &cb);
};
inline sp<IServiceManager> defaultServiceManager() {
  static sp<IServiceManager> manager = sp<IServiceManager>::make();
  return manager;
}
template <typename INTERFACE>
sp<INTERFACE> waitForService(const String16 &name) {
  return interface_cast<INTERFACE>(
      defaultServiceManager()->waitForService(name));
}
// AOSP IServiceManager.h::waitForDeclaredService, using the hosted service
// manager's declaration query rather than Android's VINTF service manager.
template <typename INTERFACE>
sp<INTERFACE> waitForDeclaredService(const String16 &name) {
  const sp<IServiceManager> manager = defaultServiceManager();
  if (!manager->isDeclared(name)) return nullptr;
  return interface_cast<INTERFACE>(manager->waitForService(name));
}
// Follows AOSP IServiceManager.cpp::checkPermission via IPermissionController.
// A hosted process may intentionally omit system services: fail closed when
// unavailable instead of waiting forever for Android system_server to restart.
inline bool checkPermission(const String16 &permission, pid_t pid, uid_t uid,
                            bool logPermissionFailure = true) {
  auto service = defaultServiceManager()->checkService(String16("permission"));
  auto controller = interface_cast<IPermissionController>(service);
  const bool granted =
      controller && controller->checkPermission(permission, pid, uid);
  if (!granted && logPermissionFailure)
    ALOGW("Permission denied: %s uid=%u pid=%d%s", String8(permission).c_str(),
          uid, pid, controller ? "" : " (permission service unavailable)");
  return granted;
}
// AOSP IServiceManager.cpp::checkCallingPermission. The transport supplies
// authenticated Binder caller identity to the existing permission client.
inline bool checkCallingPermission(const String16 &permission, int32_t *outPid,
                                   int32_t *outUid) {
  auto *state = IPCThreadState::self();
  const pid_t pid = state->getCallingPid();
  const uid_t uid = state->getCallingUid();
  if (outPid) *outPid = pid;
  if (outUid) *outUid = uid;
  return checkPermission(permission, pid, uid);
}
inline bool checkCallingPermission(const String16 &permission) {
  return checkCallingPermission(permission, nullptr, nullptr);
}
} // namespace android
