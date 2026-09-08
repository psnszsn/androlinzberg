//! azk_codec: MediaCodec backend over the Nix-provided system FFmpeg.
//! Called directly by the Zig JNI adapter and also exported as a C ABI for
//! native tests and the video-layer transport.
//!
//! Audio decode path (phase 2 item 3a): send() always accepts a packet by
//! draining pending frames into an internal queue first; receive() converts
//! any sample format to interleaved s16.
const std = @import("std");
const c = @import("av");

const gpa = std.heap.c_allocator;

extern "c" fn fopen(path: [*:0]const u8, mode: [*:0]const u8) ?*anyopaque;
extern "c" fn fwrite(ptr: *const anyopaque, size: usize, n: usize, f: *anyopaque) usize;
extern "c" fn fclose(f: *anyopaque) c_int;
const c_fopen = fopen;
const c_fwrite = fwrite;
const c_fclose = fclose;

const Frame = struct {
    pcm: []u8, // interleaved s16
    sample_rate: c_int,
    channels: c_int,
    pts: i64,
};

const VFrame = struct {
    hardware: *c.AzkHardwareFrame, // owned VA-API/VPP DMA-BUF frame
    width: c_int, // decoded/coded dimensions reported to MediaCodec
    height: c_int,
    pts: i64,
};

pub const Dec = struct {
    ctx: *c.AVCodecContext,
    pkt: *c.AVPacket,
    frame: *c.AVFrame,
    is_video: bool = false,
    queue: std.ArrayList(Frame) = .empty,
    vqueue: std.ArrayList(VFrame) = .empty,
    surface_w: c_int = 0,
    surface_h: c_int = 0,
    hw_device: ?*c.AVBufferRef = null,
    vaapi_vpp: ?*c.AzkVaapiVpp = null,
    eof_sent: bool = false,
    eof_out: bool = false,

    fn pushDecoded(self: *Dec) !void {
        // drain all currently-available frames into the queue
        while (true) {
            const r = c.avcodec_receive_frame(self.ctx, self.frame);
            if (r == avError(c.EAGAIN)) return;
            if (r == c.AVERROR_EOF) {
                self.eof_out = true;
                return;
            }
            if (r < 0) return error.Decode;
            defer c.av_frame_unref(self.frame);
            if (self.is_video) {
                if (self.frame.format != c.AV_PIX_FMT_VAAPI)
                    return error.HardwareVideoRequired;
                const output = videoPresentationSize(
                    self.frame.width,
                    self.frame.height,
                    self.surface_w,
                    self.surface_h,
                );
                const hardware = c.azk_vaapi_export(
                    self.frame,
                    output.width,
                    output.height,
                    &self.vaapi_vpp,
                ) orelse return error.HardwareVideoPresentation;
                errdefer c.azk_hardware_frame_release(hardware);
                try self.vqueue.append(gpa, .{
                    .hardware = hardware,
                    .width = hardware.*.width,
                    .height = hardware.*.height,
                    .pts = if (self.frame.pts != c.AV_NOPTS_VALUE) self.frame.pts else 0,
                });
            } else {
                try self.queue.append(gpa, try convertFrame(self.frame));
            }
        }
    }
};

const VideoSize = struct { width: c_int, height: c_int };

/// Keep codec buffers at the decoded stream size across SurfaceView resizes.
/// AOSP SurfaceView.performSurfaceTransaction() applies the destination frame
/// and position scale in the composition transaction; it does not resize
/// MediaCodec output buffers to the view. Wayland's viewport is that host seam.
fn videoPresentationSize(width: c_int, height: c_int, _: c_int, _: c_int) VideoSize {
    return .{ .width = width, .height = height };
}

fn avError(e: c_int) c_int {
    return -e; // AVERROR(e)
}

