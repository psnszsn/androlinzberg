#include "FfmpegStore.h"
#include "Codec2AudioFixtures.h"
#include "Codec2VideoFixtures.h"
#include "GpuBufferRenderer.h"
#include "../../src/codec/azk_codec.h"
#include <C2PlatformSupport.h>
#include <C2Config.h>
#include <codec2/aidl/ComponentStore.h>
#include <codec2/hidl/client.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <cutils/properties.h>
#include <gui/BufferItemConsumer.h>
#include <gui/BufferItem.h>
#include <gui/Surface.h>
#include <media/stagefright/MediaCodecList.h>
#include <media/stagefright/MediaCodecConstants.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <ResourceManagerService.h>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <map>
#include <mutex>
#include <set>

using android::Codec2Client;
using HalStore = aidl::android::hardware::media::c2::utils::ComponentStore;
constexpr const char *instance = "android.hardware.media.c2.IComponentStore/software";
constexpr const char *videoInstance = "android.hardware.media.c2.IComponentStore/default";

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
    void onFrameRendered(uint64_t, int32_t, int64_t) override {}
};

static void video(const std::shared_ptr<Codec2Client> &client, const char *name, const char *encoder) {
    using namespace android;
    GpuBufferRenderer renderer;
    auto listener = std::make_shared<Listener>();
    std::shared_ptr<Codec2Client::Component> component;
    assert(client->createComponent(name, listener, &component) == C2_OK);
    auto [consumer, surface] = BufferItemConsumer::create(GRALLOC_USAGE_HW_TEXTURE, 2);
    assert(native_window_api_connect(surface.get(), NATIVE_WINDOW_API_MEDIA) == OK);
    assert(surface->setGenerationNumber(37) == OK);
    C2BlockPool::local_id_t poolId;
    std::shared_ptr<Codec2Client::Configurable> configurable;
    assert(component->createBlockPool(C2PlatformAllocatorStore::IGBA, &poolId, &configurable) == C2_OK);
    assert(component->setOutputSurface(poolId, surface->getIGraphicBufferProducer(), 37, 16) == C2_OK);
    auto poolIds = C2PortBlockPoolsTuning::output::AllocUnique({poolId});
    std::vector<std::unique_ptr<C2SettingResult>> failures;
    assert(component->config({poolIds.get()}, C2_MAY_BLOCK, &failures) == C2_OK && failures.empty());
    assert(component->start() == C2_OK);
    std::shared_ptr<C2BlockPool> pool;
    assert(GetCodec2BlockPool(C2BlockPool::BASIC_LINEAR, nullptr, &pool) == C2_OK);
    const auto packets = videoPackets(encoder);
    for (uint32_t generation = 0; generation < 2; ++generation) {
        std::list<std::unique_ptr<C2Work>> queued;
        for (size_t i = 0; i < packets.size(); ++i)
            queued.push_back(input(pool, packets[i], UINT64_MAX - i, i + 1 == packets.size()));
        assert(component->queue(&queued) == C2_OK);
        queued.clear();
        std::list<std::unique_ptr<C2Work>> done;
        {
            std::unique_lock lock(listener->mutex);
            assert(listener->changed.wait_for(lock, std::chrono::seconds(20), [&] {
                size_t completed = 0;
                for (const auto &work : listener->done)
                    if (!(work->worklets.front()->output.flags & C2FrameData::FLAG_INCOMPLETE)) ++completed;
                return listener->error || listener->died || completed == packets.size();
            }));
            assert(!listener->error && !listener->died);
            done.splice(done.end(), listener->done);
        }
        std::set<uint64_t> frames, completions;
        size_t eos = 0;
        for (const auto &work : done) {
            assert(work->result == C2_OK && work->workletsProcessed == 1);
            const auto &out = work->worklets.front()->output;
            const auto index = out.ordinal.frameIndex.peeku();
            assert(index > UINT64_MAX - packets.size());
            if (out.flags & C2FrameData::FLAG_END_OF_STREAM) ++eos;
            if (out.buffers.empty()) { assert(completions.insert(index).second); continue; }
            assert(frames.insert(index).second && out.ordinal.timestamp == 7654321);
            const auto block = out.buffers.front()->data().graphicBlocks().front();
            assert(block.width() == 318 && block.height() == 238);
            auto info = out.buffers.front()->getInfo(C2StreamDataSpaceInfo::output::PARAM_TYPE);
            assert(info);
            const auto dataspace = C2StreamDataSpaceInfo::output::From(info.get())->value;
            IGraphicBufferProducer::QueueBufferInput qbi(7654321000LL, false,
                    static_cast<android_dataspace>(dataspace), Rect(318, 238),
                    NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW, 0, Fence::NO_FENCE);
            IGraphicBufferProducer::QueueBufferOutput qbo;
            assert(component->queueToOutputSurface(block, qbi, &qbo) == OK);
            BufferItem item;
            assert(consumer->acquireBuffer(&item, 0) == OK);
            assert(item.mTimestamp == 7654321000LL && item.mDataSpace == static_cast<android_dataspace>(dataspace));
            assert(item.mGraphicBuffer->getWidth() == 318 && item.mGraphicBuffer->getHeight() == 238);
            assertRed(renderer.readPixel(item.mGraphicBuffer));
            assert(consumer->releaseBuffer(item) == OK);
            if (frames.size() == packets.size() / 2) {
                auto [nextConsumer, nextSurface] = BufferItemConsumer::create(GRALLOC_USAGE_HW_TEXTURE, 2);
                assert(native_window_api_connect(nextSurface.get(), NATIVE_WINDOW_API_MEDIA) == OK);
                assert(nextSurface->setGenerationNumber(38 + generation) == OK);
                assert(component->setOutputSurface(poolId, nextSurface->getIGraphicBufferProducer(), 38 + generation, 16) == C2_OK);
                assert(native_window_api_disconnect(surface.get(), NATIVE_WINDOW_API_MEDIA) == OK);
                consumer->abandon();
                consumer = nextConsumer; surface = nextSurface;
            }
        }
        assert(frames.size() == packets.size() && completions == frames && eos == 1);
        done.clear();
        if (generation == 0) {
            std::list<std::unique_ptr<C2Work>> flushed;
            assert(component->flush(C2Component::FLUSH_COMPONENT, &flushed) == C2_OK && flushed.empty());
        }
    }
    assert(component->stop() == C2_OK);
    assert(component->destroyBlockPool(poolId) == C2_OK);
    assert(component->release() == C2_OK);
    component.reset();
    assert(native_window_api_disconnect(surface.get(), NATIVE_WINDOW_API_MEDIA) == OK);
    consumer->abandon();
    std::cout << "PASS " << name << " stock AIDL video allocator, VAAPI decode, cropped RGB output, "
                 "work reordering, flush, dataspace and Surface migration pixels" << std::endl;
}

