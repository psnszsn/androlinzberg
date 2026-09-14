#include "FfmpegComponent.h"
#include <C2PlatformSupport.h>
#include <C2Buffer.h>
#include <C2Config.h>
#include <cutils/properties.h>
#include <media/stagefright/foundation/OpusHeader.h>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <iostream>
#include <map>
#include <mutex>
extern "C" {
#include <libavcodec/avcodec.h>
}

using namespace android;
static std::vector<uint8_t> twoMp3Frames() {
    auto *codec = avcodec_find_encoder_by_name("libmp3lame");
    assert(codec);
    auto *ctx = avcodec_alloc_context3(codec);
    assert(ctx);
    ctx->sample_rate = 44100;
    ctx->sample_fmt = AV_SAMPLE_FMT_S16P;
    ctx->bit_rate = 128000;
    ctx->time_base = {1, 44100};
    av_channel_layout_default(&ctx->ch_layout, 2);
    assert(avcodec_open2(ctx, nullptr, nullptr) == 0);
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    assert(frame && packet);
    frame->format = ctx->sample_fmt;
    frame->sample_rate = ctx->sample_rate;
    frame->nb_samples = ctx->frame_size;
    av_channel_layout_default(&frame->ch_layout, 2);
    assert(av_frame_get_buffer(frame, 0) == 0);
    std::vector<uint8_t> bytes;
    size_t count = 0;
    for (int i = 0; i < 20 && count < 2; ++i) {
        assert(av_frame_make_writable(frame) == 0);
        for (int ch = 0; ch < 2; ++ch) std::memset(frame->data[ch], 0, frame->nb_samples * 2);
        frame->pts = int64_t(i) * frame->nb_samples;
        assert(avcodec_send_frame(ctx, frame) == 0);
        while (avcodec_receive_packet(ctx, packet) == 0) {
            if (count < 2) bytes.insert(bytes.end(), packet->data, packet->data + packet->size);
            ++count;
            av_packet_unref(packet);
        }
    }
    assert(count >= 2);
    assert(avcodec_send_frame(ctx, nullptr) == 0);
    while (avcodec_receive_packet(ctx, packet) == 0) av_packet_unref(packet);
    av_packet_free(&packet); av_frame_free(&frame); avcodec_free_context(&ctx);
    return bytes;
}

struct Listener final : C2Component::Listener {
    std::mutex mutex;
    std::condition_variable changed;
    std::list<std::unique_ptr<C2Work>> done;
    uint32_t error = 0;
    void onWorkDone_nb(std::weak_ptr<C2Component>, std::list<std::unique_ptr<C2Work>> work) override {
        std::lock_guard guard(mutex);
        done.splice(done.end(), work);
        changed.notify_all();
    }
    void onTripped_nb(std::weak_ptr<C2Component>, std::vector<std::shared_ptr<C2SettingResult>>) override { assert(false); }
    void onError_nb(std::weak_ptr<C2Component>, uint32_t code) override {
        std::lock_guard guard(mutex);
        error = code; changed.notify_all();
    }
    std::list<std::unique_ptr<C2Work>> wait(size_t completed) {
        std::unique_lock lock(mutex);
        assert(changed.wait_for(lock, std::chrono::seconds(10), [&] {
            size_t count = 0;
            for (const auto &work : done) if (!(work->worklets.front()->output.flags & C2FrameData::FLAG_INCOMPLETE)) ++count;
            return error || count >= completed;
        }));
        assert(!error);
        return std::move(done);
    }
};

static std::unique_ptr<C2Work> input(const std::shared_ptr<C2BlockPool> &pool,
        const std::vector<uint8_t> &bytes, uint64_t index, bool eos) {
    auto work = std::make_unique<C2Work>();
    work->input.ordinal.frameIndex = index;
    work->input.ordinal.timestamp = 7654321;
    work->input.flags = eos ? C2FrameData::FLAG_END_OF_STREAM : C2FrameData::flags_t(0);
    work->worklets.emplace_back(new C2Worklet);
    std::shared_ptr<C2LinearBlock> block;
    assert(pool->fetchLinearBlock(bytes.size(), C2MemoryUsage(C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE), &block) == C2_OK);
    auto view = block->map().get();
    assert(view.error() == C2_OK);
    std::memcpy(view.data(), bytes.data(), bytes.size());
    work->input.buffers.push_back(C2Buffer::CreateLinearBuffer(block->share(0, bytes.size(), C2Fence())));
    return work;
}

