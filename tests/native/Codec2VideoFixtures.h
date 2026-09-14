#pragma once
#include <cassert>
#include <cstring>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
}

// Cropped H.264 with B frames exercises delayed output, visible dimensions and
// input identity across decoder reordering. All frames are limited-range red.
static std::vector<std::vector<uint8_t>> videoPackets(const char *encoder = "libx264") {
    const AVCodec *codec = avcodec_find_encoder_by_name(encoder);
    assert(codec);
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    assert(ctx);
    ctx->width = 318; ctx->height = 238;
    ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    ctx->time_base = {1, 30};
    ctx->gop_size = 12;
    ctx->max_b_frames = codec->id == AV_CODEC_ID_H264 ? 2 : 0;
    ctx->color_range = AVCOL_RANGE_MPEG;
    ctx->colorspace = AVCOL_SPC_SMPTE170M;
    ctx->color_primaries = AVCOL_PRI_SMPTE170M;
    ctx->color_trc = AVCOL_TRC_SMPTE170M;
    if (codec->id == AV_CODEC_ID_H264)
        assert(av_opt_set(ctx->priv_data, "preset", "ultrafast", 0) == 0);
    if (codec->id == AV_CODEC_ID_VP9) {
        assert(av_opt_set(ctx->priv_data, "deadline", "realtime", 0) == 0);
        assert(av_opt_set_int(ctx->priv_data, "cpu-used", 8, 0) == 0);
    }
    assert(avcodec_open2(ctx, nullptr, nullptr) == 0);
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    assert(frame && packet);
    frame->width = ctx->width; frame->height = ctx->height;
    frame->format = ctx->pix_fmt;
    assert(av_frame_get_buffer(frame, 0) == 0);
    std::vector<std::vector<uint8_t>> packets;
    auto receive = [&] {
        int status;
        while ((status = avcodec_receive_packet(ctx, packet)) == 0) {
            packets.emplace_back(packet->data, packet->data + packet->size);
            av_packet_unref(packet);
        }
        assert(status == AVERROR(EAGAIN) || status == AVERROR_EOF);
    };
    for (int i = 0; i < 8; ++i) {
        assert(av_frame_make_writable(frame) == 0);
        for (int plane = 0; plane < 3; ++plane) {
            const int width = ctx->width >> (plane != 0);
            const int height = ctx->height >> (plane != 0);
            const int value[] = {81, 90, 240};
            for (int row = 0; row < height; ++row)
                std::memset(frame->data[plane] + row * frame->linesize[plane], value[plane], width);
        }
        frame->pts = i;
        assert(avcodec_send_frame(ctx, frame) == 0);
        receive();
    }
    assert(avcodec_send_frame(ctx, nullptr) == 0);
    receive();
    av_packet_free(&packet); av_frame_free(&frame); avcodec_free_context(&ctx);
    assert(packets.size() == 8);
    return packets;
}

static void assertRed(uint32_t pixel) {
    assert((pixel & 255) >= 245 && ((pixel >> 8) & 255) < 10 && ((pixel >> 16) & 255) < 10);
}
