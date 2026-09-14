// Exercise the original resource service through Binder into the hosted battery
// service. The system-test driver supplies the production session and identity.
#include <aidl/android/media/BnResourceManagerClient.h>
#include <aidl/android/media/IResourceManagerService.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <binder/IPCThreadState.h>
#include <cassert>
#include <cstdio>
#include <iostream>
#include <string>
#include <unistd.h>

using namespace aidl::android::media;
struct Client final : BnResourceManagerClient {
    ::ndk::ScopedAStatus reclaimResource(bool* result) override {
        *result = false;
        return ::ndk::ScopedAStatus::ok();
    }
    ::ndk::ScopedAStatus getName(std::string* name) override {
        *name = "native-video-resource-fixture";
        return ::ndk::ScopedAStatus::ok();
    }
};
static std::string dump(AIBinder* binder) {
    auto* file = std::tmpfile();
    assert(file);
    assert(AIBinder_dump(binder, fileno(file), nullptr, 0) == STATUS_OK);
    std::rewind(file);
    std::string text;
    char bytes[1024];
    while (size_t count = std::fread(bytes, 1, sizeof(bytes), file)) text.append(bytes, count);
    assert(!std::ferror(file));
    std::fclose(file);
    return text;
}
int main() {
    ABinderProcess_startThreadPool();
    assert(android::IPCThreadState::self()->getCallingUid() == 1000);
    ::ndk::SpAIBinder binder(AServiceManager_checkService("media.resource_manager"));
    ::ndk::SpAIBinder observer(AServiceManager_checkService("media.resource_observer"));
    ::ndk::SpAIBinder battery(AServiceManager_checkService("batterystats"));
    assert(binder.get() && observer.get() && battery.get());
    auto service = IResourceManagerService::fromBinder(binder);
    assert(service && service->isRemote());
    auto client = ::ndk::SharedRefBase::make<Client>();
    ClientInfoParcel info;
    info.pid = getpid();
    info.uid = 1000;
    info.id = 741;
    info.name = "native-video-resource-fixture";
    MediaResourceParcel video;
    video.type = MediaResourceType::kBattery;
    video.subType = MediaResourceSubType::kSwVideoCodec;
    video.value = 1;
    const auto before = dump(battery.get());
    std::cerr << "Battery before native video resource:\n" << before << std::endl;
    assert(before.find("uid={}") != std::string::npos);
    assert(service->notifyClientCreated(info).isOk());
    assert(service->addResource(info, client, {video}).isOk());
    const auto during = dump(battery.get());
    std::cerr << "Battery during native video resource:\n" << during << std::endl;
    assert(during.find("uid={1000=") != std::string::npos);
    assert(dump(binder.get()).find("native-video-resource-fixture") != std::string::npos);
    assert(service->removeResource(info, {video}).isOk());
    assert(service->removeClient(info).isOk());
    assert(service->removeClient(info).isOk());
    std::cout << "PASS native resource manager publication, remote client lifetime and battery accounting across Binder\n";
}