struct Encoded {
    std::vector<std::vector<uint8_t>> csd, packets;
};
static Encoded encodeAudio(const char *name, AVSampleFormat format, int rate) {
    auto *encoder = avcodec_find_encoder_by_name(name);
    assert(encoder);
    auto *ctx = avcodec_alloc_context3(encoder);
    assert(ctx);
    ctx->sample_rate = rate;
    ctx->sample_fmt = format;
    ctx->bit_rate = 128000;
    ctx->time_base = {1, rate};
    av_channel_layout_default(&ctx->ch_layout, 2);
    assert(avcodec_open2(ctx, nullptr, nullptr) == 0);
    Encoded encoded;
    std::vector<uint8_t> extra(ctx->extradata, ctx->extradata + ctx->extradata_size);
    if (!std::strcmp(name, "libvorbis")) {
        assert(extra[0] == 2);
        size_t offset = 1, sizes[3] = {};
        for (int i = 0; i < 2; ++i) {
            uint8_t length;
            do { assert(offset < extra.size()); length = extra[offset++]; sizes[i] += length; } while (length == 255);
        }
        assert(offset + sizes[0] + sizes[1] < extra.size());
        encoded.csd.emplace_back(extra.begin() + offset, extra.begin() + offset + sizes[0]);
        encoded.csd.emplace_back(extra.begin() + offset + sizes[0] + sizes[1], extra.end());
    } else if (!std::strcmp(name, "flac")) {
        assert(extra.size() == 34);
        encoded.csd.push_back({'f','L','a','C',0x80,0,0,34});
        encoded.csd.back().insert(encoded.csd.back().end(), extra.begin(), extra.end());
    } else if (!extra.empty()) encoded.csd.push_back(extra);
    if (!std::strcmp(name, "libopus")) {
        assert(extra.size() >= 19);
        const uint64_t skip = (uint64_t(extra[10]) | (uint64_t(extra[11]) << 8)) * 1000000000 / 48000;
        for (uint64_t ns : {skip, uint64_t(80000000)}) {
            encoded.csd.emplace_back(8);
            std::memcpy(encoded.csd.back().data(), &ns, 8);
        }
    }
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    assert(frame && packet);
    frame->format = format; frame->sample_rate = rate;
    frame->nb_samples = ctx->frame_size;
    av_channel_layout_default(&frame->ch_layout, 2);
    assert(frame->nb_samples > 0 && av_frame_get_buffer(frame, 0) == 0);
    for (int i = 0; i <= 64; ++i) {
        if (i < 64) {
            assert(av_frame_make_writable(frame) == 0);
            assert(av_samples_set_silence(frame->data, 0, frame->nb_samples, 2, format) == 0);
            frame->pts = int64_t(i) * frame->nb_samples;
        }
        assert(avcodec_send_frame(ctx, i == 64 ? nullptr : frame) == 0);
        while (avcodec_receive_packet(ctx, packet) == 0) {
            if (packet->size) encoded.packets.emplace_back(packet->data, packet->data + packet->size);
            av_packet_unref(packet);
        }
    }
    av_packet_free(&packet); av_frame_free(&frame); avcodec_free_context(&ctx);
    assert(!encoded.packets.empty());
    return encoded;
}