fn convertFrame(f: *c.AVFrame) !Frame {
    const channels: usize = @intCast(f.ch_layout.nb_channels);
    const samples: usize = @intCast(f.nb_samples);
    const out = try gpa.alloc(u8, samples * channels * 2);
    errdefer gpa.free(out);
    const dst = @as([*]i16, @ptrCast(@alignCast(out.ptr)))[0 .. samples * channels];
    switch (f.format) {
        c.AV_SAMPLE_FMT_S16 => {
            const src = @as([*]const i16, @ptrCast(@alignCast(f.data[0])))[0 .. samples * channels];
            @memcpy(dst, src);
        },
        c.AV_SAMPLE_FMT_S16P => {
            for (0..channels) |ch| {
                const src = @as([*]const i16, @ptrCast(@alignCast(f.data[ch])))[0..samples];
                for (0..samples) |i| dst[i * channels + ch] = src[i];
            }
        },
        c.AV_SAMPLE_FMT_FLT => {
            const src = @as([*]const f32, @ptrCast(@alignCast(f.data[0])))[0 .. samples * channels];
            for (0..samples * channels) |i| dst[i] = f2s16(src[i]);
        },
        c.AV_SAMPLE_FMT_FLTP => {
            for (0..channels) |ch| {
                const src = @as([*]const f32, @ptrCast(@alignCast(f.data[ch])))[0..samples];
                for (0..samples) |i| dst[i * channels + ch] = f2s16(src[i]);
            }
        },
        c.AV_SAMPLE_FMT_S32 => {
            const src = @as([*]const i32, @ptrCast(@alignCast(f.data[0])))[0 .. samples * channels];
            for (0..samples * channels) |i| dst[i] = @intCast(src[i] >> 16);
        },
        c.AV_SAMPLE_FMT_S32P => {
            for (0..channels) |ch| {
                const src = @as([*]const i32, @ptrCast(@alignCast(f.data[ch])))[0..samples];
                for (0..samples) |i| dst[i * channels + ch] = @intCast(src[i] >> 16);
            }
        },
        else => return error.UnsupportedSampleFormat,
    }
    return .{
        .pcm = out,
        .sample_rate = f.sample_rate,
        .channels = @intCast(channels),
        .pts = if (f.pts != c.AV_NOPTS_VALUE) f.pts else 0,
    };
}

fn f2s16(v: f32) i16 {
    const scaled = v * 32767.0;
    if (std.math.isNan(scaled)) return 0;
    if (scaled >= 32767.0) return 32767;
    if (scaled <= -32768.0) return -32768;
    return @intFromFloat(scaled);
}

// ---------------------------------------------------------------- C ABI

/// codec_name: an ffmpeg decoder name ("aac", "opus", "mp3", "flac", "vorbis").
pub export fn azk_codec_open(codec_name: [*:0]const u8) ?*Dec {
    const codec = c.avcodec_find_decoder_by_name(codec_name) orelse return null;
    const ctx = c.avcodec_alloc_context3(codec) orelse return null;
    const d = gpa.create(Dec) catch return null;
    d.* = .{
        .ctx = ctx,
        .pkt = c.av_packet_alloc(),
        .frame = c.av_frame_alloc(),
        .is_video = codec.*.type == c.AVMEDIA_TYPE_VIDEO,
    };
    return d;
}

