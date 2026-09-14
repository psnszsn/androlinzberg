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

const Frame = struct {
    pcm: []u8, // interleaved s16
    sample_rate: c_int,
    channels: c_int,
    pts: i64,
    work: ?*c.AVBufferRef = null,
};

const VFrame = struct {
    hardware: ?*c.AzkHardwareFrame = null, // owned legacy VPP output
    decoded: ?*c.AVFrame = null, // retained decoder surface for allocator output
    width: c_int, // decoded/coded dimensions reported to MediaCodec
    height: c_int,
    dataspace: c_int,
    pts: i64,
    work: ?*c.AVBufferRef = null,

    fn release(self: VFrame) void {
        if (self.hardware) |hardware| c.azk_hardware_frame_release(hardware);
        var decoded = self.decoded;
        c.av_frame_free(&decoded);
        releaseWork(self.work);
    }
};

pub const Dec = struct {
    ctx: *c.AVCodecContext,
    pkt: *c.AVPacket,
    frame: *c.AVFrame,
    is_video: bool = false,
    allocator_output: bool = false,
    queue: std.ArrayList(Frame) = .empty,
    vqueue: std.ArrayList(VFrame) = .empty,
    surface_w: c_int = 0,
    surface_h: c_int = 0,
    hw_device: ?*c.AVBufferRef = null,
    vaapi_vpp: ?*c.AzkVaapiVpp = null,
    eof_sent: bool = false,
    eof_out: bool = false,
    // Codec2 frameIndex is independent of presentation time. The decoder's
    // opaque references retain each work through reordering and delayed output.
    work_refs: std.ArrayList(*c.AVBufferRef) = .empty,

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
                const work = try retainWork(self.frame);
                errdefer releaseWork(work);
                const pts = if (self.frame.pts != c.AV_NOPTS_VALUE) self.frame.pts else 0;
                if (self.allocator_output) {
                    var width: c_int = undefined;
                    var height: c_int = undefined;
                    var dataspace: c_int = undefined;
                    if (c.azk_vaapi_frame_info(self.frame, &width, &height, &dataspace) != 0)
                        return error.HardwareVideoPresentation;
                    var decoded = c.av_frame_clone(self.frame) orelse return error.OutOfMemory;
                    errdefer c.av_frame_free(&decoded);
                    try self.vqueue.append(gpa, .{
                        .decoded = decoded,
                        .width = width,
                        .height = height,
                        .dataspace = dataspace,
                        .pts = pts,
                        .work = work,
                    });
                    continue;
                }
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
                    .dataspace = hardware.*.dataspace,
                    .pts = pts,
                    .work = work,
                });
            } else {
                const converted = try convertFrame(self.frame);
                errdefer gpa.free(converted.pcm);
                errdefer releaseWork(converted.work);
                try self.queue.append(gpa, converted);
            }
        }
    }
};

fn retainWork(frame: *c.AVFrame) !?*c.AVBufferRef {
    if (frame.opaque_ref == null) return null;
    return c.av_buffer_ref(frame.opaque_ref) orelse error.OutOfMemory;
}

fn releaseWork(work: ?*c.AVBufferRef) void {
    var ref = work;
    c.av_buffer_unref(&ref);
}

fn workIndex(ref: *c.AVBufferRef) u64 {
    return std.mem.readInt(u64, ref.data[0..8], .little);
}

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
        .work = try retainWork(f),
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

pub export fn azk_codec_video_profiles(codec_name: [*:0]const u8) u32 {
    const codec = c.avcodec_find_decoder_by_name(codec_name) orelse return 0;
    return c.azk_vaapi_decoder_profiles(codec.*.id);
}

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
    d.ctx.flags |= c.AV_CODEC_FLAG_COPY_OPAQUE;
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

/// Select allocator-owned video output before configuring the decoder.
pub export fn azk_codec_use_video_allocator(d: *Dec) c_int {
    if (!d.is_video or c.avcodec_is_open(d.ctx) != 0) return -1;
    d.allocator_output = true;
    return 0;
}

/// Send one encoded packet (or EOF). Always accepts: pending frames are
/// drained into the queue first. Returns 0 on success, <0 on error.
pub export fn azk_codec_send(d: *Dec, data: ?[*]const u8, len: c_int, pts_us: i64, eof: c_int) c_int {
    return sendPacket(d, data, len, pts_us, eof != 0, null);
}

/// Submit encoded data with its Codec2 frameIndex, preserving the original PTS.
/// EOS is submitted separately through azk_codec_send, after the final packet.
pub export fn azk_codec_send_work(d: *Dec, data: ?[*]const u8, len: c_int, pts_us: i64, work_id: u64) c_int {
    return sendPacket(d, data, len, pts_us, false, work_id);
}