static void audioFamily(const std::shared_ptr<C2ReflectorHelper> &reflector,
        const char *name, const char *encoder, AVSampleFormat format, int rate,
        bool unifiedOpus = false) {
    auto encoded = encodeAudio(encoder, format, rate);
    if (unifiedOpus) {
        OpusHeader header;
        assert(ParseOpusHeader(encoded.csd[0].data(), encoded.csd[0].size(), &header));
        uint64_t delay, preRoll;
        std::memcpy(&delay, encoded.csd[1].data(), 8);
        std::memcpy(&preRoll, encoded.csd[2].data(), 8);
        std::vector<uint8_t> unified(AOPUS_UNIFIED_CSD_MAXSIZE);
        int size = WriteOpusHeaders(header, rate, unified.data(), unified.size(), delay, preRoll);
        assert(size > 0);
        unified.resize(size);
        encoded.csd = {std::move(unified)};
    }
    std::shared_ptr<C2Component> component;
    assert(azk::codec2::createAudioComponent(name, 74, reflector, &component) == C2_OK);
    auto listener = std::make_shared<Listener>();
    assert(component->setListener_vb(listener, C2_MAY_BLOCK) == C2_OK);
    assert(component->start() == C2_OK);
    std::shared_ptr<C2BlockPool> pool;
    assert(GetCodec2BlockPool(C2BlockPool::BASIC_LINEAR, component, &pool) == C2_OK);
    size_t previousBytes = 0;
    for (int generation = 0; generation < 3; ++generation) {
        std::list<std::unique_ptr<C2Work>> queued;
        uint64_t index = 0;
        if (generation != 2) for (const auto &csd : encoded.csd) {
            auto work = input(pool, csd, index++, false);
            work->input.flags = C2FrameData::FLAG_CODEC_CONFIG;
            queued.push_back(std::move(work));
        }
        for (size_t i = 0; i < encoded.packets.size(); ++i) {
            queued.push_back(input(pool, encoded.packets[i], index++, i + 1 == encoded.packets.size()));
        }
        assert(component->queue_nb(&queued) == C2_OK && queued.empty());
        auto done = listener->wait(index);
        std::map<uint64_t, size_t> completions;
        size_t outputBytes = 0, eos = 0;
        for (const auto &work : done) {
            if (work->result != C2_OK) std::cerr << name << " work " << work->input.ordinal.frameIndex.peeku() << " failed: " << work->result << std::endl;
            assert(work->result == C2_OK && work->workletsProcessed == 1);
            auto &output = work->worklets.front()->output;
            if (!(output.flags & C2FrameData::FLAG_INCOMPLETE)) ++completions[output.ordinal.frameIndex.peeku()];
            if (output.flags & C2FrameData::FLAG_END_OF_STREAM) ++eos;
            for (const auto &buffer : output.buffers) {
                auto view = buffer->data().linearBlocks().front().map().get();
                assert(view.error() == C2_OK && view.capacity() > 0);
                outputBytes += view.capacity();
            }
        }
        assert(outputBytes > 0 && eos == 1 && completions.size() == index);
        if (generation == 2 && !std::strcmp(encoder, "libopus")) {
            // Initial delay is 312 samples; a seek discards 3840 instead.
            assert(previousBytes - outputBytes == (3840 - 312) * 2 * sizeof(int16_t));
        }
        previousBytes = outputBytes;
        for (const auto &[id, count] : completions) { assert(id < index && count == 1); }
        C2StreamSampleRateInfo::output actual(0u);
        std::vector<std::unique_ptr<C2Param>> unused;
        assert(component->intf()->query_vb({&actual}, {}, C2_MAY_BLOCK, &unused) == C2_OK);
        assert(actual.value == uint32_t(rate));
        if (generation == 1) {
            std::list<std::unique_ptr<C2Work>> flushed;
            assert(component->flush_sm(C2Component::FLUSH_COMPONENT, &flushed) == C2_OK);
            assert(flushed.empty());
        } else {
            assert(component->stop() == C2_OK);
            if (!generation) assert(component->start() == C2_OK);
        }
    }
    assert(component->release() == C2_OK);
    std::cout << "PASS " << name << (unifiedOpus ? " unified" : "") << " Android CSD, output, every input completion, stop/start and flush" << std::endl;
}