/// Set codec-specific data (csd-0: AudioSpecificConfig, OpusHead, ...) before
/// the first send. Then opens the codec. Pass len==0 for codecs without csd.
pub export fn azk_codec_configure(d: *Dec, extradata: ?[*]const u8, len: c_int, sample_rate: c_int, channels: c_int) c_int {
    if (len > 0) {
        const ed = c.av_mallocz(@intCast(len + c.AV_INPUT_BUFFER_PADDING_SIZE)) orelse return -1;
        @memcpy(@as([*]u8, @ptrCast(ed))[0..@intCast(len)], extradata.?[0..@intCast(len)]);
        d.ctx.extradata = @ptrCast(ed);
        d.ctx.extradata_size = len;
    }
    if (sample_rate > 0) d.ctx.sample_rate = sample_rate;
    if (channels > 0) {
        c.av_channel_layout_uninit(&d.ctx.ch_layout);
        c.av_channel_layout_default(&d.ctx.ch_layout, channels);
    }
    d.ctx.pkt_timebase = .{ .num = 1, .den = 1000000 }; // MediaCodec uses µs
    // FFmpeg's hardware decoder performs the GPU work asynchronously.
    d.ctx.thread_count = 1;
    d.ctx.thread_type = 0;
    if (d.is_video) {
        if (c.azk_vaapi_prepare(d.ctx, &d.hw_device) == 0) return -3;
    }
    return c.avcodec_open2(d.ctx, null, null);
}

pub export fn azk_codec_set_video_target(d: *Dec, width: c_int, height: c_int) void {
    // Retain the current SurfaceView dimensions at the JNI seam, but do not
    // use them to rebuild the decoder/VPP pipeline.
    d.surface_w = @max(0, width);
    d.surface_h = @max(0, height);
}

/// Send one encoded packet (or EOF). Always accepts: pending frames are
/// drained into the queue first. Returns 0 on success, <0 on error.
pub export fn azk_codec_send(d: *Dec, data: ?[*]const u8, len: c_int, pts_us: i64, eof: c_int) c_int {
    d.pushDecoded() catch return -1;
    if (eof != 0) {
        if (!d.eof_sent) {
            _ = c.avcodec_send_packet(d.ctx, null);
            d.eof_sent = true;
        }
        return 0;
    }
    c.av_packet_unref(d.pkt);
    if (c.av_new_packet(d.pkt, len) < 0) return -1;
    @memcpy(d.pkt.data[0..@intCast(len)], data.?[0..@intCast(len)]);
    d.pkt.pts = pts_us;
    var r = c.avcodec_send_packet(d.ctx, d.pkt);
    if (r == avError(c.EAGAIN)) {
        d.pushDecoded() catch return -1;
        r = c.avcodec_send_packet(d.ctx, d.pkt);
    }
    if (r < 0 and r != c.AVERROR_EOF) return r;
    d.pushDecoded() catch return -1;
    return 0;
}

/// Receive one decoded audio frame as interleaved s16.
/// dst/dst_cap: destination buffer. out_meta: {bytes, sample_rate, channels}.
/// Returns 0 = frame, 1 = try again, 2 = end of stream, <0 = error.
pub export fn azk_codec_receive_audio(d: *Dec, dst: [*]u8, dst_cap: c_int, out_meta: [*]c_int, out_pts: *i64) c_int {
    if (d.queue.items.len == 0) d.pushDecoded() catch return -1;
    if (d.queue.items.len == 0) {
        if (d.eof_out or (d.eof_sent and blk: {
            d.pushDecoded() catch return -1;
            break :blk d.eof_out and d.queue.items.len == 0;
        })) {
            if (d.queue.items.len == 0) return 2;
        } else return 1;
    }
    const pending = d.queue.items[0];
    if (pending.pcm.len > dst_cap) {
        out_meta[0] = @intCast(pending.pcm.len);
        out_meta[1] = pending.sample_rate;
        out_meta[2] = pending.channels;
        out_pts.* = pending.pts;
        return -2;
    }
    const f = d.queue.orderedRemove(0);
    defer gpa.free(f.pcm);
    @memcpy(dst[0..f.pcm.len], f.pcm);
    out_meta[0] = @intCast(f.pcm.len);
    out_meta[1] = f.sample_rate;
    out_meta[2] = f.channels;
    out_pts.* = f.pts;
    return 0;
}

