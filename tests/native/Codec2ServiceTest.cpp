#include "FfmpegStore.h"
#include "Codec2AudioFixtures.h"
#include <C2PlatformSupport.h>
#include <C2Config.h>
#include <codec2/aidl/ComponentStore.h>
#include <codec2/hidl/client.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <cutils/properties.h>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <map>
#include <mutex>

using android::Codec2Client;
using HalStore = aidl::android::hardware::media::c2::utils::ComponentStore;
constexpr const char *instance = "android.hardware.media.c2.IComponentStore/software";

struct Listener final : Codec2Client::Listener {
    std::mutex mutex;
    std::condition_variable changed;
    std::list<std::unique_ptr<C2Work>> done;
    bool died = false;
    uint32_t error = 0;
    void onWorkDone(const std::weak_ptr<Codec2Client::Component>&,
                    std::list<std::unique_ptr<C2Work>> &work) override {
        std::lock_guard guard(mutex);
        done.splice(done.end(), work);
        changed.notify_all();
    }
    void onTripped(const std::weak_ptr<Codec2Client::Component>&,
                   const std::vector<std::shared_ptr<C2SettingResult>>&) override { assert(false); }
    void onError(const std::weak_ptr<Codec2Client::Component>&, uint32_t code) override {
        std::lock_guard guard(mutex); error = code; changed.notify_all();
    }
    void onDeath(const std::weak_ptr<Codec2Client::Component>&) override {
        std::lock_guard guard(mutex); died = true; changed.notify_all();
    }
    void onInputBufferDone(uint64_t, size_t) override {}
    void onFrameRendered(uint64_t, int32_t, int64_t) override { assert(false); }
};

int codec2ProcessTestMain(int argc, char **argv) {
    ABinderProcess_startThreadPool();
    if (argc > 1 && std::strcmp(argv[1], "server") == 0) {
        assert(property_set("ro.vendor.api_level", "202404") == 0);
        assert(property_set("media.c2.hal.selection", "aidl") == 0);
        const auto mask = std::to_string(1u << android::C2PlatformAllocatorStore::BLOB);
        assert(property_set("debug.stagefright.c2-poolmask", mask.c_str()) == 0);
        auto service = ndk::SharedRefBase::make<HalStore>(azk::codec2::createStore());
        assert(service->status() == C2_OK);
        assert(AServiceManager_addService(service->asBinder().get(), instance) == STATUS_OK);
        std::cout << "ready" << std::endl;
        std::cin.get();
        std::_Exit(0);
    }
    const auto &names = Codec2Client::GetServiceNames();
    assert(names.size() == 1 && names[0] == "software");
    auto client = Codec2Client::CreateFromService("software");
    assert(client && AIBinder_isRemote(client->getAidlBase().get()));
    const auto &traits = client->listComponents();
    assert(traits.size() == 5);
    for (const auto &trait : traits) {
        assert(trait.domain == C2Component::DOMAIN_AUDIO && trait.kind == C2Component::KIND_DECODER);
        assert(trait.rank == 8 && !trait.name.empty() && !trait.mediaType.empty());
    }
    std::shared_ptr<Codec2Client::Interface> interface;
    assert(client->createInterface("missing", &interface) == C2_NOT_FOUND && !interface);
    assert(client->createInterface("c2.artzig.mp3.decoder", &interface) == C2_OK);
    C2StreamSampleRateInfo::output rate(0u);
    std::vector<std::unique_ptr<C2Param>> queried;
    assert(interface->query({&rate}, {}, C2_MAY_BLOCK, &queried) == C2_OK && rate.value == 44100);
    auto listener = std::make_shared<Listener>();
    std::shared_ptr<Codec2Client::Component> component;
    assert(client->createComponent("c2.artzig.mp3.decoder", listener, &component) == C2_OK);
    assert(component->start() == C2_OK);
    std::shared_ptr<C2BlockPool> pool;
    assert(android::GetCodec2BlockPool(C2BlockPool::BASIC_LINEAR, nullptr, &pool) == C2_OK);
    auto bytes = twoMp3Frames();
    std::list<std::unique_ptr<C2Work>> queued;
    queued.push_back(input(pool, bytes, 0, false));
    queued.push_back(input(pool, bytes, UINT64_MAX, true));
    assert(component->queue(&queued) == C2_OK);
    // The original client leaves caller work ownership intact after serialization.
    queued.clear();
    {
        std::unique_lock lock(listener->mutex);
        assert(listener->changed.wait_for(lock, std::chrono::seconds(10), [&] {
            size_t completed = 0;
            for (const auto &work : listener->done)
                if (!(work->worklets.front()->output.flags & C2FrameData::FLAG_INCOMPLETE)) ++completed;
            return listener->error || listener->died || completed == 2;
        }));
        assert(!listener->error && !listener->died);
        std::map<uint64_t, size_t> frames, completed;
        size_t eos = 0;
        for (const auto &work : listener->done) {
            assert(work->result == C2_OK && work->workletsProcessed == 1);
            const auto &out = work->worklets.front()->output;
            const auto id = out.ordinal.frameIndex.peeku();
            if (out.flags & C2FrameData::FLAG_INCOMPLETE) {
                assert(out.buffers.size() == 1);
                auto view = out.buffers.front()->data().linearBlocks().front().map().get();
                assert(view.error() == C2_OK && view.capacity() == 1152 * 2 * 2);
                if (!frames[id]) assert(out.ordinal.timestamp == 7654321);
                ++frames[id];
            } else ++completed[id];
            if (out.flags & C2FrameData::FLAG_END_OF_STREAM) ++eos;
        }
        assert(frames[0] == 2 && frames[UINT64_MAX] == 2 && completed[0] == 1 && completed[UINT64_MAX] == 1 && eos == 1);
        listener->done.clear();
    }
    assert(component->stop() == C2_OK);
    assert(component->release() == C2_OK);
    component.reset();
    auto death = std::make_shared<Listener>();
    assert(client->createComponent("c2.artzig.mp3.decoder", death, &component) == C2_OK);
    std::cout << "PASS stock Codec2 service discovery, traits, reflection, shared buffers and FFmpeg work across Binder\nREADY_DEATH" << std::endl;
    {
        std::unique_lock lock(death->mutex);
        assert(death->changed.wait_for(lock, std::chrono::seconds(10), [&] { return death->died; }));
    }
    component.reset();
    std::cout << "PASS original Codec2 client receives component-service death" << std::endl;
    return 0;
}
