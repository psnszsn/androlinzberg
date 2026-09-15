// Host process bootstrap follows main_mediaserver.cpp. The pinned resource
// services retain their Binder clients and policy; the session replaces init.
#include <MediaPlayerService.h>
// Match build/codec2.zig: these Soong libraries have incompatible private
// android::DeathNotifier helpers and share one hosted server ELF here.
#define DeathNotifier AzkResourceDeathNotifier
#include <ResourceManagerService.h>
#include <ResourceObserverService.h>
#undef DeathNotifier
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <cstdlib>
#include <cutils/properties.h>
#include <C2PlatformSupport.h>
#include <codec2/aidl/ComponentStore.h>
#include "FfmpegStore.h"

extern "C" int azk_media_service_start() {
    ABinderProcess_startThreadPool();
    for (const char *name : {"activity", "processinfo", "batterystats"}) {
        AIBinder *service = AServiceManager_checkService(name);
        if (!service) return -1;
        AIBinder_decStrong(service);
    }
    // Match the hosted allocator and the declared AIDL HAL transport. All clients
    // read the same session property area before creating their first codec.
    if (property_set("ro.vendor.api_level", "202404") != 0 ||
        property_set("media.c2.hal.selection", "aidl") != 0 ||
        // Use the existing compositor's real frame times through CCodec's
        // standard configuration, rather than its requested-time fallback.
        property_set("persist.device_config.media_native.render_metrics_enabled", "true") != 0) return -1;
    const auto mask = std::to_string((1u << android::C2PlatformAllocatorStore::BLOB) |
                                    (1u << android::C2PlatformAllocatorStore::BUFFERQUEUE));
    if (property_set("debug.stagefright.c2-poolmask", mask.c_str()) != 0) return -1;
    using HalStore = aidl::android::hardware::media::c2::utils::ComponentStore;
    for (const auto &[domain, instance] : {
            std::pair{C2Component::DOMAIN_AUDIO, "software"},
            std::pair{C2Component::DOMAIN_VIDEO, "default"}}) {
        auto store = ndk::SharedRefBase::make<HalStore>(azk::codec2::createStore(domain));
        if (store->status() != C2_OK) return -1;
        const auto name = std::string("android.hardware.media.c2.IComponentStore/") + instance;
        if (AServiceManager_addService(store->asBinder().get(), name.c_str()) != STATUS_OK) return -1;
    }
    android::ResourceManagerService::instantiate();
    android::MediaPlayerService::instantiate();
    for (const char *name : {android::ResourceManagerService::getServiceName(),
                            android::ResourceObserverService::getServiceName(), "media.player"}) {
        AIBinder *service = AServiceManager_checkService(name);
        if (!service) return -1;
        AIBinder_decStrong(service);
    }
    return 0;
}

// Android init stops the service with process lifetime. Binder worker threads
// remain live, so shutdown cannot run C++ static destructors beneath them.
extern "C" [[noreturn]] void azk_media_service_exit(int status) { std::_Exit(status); }

namespace android {
// MediaPlayerService::dump calls Bionic allocation tracing. Host libc does not
// expose allocation enumeration or Bionic's malloc_debug records. Report this
// unavailable diagnostic explicitly; it is unrelated to playback allocation.
std::string dumpMemoryAddresses(size_t) {
    return "Allocation backtraces are unavailable with the host allocator.\n";
}
std::string GetUnreachableMemoryString(bool, size_t) {
    return "Unreachable-memory scanning is unavailable with the host allocator.\n";
}
} // namespace android