/// Hardware frames return 3 and transfer an owned AzkHardwareFrame.
pub export fn azk_codec_receive_video2(d: *Dec, out_meta: [*]c_int, out_pts: *i64, out_handle: *usize) c_int {
    out_handle.* = 0;
    if (d.vqueue.items.len == 0) d.pushDecoded() catch return -1;
    if (d.vqueue.items.len == 0) {
        if (d.eof_out) return 2;
        return 1;
    }
    const pending = d.vqueue.items[0];
    out_meta[1] = pending.width;
    out_meta[2] = pending.height;
    out_pts.* = pending.pts;
    const frame = d.vqueue.orderedRemove(0);
    out_meta[0] = 0;
    out_meta[3] = frame.hardware.*.dataspace;
    out_handle.* = @intFromPtr(frame.hardware);
    return 3;
}

pub const HardwareFrame = c.AzkHardwareFrame;

pub fn releaseHardwareFrame(frame: *HardwareFrame) void {
    c.azk_hardware_frame_release(frame);
}

test "surface resize keeps codec presentation at decoded dimensions" {
    try std.testing.expectEqual(VideoSize{ .width = 2842, .height = 2160 }, videoPresentationSize(2842, 2160, 774, 589));
    try std.testing.expectEqual(VideoSize{ .width = 3840, .height = 2160 }, videoPresentationSize(3840, 2160, 1920, 1080));
    try std.testing.expectEqual(VideoSize{ .width = 426, .height = 240 }, videoPresentationSize(426, 240, 1920, 1080));
}

pub export fn azk_codec_flush(d: *Dec) void {
    c.avcodec_flush_buffers(d.ctx);
    for (d.queue.items) |f| gpa.free(f.pcm);
    d.queue.clearRetainingCapacity();
    for (d.vqueue.items) |f| c.azk_hardware_frame_release(f.hardware);
    d.vqueue.clearRetainingCapacity();
    d.eof_sent = false;
    d.eof_out = false;
}

pub export fn azk_codec_close(d: *Dec) void {
    for (d.queue.items) |f| gpa.free(f.pcm);
    d.queue.deinit(gpa);
    for (d.vqueue.items) |f| c.azk_hardware_frame_release(f.hardware);
    d.vqueue.deinit(gpa);
    c.azk_vaapi_vpp_free(&d.vaapi_vpp);
    var hw_device = d.hw_device;
    c.av_buffer_unref(&hw_device);
    var ctx: ?*c.AVCodecContext = d.ctx;
    c.avcodec_free_context(&ctx);
    var pkt: ?*c.AVPacket = d.pkt;
    c.av_packet_free(&pkt);
    var frame: ?*c.AVFrame = d.frame;
    c.av_frame_free(&frame);
    gpa.destroy(d);
}

/// Write a short mp3 file for Java-level MediaCodec tests.
pub export fn azk_codec_write_test_mp3(path: [*:0]const u8) c_int {
    const enc = c.avcodec_find_encoder_by_name("libmp3lame") orelse return -1;
    const ectx = c.avcodec_alloc_context3(enc) orelse return -2;
    defer {
        var e: ?*c.AVCodecContext = ectx;
        c.avcodec_free_context(&e);
    }
    ectx.*.sample_rate = 44100;
    c.av_channel_layout_default(&ectx.*.ch_layout, 2);
    ectx.*.sample_fmt = c.AV_SAMPLE_FMT_S16P;
    ectx.*.bit_rate = 128000;
    ectx.*.time_base = .{ .num = 1, .den = 44100 };
    if (c.avcodec_open2(ectx, null, null) < 0) return -3;
    const file = c_fopen(path, "wb") orelse return -4;
    defer _ = c_fclose(file);
    const ef = c.av_frame_alloc();
    defer {
        var f: ?*c.AVFrame = ef;
        c.av_frame_free(&f);
    }
    ef.*.format = c.AV_SAMPLE_FMT_S16P;
    ef.*.sample_rate = 44100;
    c.av_channel_layout_default(&ef.*.ch_layout, 2);
    ef.*.nb_samples = ectx.*.frame_size;
    if (c.av_frame_get_buffer(ef, 0) < 0) return -5;
    const epkt = c.av_packet_alloc();
    defer {
        var p: ?*c.AVPacket = epkt;
        c.av_packet_free(&p);
    }
    var total: c_int = 0;
    var t: usize = 0;
    for (0..80) |_| {
        const n: usize = @intCast(ef.*.nb_samples);
        for (0..2) |ch| {
            const pl = @as([*]i16, @ptrCast(@alignCast(ef.*.data[ch])))[0..n];
            for (0..n) |i| {
                const phase: f32 = @floatFromInt(t + i);
                pl[i] = @intFromFloat(@sin(phase * 2.0 * std.math.pi * 440.0 / 44100.0) * 12000.0);
            }
        }
        t += n;
        if (c.avcodec_send_frame(ectx, ef) < 0) return -6;
        while (c.avcodec_receive_packet(ectx, epkt) == 0) {
            if (c_fwrite(epkt.*.data, 1, @intCast(epkt.*.size), file) != @as(usize, @intCast(epkt.*.size))) return -7;
            total += epkt.*.size;
            c.av_packet_unref(epkt);
        }
    }
    return total;
}