fn sendPacket(d: *Dec, data: ?[*]const u8, len: c_int, pts_us: i64, eof: bool, work_id: ?u64) c_int {
    if (len < 0 or (len != 0 and data == null)) return avError(c.EINVAL);
    d.pushDecoded() catch return -1;
    if (eof) {
        if (!d.eof_sent) {
            const result = c.avcodec_send_packet(d.ctx, null);
            if (result < 0 and result != c.AVERROR_EOF) return result;
            d.eof_sent = true;
        }
        return 0;
    }
    if (work_id) |id| for (d.work_refs.items) |ref| {
        if (workIndex(ref) == id) return avError(c.EINVAL);
    };
    c.av_packet_unref(d.pkt);
    defer c.av_packet_unref(d.pkt);
    if (c.av_new_packet(d.pkt, len) < 0) return -1;
    if (len != 0) @memcpy(d.pkt.data[0..@intCast(len)], data.?[0..@intCast(len)]);
    d.pkt.pts = pts_us;
    var accepted = false;
    if (work_id) |id| {
        const ref: *c.AVBufferRef = c.av_buffer_alloc(8) orelse return -1;
        d.work_refs.append(gpa, ref) catch {
            releaseWork(ref);
            return -1;
        };
        std.mem.writeInt(u64, ref.data[0..8], id, .little);
        d.pkt.opaque_ref = c.av_buffer_ref(ref);
        if (d.pkt.opaque_ref == null) {
            releaseWork(d.work_refs.pop().?);
            return -1;
        }
    }
    defer if (work_id != null and !accepted) releaseWork(d.work_refs.pop().?);
    var r = c.avcodec_send_packet(d.ctx, d.pkt);
    if (r == avError(c.EAGAIN)) {
        d.pushDecoded() catch return -1;
        r = c.avcodec_send_packet(d.ctx, d.pkt);
    }
    if (r < 0 and r != c.AVERROR_EOF) return r;
    accepted = true;
    d.pushDecoded() catch return -1;
    return 0;
}

/// Receive one decoded audio frame as interleaved s16.
/// dst/dst_cap: destination buffer. out_meta: {bytes, sample_rate, channels}.
/// Returns 0 = frame, 1 = try again, 2 = end of stream, <0 = error.
pub export fn azk_codec_receive_audio(d: *Dec, dst: [*]u8, dst_cap: c_int, out_meta: [*]c_int, out_pts: *i64) c_int {
    return receiveAudio(d, dst, dst_cap, out_meta, out_pts, null);
}

pub export fn azk_codec_receive_audio_work(d: *Dec, dst: [*]u8, dst_cap: c_int, out_meta: [*]c_int, out_pts: *i64, out_work: *u64) c_int {
    return receiveAudio(d, dst, dst_cap, out_meta, out_pts, out_work);
}

fn receiveAudio(d: *Dec, dst: [*]u8, dst_cap: c_int, out_meta: [*]c_int, out_pts: *i64, out_work: ?*u64) c_int {
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
    if (out_work) |id| id.* = workIndex(pending.work orelse return -4);
    if (pending.pcm.len > dst_cap) {
        out_meta[0] = @intCast(pending.pcm.len);
        out_meta[1] = pending.sample_rate;
        out_meta[2] = pending.channels;
        out_pts.* = pending.pts;
        return -2;
    }
    const f = d.queue.orderedRemove(0);
    defer gpa.free(f.pcm);
    defer releaseWork(f.work);
    @memcpy(dst[0..f.pcm.len], f.pcm);
    out_meta[0] = @intCast(f.pcm.len);
    out_meta[1] = f.sample_rate;
    out_meta[2] = f.channels;
    out_pts.* = f.pts;
    return 0;
}

/// Hardware frames return 3 and transfer an owned AzkHardwareFrame.
pub export fn azk_codec_receive_video2(d: *Dec, out_meta: [*]c_int, out_pts: *i64, out_handle: *usize) c_int {
    return receiveVideo(d, out_meta, out_pts, out_handle, null);
}

pub export fn azk_codec_receive_video_work(d: *Dec, out_meta: [*]c_int, out_pts: *i64, out_handle: *usize, out_work: *u64) c_int {
    return receiveVideo(d, out_meta, out_pts, out_handle, out_work);
}

fn receiveVideo(d: *Dec, out_meta: [*]c_int, out_pts: *i64, out_handle: *usize, out_work: ?*u64) c_int {
    out_handle.* = 0;
    if (d.allocator_output) return -1;
    if (d.vqueue.items.len == 0) d.pushDecoded() catch return -1;
    if (d.vqueue.items.len == 0) {
        if (d.eof_out) return 2;
        return 1;
    }
    const pending = d.vqueue.items[0];
    if (out_work) |id| id.* = workIndex(pending.work orelse return -4);
    out_meta[1] = pending.width;
    out_meta[2] = pending.height;
    out_pts.* = pending.pts;
    const frame = d.vqueue.orderedRemove(0);
    defer releaseWork(frame.work);
    out_meta[0] = 0;
    out_meta[3] = frame.dataspace;
    out_handle.* = @intFromPtr(frame.hardware.?);
    return 3;
}