static void nativeMediaCodec(bool video, bool aac = false) {
    using namespace android;
    std::unique_ptr<GpuBufferRenderer> renderer;
    sp<BufferItemConsumer> consumer;
    sp<Surface> surface;
    if (video) {
        renderer = std::make_unique<GpuBufferRenderer>();
        std::tie(consumer, surface) = BufferItemConsumer::create(GRALLOC_USAGE_HW_TEXTURE, 2);
    }
    const auto aacData = aac ? encodeAudio("aac", AV_SAMPLE_FMT_FLTP, 44100) : Encoded{};
    const char *mime = video ? "video/avc" : aac ? "audio/mp4a-latm" : "audio/mpeg";
    AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
    assert(codec);
    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
    if (video) {
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 318);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 238);
    } else {
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 44100);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
    }
    if (aac) {
        assert(aacData.csd.size() == 1);
        AMediaFormat_setBuffer(format, "csd-0", aacData.csd[0].data(), aacData.csd[0].size());
    }
    assert(AMediaCodec_configure(codec, format, surface.get(), nullptr, 0) == AMEDIA_OK);
    AMediaFormat_delete(format);
    assert(AMediaCodec_start(codec) == AMEDIA_OK);
    const auto packets = video ? videoPackets() : aac ?
        std::vector<std::vector<uint8_t>>(aacData.packets.begin(), aacData.packets.begin() + 2) :
        std::vector<std::vector<uint8_t>>{twoMp3Frames()};
    ssize_t index;
    size_t capacity = 0;
    for (const auto &encoded : packets) {
        index = AMediaCodec_dequeueInputBuffer(codec, 2000000);
        assert(index >= 0);
        uint8_t *input = AMediaCodec_getInputBuffer(codec, index, &capacity);
        assert(input && capacity >= encoded.size());
        std::memcpy(input, encoded.data(), encoded.size());
        assert(AMediaCodec_queueInputBuffer(codec, index, 0, encoded.size(), 7654321, 0) == AMEDIA_OK);
    }
    index = AMediaCodec_dequeueInputBuffer(codec, 2000000);
    assert(index >= 0);
    assert(AMediaCodec_queueInputBuffer(codec, index, 0, 0, 7654321, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) == AMEDIA_OK);
    size_t bytes = 0, frames = 0;
    bool eos = false, changed = false;
    for (unsigned attempt = 0; attempt < 24 && !eos; ++attempt) {
        AMediaCodecBufferInfo info{};
        index = AMediaCodec_dequeueOutputBuffer(codec, &info, 500000);
        if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            format = AMediaCodec_getOutputFormat(codec);
            int32_t first = 0, second = 0;
            assert(format && AMediaFormat_getInt32(format, video ? AMEDIAFORMAT_KEY_WIDTH : AMEDIAFORMAT_KEY_SAMPLE_RATE, &first));
            assert(AMediaFormat_getInt32(format, video ? AMEDIAFORMAT_KEY_HEIGHT : AMEDIAFORMAT_KEY_CHANNEL_COUNT, &second));
            assert(first == (video ? 318 : 44100) && second == (video ? 238 : 2));
            AMediaFormat_delete(format);
            changed = true;
        } else if (index >= 0) {
            eos = info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM;
            if (video) {
                const bool render = info.size != 0;
                assert(AMediaCodec_releaseOutputBuffer(codec, index, render) == AMEDIA_OK);
                if (render) {
                    BufferItem item;
                    assert(consumer->acquireBuffer(&item, 0) == OK);
                    assertRed(renderer->readPixel(item.mGraphicBuffer));
                    assert(consumer->releaseBuffer(item) == OK);
                    ++frames;
                }
            } else {
                uint8_t *output = AMediaCodec_getOutputBuffer(codec, index, &capacity);
                assert(info.offset >= 0 && info.size >= 0 && size_t(info.offset) + info.size <= capacity);
                if (info.size) {
                    assert(output);
                    if (!bytes) assert(info.presentationTimeUs == 7654321);
                    bytes += info.size;
                }
                assert(AMediaCodec_releaseOutputBuffer(codec, index, false) == AMEDIA_OK);
            }
        } else assert(index == AMEDIACODEC_INFO_TRY_AGAIN_LATER || index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED);
    }
    assert(eos && changed);
    if (video) assert(frames == packets.size());
    else assert(bytes == 2 * (aac ? 1024 : 1152) * 2 * sizeof(int16_t));
    assert(AMediaCodec_stop(codec) == AMEDIA_OK);
    assert(AMediaCodec_delete(codec) == AMEDIA_OK);
    if (consumer) consumer->abandon();
    std::cout << "PASS original NDK MediaCodec and CCodec " << mime << " decode through the stock AIDL service" << std::endl;
}