/// Write a short mpeg4 elementary stream as [u32le size][frame]... for
/// Java-level MediaCodec video tests.
pub export fn azk_codec_write_test_m4v(path: [*:0]const u8) c_int {
    const enc = c.avcodec_find_encoder_by_name("mpeg4") orelse return -1;
    const ectx = c.avcodec_alloc_context3(enc) orelse return -2;
    defer {
        var e: ?*c.AVCodecContext = ectx;
        c.avcodec_free_context(&e);
    }
    ectx.*.width = 320;
    ectx.*.height = 240;
    ectx.*.pix_fmt = c.AV_PIX_FMT_YUV420P;
    ectx.*.time_base = .{ .num = 1, .den = 30 };
    ectx.*.bit_rate = 800000;
    ectx.*.gop_size = 10;
    if (c.avcodec_open2(ectx, null, null) < 0) return -3;
    const file = c_fopen(path, "wb") orelse return -4;
    defer _ = c_fclose(file);
    const ef = c.av_frame_alloc();
    defer {
        var f: ?*c.AVFrame = ef;
        c.av_frame_free(&f);
    }
    ef.*.format = c.AV_PIX_FMT_YUV420P;
    ef.*.width = 320;
    ef.*.height = 240;
    if (c.av_frame_get_buffer(ef, 0) < 0) return -5;
    const epkt = c.av_packet_alloc();
    defer {
        var p: ?*c.AVPacket = epkt;
        c.av_packet_free(&p);
    }
    var frames: c_int = 0;
    for (0..90) |i| {
        _ = c.av_frame_make_writable(ef);
        for (0..240) |y| {
            for (0..320) |x| {
                // moving diagonal color bars
                ef.*.data[0][y * @as(usize, @intCast(ef.*.linesize[0])) + x] =
                    @truncate(((x + y + i * 6) / 24) * 40);
            }
        }
        for (0..120) |y| {
            for (0..160) |x| {
                ef.*.data[1][y * @as(usize, @intCast(ef.*.linesize[1])) + x] =
                    @truncate(96 + ((x + i * 3) / 40) * 32);
                ef.*.data[2][y * @as(usize, @intCast(ef.*.linesize[2])) + x] =
                    @truncate(96 + ((y + i * 2) / 30) * 32);
            }
        }
        ef.*.pts = @intCast(i);
        if (c.avcodec_send_frame(ectx, ef) < 0) return -6;
        while (c.avcodec_receive_packet(ectx, epkt) == 0) {
            var size_le: [4]u8 = undefined;
            std.mem.writeInt(u32, &size_le, @intCast(epkt.*.size), .little);
            if (c_fwrite(&size_le, 1, 4, file) != 4) return -7;
            if (c_fwrite(epkt.*.data, 1, @intCast(epkt.*.size), file) != @as(usize, @intCast(epkt.*.size))) return -7;
            frames += 1;
            c.av_packet_unref(epkt);
        }
    }
    _ = c.avcodec_send_frame(ectx, null);
    while (c.avcodec_receive_packet(ectx, epkt) == 0) {
        var size_le: [4]u8 = undefined;
        std.mem.writeInt(u32, &size_le, @intCast(epkt.*.size), .little);
        if (c_fwrite(&size_le, 1, 4, file) != 4) return -7;
        if (c_fwrite(epkt.*.data, 1, @intCast(epkt.*.size), file) != @as(usize, @intCast(epkt.*.size))) return -7;
        frames += 1;
        c.av_packet_unref(epkt);
    }
    return frames;
}

