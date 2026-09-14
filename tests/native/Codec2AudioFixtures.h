#pragma once
#include <C2Buffer.h>
#include <C2Component.h>
#include <cassert>
#include <cstring>
#include <memory>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
}

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

