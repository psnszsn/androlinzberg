// AOSP CppBackendShim::registerForNotifications / unregisterForNotifications
// (frameworks/native/libs/binder/IServiceManager.cpp) over the broker's
// IServiceCallback registration at the context object. The stock file cannot be
// compiled: it owns the kernel-backed service manager shim replaced by AZKB.
#include <android/os/BnServiceCallback.h>
#include <android/os/IServiceManager.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <log/log.h>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace android {
namespace {
using AidlRegistrationCallback = IServiceManager::LocalRegistrationCallback;

class RegistrationWaiter : public os::BnServiceCallback {
public:
    explicit RegistrationWaiter(const sp<AidlRegistrationCallback>& callback) : mImpl(callback) {}
    binder::Status onRegistration(const std::string& name, const sp<IBinder>& binder) override {
        mImpl->onServiceRegistration(String16(name.c_str()), binder);
        return binder::Status::ok();
    }
    const sp<AidlRegistrationCallback> mImpl;
};

using LocalRegistrationAndWaiter = std::pair<sp<AidlRegistrationCallback>, sp<RegistrationWaiter>>;
using ServiceCallbackMap = std::map<std::string, std::vector<LocalRegistrationAndWaiter>>;
std::mutex gNameToRegistrationLock;
ServiceCallbackMap gNameToRegistrationCallback;

sp<os::IServiceManager> unifiedServiceManager() {
    static const sp<os::IServiceManager> manager =
            interface_cast<os::IServiceManager>(ProcessState::self()->getContextObject(nullptr));
    return manager;
}
}  // namespace

status_t IServiceManager::registerForNotifications(const String16& name,
                                                   const sp<AidlRegistrationCallback>& cb) {
    if (cb == nullptr) {
        ALOGE("%s: null cb passed", __FUNCTION__);
        return BAD_VALUE;
    }
    const std::string nameStr = String8(name).c_str();
    const sp<RegistrationWaiter> registrationWaiter = sp<RegistrationWaiter>::make(cb);
    std::lock_guard<std::mutex> lock(gNameToRegistrationLock);
    if (binder::Status status = unifiedServiceManager()->registerForNotifications(nameStr, registrationWaiter);
        !status.isOk()) {
        ALOGW("Failed to registerForNotifications for %s: %s", nameStr.c_str(), status.toString8().c_str());
        return UNKNOWN_ERROR;
    }
    gNameToRegistrationCallback[nameStr].push_back(std::make_pair(cb, registrationWaiter));
    return OK;
}

status_t IServiceManager::unregisterForNotifications(const String16& name,
                                                     const sp<AidlRegistrationCallback>& cb) {
    if (cb == nullptr) {
        ALOGE("%s: null cb passed", __FUNCTION__);
        return BAD_VALUE;
    }
    const std::string nameStr = String8(name).c_str();
    std::lock_guard<std::mutex> lock(gNameToRegistrationLock);
    auto it = gNameToRegistrationCallback.find(nameStr);
    if (it == gNameToRegistrationCallback.end()) {
        ALOGE("%s no callback registered for %s", __FUNCTION__, nameStr.c_str());
        return BAD_VALUE;
    }
    sp<RegistrationWaiter> waiter;
    for (auto entry = it->second.begin(); entry != it->second.end(); ++entry) {
        if (entry->first == cb) {
            waiter = entry->second;
            it->second.erase(entry);
            break;
        }
    }
    if (waiter == nullptr) {
        ALOGE("%s no callback found for %s", __FUNCTION__, nameStr.c_str());
        return BAD_VALUE;
    }
    if (it->second.empty()) gNameToRegistrationCallback.erase(it);
    if (binder::Status status = unifiedServiceManager()->unregisterForNotifications(nameStr, waiter);
        !status.isOk()) {
        ALOGW("Failed to unregisterForNotifications for %s: %s", nameStr.c_str(), status.toString8().c_str());
        return UNKNOWN_ERROR;
    }
    return OK;
}
}  // namespace android