/// Round-trip selftest: encode a sine with the mp3 encoder, decode it with the
/// same path MediaCodec uses. Returns decoded sample count (>0 = pass).
pub export fn azk_codec_selftest() c_int {
    const enc = c.avcodec_find_encoder_by_name("libmp3lame") orelse return -1;
    const ectx = c.avcodec_alloc_context3(enc) orelse return -2;
    defer {
        var e: ?*c.AVCodecContext = ectx;
        c.avcodec_free_context(&e);
    }
    ectx.*.sample_rate = 44100;
    c.av_channel_layout_default(&ectx.*.ch_layout, 2);
    ectx.*.sample_fmt = c.AV_SAMPLE_FMT_S16P;
    ectx.*.bit_rate = 128000;
    ectx.*.time_base = .{ .num = 1, .den = 44100 };
    if (c.avcodec_open2(ectx, null, null) < 0) return -3;

    const d = azk_codec_open("mp3") orelse return -4;
    defer azk_codec_close(d);
    if (azk_codec_configure(d, null, 0, 44100, 2) < 0) return -5;

    const ef = c.av_frame_alloc();
    defer {
        var f: ?*c.AVFrame = ef;
        c.av_frame_free(&f);
    }
    ef.*.format = c.AV_SAMPLE_FMT_S16P;
    ef.*.sample_rate = 44100;
    c.av_channel_layout_default(&ef.*.ch_layout, 2);
    ef.*.nb_samples = ectx.*.frame_size;
    if (c.av_frame_get_buffer(ef, 0) < 0) return -6;

    const epkt = c.av_packet_alloc();
    defer {
        var p: ?*c.AVPacket = epkt;
        c.av_packet_free(&p);
    }

    var decoded_samples: c_int = 0;
    var meta: [3]c_int = undefined;
    var pts: i64 = 0;
    const dst = gpa.alloc(u8, 1 << 20) catch return -7;
    defer gpa.free(dst);

    var t: usize = 0;
    var chunk: usize = 0;
    while (chunk < 30) : (chunk += 1) {
        const n: usize = @intCast(ef.*.nb_samples);
        for (0..2) |ch| {
            const p = @as([*]i16, @ptrCast(@alignCast(ef.*.data[ch])))[0..n];
            for (0..n) |i| {
                const phase: f32 = @floatFromInt(t + i);
                p[i] = @intFromFloat(@sin(phase * 2.0 * std.math.pi * 440.0 / 44100.0) * 12000.0);
            }
        }
        t += n;
        if (c.avcodec_send_frame(ectx, ef) < 0) return -8;
        while (c.avcodec_receive_packet(ectx, epkt) == 0) {
            if (azk_codec_send(d, epkt.*.data, epkt.*.size, 0, 0) < 0) return -9;
            c.av_packet_unref(epkt);
        }
        while (true) {
            const r = azk_codec_receive_audio(d, dst.ptr, @intCast(dst.len), &meta, &pts);
            if (r != 0) break;
            decoded_samples += @divTrunc(meta[0], 2 * meta[2]);
        }
    }
    return decoded_samples;
}