/// Peek without consuming a decoded surface or its work reference. The caller
/// allocates the matching C2 block and waits its acquire fence before render.
pub export fn azk_codec_peek_video_work(d: *Dec, out_meta: [*]c_int, out_pts: *i64, out_work: *u64) c_int {
    if (!d.allocator_output) return -1;
    if (d.vqueue.items.len == 0) d.pushDecoded() catch return -1;
    if (d.vqueue.items.len == 0) return if (d.eof_out) 2 else 1;
    const frame = d.vqueue.items[0];
    out_work.* = workIndex(frame.work orelse return -4);
    out_meta[0] = 0;
    out_meta[1] = frame.width;
    out_meta[2] = frame.height;
    out_meta[3] = frame.dataspace;
    out_pts.* = frame.pts;
    return 0;
}

/// The only GPU operation is the existing YUV-to-RGB conversion, now directed
/// into caller storage. Failure leaves the decoded frame queued for retry.
pub export fn azk_codec_render_video_work(d: *Dec, target: *const c.AzkGraphicsBufferInfo) c_int {
    if (!d.allocator_output or d.vqueue.items.len == 0) return -1;
    const frame = d.vqueue.items[0];
    const result = c.azk_vaapi_render_into(frame.decoded, target, &d.vaapi_vpp);
    if (result != 0) return result;
    d.vqueue.orderedRemove(0).release();
    return 0;
}

/// Returns 1 with a completed work index, or 0 if none is ready. Completion
/// follows consumption of every queued output and release by the decoder.
/// At decoder EOF only queued outputs can still belong to the work. This
/// permits no-output and multi-output packets without guessing from timestamps.
pub export fn azk_codec_poll_completed_work(d: *Dec, out_work: *u64) c_int {
    for (d.work_refs.items, 0..) |ref, i| {
        if (c.av_buffer_get_ref_count(ref) != 1) {
            if (!d.eof_out) continue;
            var queued = false;
            for (d.queue.items) |frame| {
                if (frame.work) |work| if (work.buffer == ref.buffer) {
                    queued = true;
                    break;
                };
            }
            for (d.vqueue.items) |frame| {
                if (frame.work) |work| if (work.buffer == ref.buffer) {
                    queued = true;
                    break;
                };
            }
            if (queued) continue;
        }
        out_work.* = workIndex(ref);
        releaseWork(d.work_refs.orderedRemove(i));
        return 1;
    }
    return 0;
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
    c.av_packet_unref(d.pkt);
    c.av_frame_unref(d.frame);
    for (d.queue.items) |f| {
        gpa.free(f.pcm);
        releaseWork(f.work);
    }
    d.queue.clearRetainingCapacity();
    for (d.vqueue.items) |f| f.release();
    d.vqueue.clearRetainingCapacity();
    for (d.work_refs.items) |ref| releaseWork(ref);
    d.work_refs.clearRetainingCapacity();
    d.eof_sent = false;
    d.eof_out = false;
}

pub export fn azk_codec_close(d: *Dec) void {
    for (d.queue.items) |f| {
        gpa.free(f.pcm);
        releaseWork(f.work);
    }
    d.queue.deinit(gpa);
    for (d.vqueue.items) |f| f.release();
    d.vqueue.deinit(gpa);
    for (d.work_refs.items) |ref| releaseWork(ref);
    d.work_refs.deinit(gpa);
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
pub fn writeTestMP3(io: std.Io, path: []const u8) c_int {
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
    const file = std.Io.Dir.cwd().createFile(io, path, .{}) catch return -4;
    defer file.close(io);
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
            file.writeStreamingAll(io, epkt.*.data[0..@intCast(epkt.*.size)]) catch return -7;
            total += epkt.*.size;
            c.av_packet_unref(epkt);
        }
    }
    return total;
}

/// Write a short mpeg4 elementary stream as [u32le size][frame]... for
/// Java-level MediaCodec video tests.
pub fn writeTestM4V(io: std.Io, path: []const u8) c_int {
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
    const file = std.Io.Dir.cwd().createFile(io, path, .{}) catch return -4;
    defer file.close(io);
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
            file.writeStreamingAll(io, &size_le) catch return -7;
            file.writeStreamingAll(io, epkt.*.data[0..@intCast(epkt.*.size)]) catch return -7;
            frames += 1;
            c.av_packet_unref(epkt);
        }
    }
    _ = c.avcodec_send_frame(ectx, null);
    while (c.avcodec_receive_packet(ectx, epkt) == 0) {
        var size_le: [4]u8 = undefined;
        std.mem.writeInt(u32, &size_le, @intCast(epkt.*.size), .little);
        file.writeStreamingAll(io, &size_le) catch return -7;
        file.writeStreamingAll(io, epkt.*.data[0..@intCast(epkt.*.size)]) catch return -7;
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