int codec2ProcessTestMain(int argc, char **argv) {
    ABinderProcess_startThreadPool();
    if (argc > 1 && std::strcmp(argv[1], "server") == 0) {
        assert(property_set("ro.vendor.api_level", "202404") == 0);
        assert(property_set("media.c2.hal.selection", "aidl") == 0);
        const auto mask = std::to_string((1u << android::C2PlatformAllocatorStore::BLOB) | (1u << android::C2PlatformAllocatorStore::BUFFERQUEUE));
        assert(property_set("debug.stagefright.c2-poolmask", mask.c_str()) == 0);
        auto service = ndk::SharedRefBase::make<HalStore>(azk::codec2::createStore(C2Component::DOMAIN_AUDIO));
        auto videoService = ndk::SharedRefBase::make<HalStore>(azk::codec2::createStore(C2Component::DOMAIN_VIDEO));
        assert(service->status() == C2_OK);
        assert(AServiceManager_addService(service->asBinder().get(), instance) == STATUS_OK);
        assert(videoService->status() == C2_OK);
        assert(AServiceManager_addService(videoService->asBinder().get(), videoInstance) == STATUS_OK);
        android::ResourceManagerService::instantiate();
        std::cout << "ready" << std::endl;
        std::cin.get();
        std::_Exit(0);
    }
    const auto &names = Codec2Client::GetServiceNames();
    assert(std::set<std::string>(names.begin(), names.end()) == std::set<std::string>({"software", "default"}));
    auto client = Codec2Client::CreateFromService("software");
    auto videoClient = Codec2Client::CreateFromService("default");
    assert(client && AIBinder_isRemote(client->getAidlBase().get()));
    assert(videoClient && AIBinder_isRemote(videoClient->getAidlBase().get()));
    const auto &traits = client->listComponents();
    assert(traits.size() == 5);
    size_t expected = 0;
    for (const char *backend : {"h264", "vp9", "mpeg4"}) expected += azk_codec_video_profiles(backend) != 0;
    assert(videoClient->listComponents().size() == expected);
    for (const auto &trait : videoClient->listComponents())
        assert(trait.domain == C2Component::DOMAIN_VIDEO && trait.owner == "default");
    for (const auto &trait : traits) {
        assert(trait.domain == C2Component::DOMAIN_AUDIO && trait.owner == "software" &&
                trait.kind == C2Component::KIND_DECODER);
        assert(trait.rank == 8 && !trait.name.empty() && !trait.mediaType.empty());
    }
    // Exercise the original catalogue, XML parser and Codec2 profile mapper.
    // Store instance ownership determines hardware/software flags in AOSP.
    const auto catalogue = android::MediaCodecList::getLocalInstance();
    assert(catalogue && catalogue->countCodecs() == 5 + expected);
    for (size_t i = 0; i < catalogue->countCodecs(); ++i) {
        const auto info = catalogue->getCodecInfo(i);
        assert(info && !info->isEncoder());
        android::Vector<android::AString> types;
        info->getSupportedMediaTypes(&types);
        assert(types.size() == 1);
        const bool hardware = types[0].startsWith("video/");
        assert(bool(info->getAttributes() & android::MediaCodecInfo::kFlagIsHardwareAccelerated) == hardware);
        assert(bool(info->getAttributes() & android::MediaCodecInfo::kFlagIsSoftwareOnly) == !hardware);
        auto caps = info->getCapabilitiesFor(types[0].c_str());
        assert(caps);
        android::Vector<android::MediaCodecInfo::ProfileLevel> profiles;
        caps->getSupportedProfileLevels(&profiles);
        if (hardware || types[0] == "audio/mp4a-latm") assert(!profiles.empty());
        if (hardware) {
            android::AString dimensions;
            assert(caps->getDetails()->findString("size-range", &dimensions) && dimensions == "2x2-4096x4096");
        }
    }
    std::cout << "PASS original MediaCodecList XML, profiles and hardware/software attributes" << std::endl;
    nativeMediaCodec(false);
    nativeMediaCodec(false, true);
    nativeMediaCodec(true);
    std::shared_ptr<Codec2Client::Interface> interface;
    assert(client->createInterface("c2.artzig.avc.decoder", &interface) == C2_NOT_FOUND && !interface);
    assert(videoClient->createInterface("c2.artzig.mp3.decoder", &interface) == C2_NOT_FOUND && !interface);
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
    video(videoClient, "c2.artzig.avc.decoder", "libx264");
    video(videoClient, "c2.artzig.vp9.decoder", "libvpx-vp9");
    if (azk_codec_video_profiles("mpeg4")) {
        video(videoClient, "c2.artzig.mpeg4.decoder", "mpeg4");
    } else {
        const auto previous = interface;
        assert(videoClient->createInterface("c2.artzig.mpeg4.decoder", &interface) == C2_NOT_FOUND && interface == previous);
        assert(videoClient->createComponent("c2.artzig.mpeg4.decoder", listener, &component) == C2_NOT_FOUND && !component);
        std::cout << "PASS unavailable MPEG-4 hardware is excluded from Codec2 discovery" << std::endl;
    }
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
