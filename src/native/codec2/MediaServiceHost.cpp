// Host process bootstrap follows main_mediaserver.cpp. The pinned resource
// services retain their Binder clients and policy; the session replaces init.
#include <ResourceManagerService.h>
#include <ResourceObserverService.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <cstdlib>

extern "C" int azk_media_service_start() {
    ABinderProcess_startThreadPool();
    for (const char *name : {"activity", "processinfo", "batterystats"}) {
        AIBinder *service = AServiceManager_checkService(name);
        if (!service) return -1;
        AIBinder_decStrong(service);
    }
    android::ResourceManagerService::instantiate();
    for (const char *name : {android::ResourceManagerService::getServiceName(),
                            android::ResourceObserverService::getServiceName()}) {
        AIBinder *service = AServiceManager_checkService(name);
        if (!service) return -1;
        AIBinder_decStrong(service);
    }
    return 0;
}

// Android init stops the service with process lifetime. Binder worker threads
// remain live, so shutdown cannot run C++ static destructors beneath them.
extern "C" [[noreturn]] void azk_media_service_exit(int status) { std::_Exit(status); }