int main() {
    const std::string mask = std::to_string(1u << C2PlatformAllocatorStore::BLOB);
    assert(property_set("debug.stagefright.c2-poolmask", mask.c_str()) == 0);
    const auto reflector = std::make_shared<C2ReflectorHelper>();
    std::shared_ptr<C2Component> component;
    assert(azk::codec2::createAudioComponent("missing", 1, reflector, &component) == C2_NOT_FOUND && !component);
    assert(azk::codec2::createAudioComponent("c2.artzig.mp3.decoder", 73, reflector, &component) == C2_OK);
    assert(component->intf()->getName() == "c2.artzig.mp3.decoder" && component->intf()->getId() == 73);
    C2StreamSampleRateInfo::output rate(0u);
    C2StreamChannelCountInfo::output channels(0u);
    std::vector<std::unique_ptr<C2Param>> queried;
    assert(component->intf()->query_vb({&rate, &channels}, {}, C2_MAY_BLOCK, &queried) == C2_OK);
    assert(rate.value == 44100 && channels.value == 2);
    auto listener = std::make_shared<Listener>();
    assert(component->setListener_vb(listener, C2_MAY_BLOCK) == C2_OK);
    assert(component->start() == C2_OK);
    assert(component->start() == C2_BAD_STATE);
    std::shared_ptr<C2BlockPool> pool;
    assert(GetCodec2BlockPool(C2BlockPool::BASIC_LINEAR, component, &pool) == C2_OK);
    const auto bytes = twoMp3Frames();
    for (int generation = 0; generation < 2; ++generation) {
        std::list<std::unique_ptr<C2Work>> queued;
        queued.push_back(input(pool, bytes, 0, false));
        queued.push_back(input(pool, bytes, UINT64_MAX, true));
        assert(component->queue_nb(&queued) == C2_OK && queued.empty());
        auto done = listener->wait(2);
        std::map<uint64_t, size_t> outputs, completions;
        size_t eos = 0;
        for (const auto &work : done) {
            assert(work->result == C2_OK && work->workletsProcessed == 1);
            const auto &output = work->worklets.front()->output;
            assert(output.ordinal.frameIndex == work->input.ordinal.frameIndex);
            const uint64_t id = output.ordinal.frameIndex.peeku();
            if (output.flags & C2FrameData::FLAG_INCOMPLETE) {
                assert(!(output.flags & C2FrameData::FLAG_END_OF_STREAM));
                assert(output.buffers.size() == 1);
                auto view = output.buffers.front()->data().linearBlocks().front().map().get();
                assert(view.error() == C2_OK && view.capacity() == 1152 * 2 * 2);
                if (outputs[id] == 0) assert(output.ordinal.timestamp == 7654321);
                ++outputs[id];
            } else {
                assert(output.buffers.empty());
                ++completions[id];
                if (output.flags & C2FrameData::FLAG_END_OF_STREAM) { assert(id == UINT64_MAX); ++eos; }
            }
        }
        assert(outputs[0] == 2 && outputs[UINT64_MAX] == 2);
        assert(completions[0] == 1 && completions[UINT64_MAX] == 1 && eos == 1);
        assert(component->stop() == C2_OK);
        if (generation == 0) assert(component->start() == C2_OK);
    }
    assert(component->reset() == C2_OK);
    assert(component->release() == C2_OK);
    component.reset();
    audioFamily(reflector, "c2.artzig.aac.decoder", "aac", AV_SAMPLE_FMT_FLTP, 44100);
    audioFamily(reflector, "c2.artzig.flac.decoder", "flac", AV_SAMPLE_FMT_S16, 44100);
    audioFamily(reflector, "c2.artzig.opus.decoder", "libopus", AV_SAMPLE_FMT_FLT, 48000);
    audioFamily(reflector, "c2.artzig.opus.decoder", "libopus", AV_SAMPLE_FMT_FLT, 48000, true);
    audioFamily(reflector, "c2.artzig.vorbis.decoder", "libvorbis", AV_SAMPLE_FMT_FLTP, 44100);
    std::cout << "PASS original Codec2 interface, queue, multiple outputs, duplicate PTS, EOS and stop/start/reset over FFmpeg\n";
}
