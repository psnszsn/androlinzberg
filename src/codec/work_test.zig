//! Codec2 work identity must survive duplicate timestamps and queued output.
const std = @import("std");
const codec = @import("azk_codec");
const av = @import("av");
const expectEqual = std.testing.expectEqual;

test "work identity is independent of PTS and completes after output consumption" {
    const d = codec.azk_codec_open("pcm_s16le") orelse return error.NoDecoder;
    defer codec.azk_codec_close(d);
    try expectEqual(0, codec.azk_codec_configure(d, null, 0, 48000, 2));
    const pcm = [_]u8{ 1, 0, 2, 0, 3, 0, 4, 0 };
    const ids = [_]u64{ 0, std.math.maxInt(u64) };
    for (ids) |id| try expectEqual(0, codec.azk_codec_send_work(d, &pcm, pcm.len, 7654321, id));
    // A repeated active frame index cannot be confused with its earlier work.
    try std.testing.expect(codec.azk_codec_send_work(d, &pcm, pcm.len, 1234, 0) < 0);
    var id: u64 = undefined;
    try expectEqual(0, codec.azk_codec_poll_completed_work(d, &id));
    var bytes: [32]u8 = undefined;
    var meta: [3]c_int = undefined;
    var pts: i64 = undefined;
    try expectEqual(-2, codec.azk_codec_receive_audio_work(d, &bytes, 1, &meta, &pts, &id));
    try expectEqual(@as(c_int, pcm.len), meta[0]);
    try expectEqual(0, id);
    try expectEqual(0, codec.azk_codec_poll_completed_work(d, &id));
    for (ids) |expected| {
        try expectEqual(0, codec.azk_codec_receive_audio_work(d, &bytes, bytes.len, &meta, &pts, &id));
        try expectEqual(expected, id);
        try expectEqual(7654321, pts);
        try expectEqual(48000, meta[1]);
        try expectEqual(2, meta[2]);
        try std.testing.expectEqualSlices(u8, &pcm, bytes[0..@intCast(meta[0])]);
    }
    try expectEqual(0, codec.azk_codec_send(d, null, 0, 0, 1));
    try expectEqual(2, codec.azk_codec_receive_audio_work(d, &bytes, bytes.len, &meta, &pts, &id));
    for (ids) |expected| {
        try expectEqual(1, codec.azk_codec_poll_completed_work(d, &id));
        try expectEqual(expected, id);
    }
    try expectEqual(0, codec.azk_codec_poll_completed_work(d, &id));
}

test "flush discards queued work and permits frame index reuse" {
    const d = codec.azk_codec_open("pcm_s16le") orelse return error.NoDecoder;
    defer codec.azk_codec_close(d);
    try expectEqual(0, codec.azk_codec_configure(d, null, 0, 44100, 1));
    const pcm = [_]u8{ 6, 0, 7, 0 };
    try expectEqual(0, codec.azk_codec_send_work(d, &pcm, pcm.len, 222, 42));
    codec.azk_codec_flush(d);
    var id: u64 = undefined;
    try expectEqual(0, codec.azk_codec_poll_completed_work(d, &id));
    try expectEqual(0, codec.azk_codec_send_work(d, &pcm, pcm.len, 333, 42));
    var bytes: [32]u8 = undefined;
    var meta: [3]c_int = undefined;
    var pts: i64 = undefined;
    try expectEqual(0, codec.azk_codec_receive_audio_work(d, &bytes, bytes.len, &meta, &pts, &id));
    try expectEqual(42, id);
    try expectEqual(333, pts);
    try expectEqual(1, codec.azk_codec_receive_audio_work(d, &bytes, bytes.len, &meta, &pts, &id));
    try std.testing.expect(codec.azk_codec_send_work(d, null, 4, 0, 43) < 0);
    try std.testing.expect(codec.azk_codec_send_work(d, &pcm, -1, 0, 43) < 0);
}

test "legacy FFmpeg MP3 decoder round trip remains usable" {
    try std.testing.expect(codec.azk_codec_selftest() > 0);
}

fn twoMp3Frames() ![]u8 {
    const encoder = av.avcodec_find_encoder_by_name("libmp3lame") orelse return error.NoEncoder;
    const ctx = av.avcodec_alloc_context3(encoder) orelse return error.OutOfMemory;
    defer {
        var ptr = ctx;
        av.avcodec_free_context(&ptr);
    }
    ctx.*.sample_rate = 44100;
    ctx.*.sample_fmt = av.AV_SAMPLE_FMT_S16P;
    ctx.*.bit_rate = 128000;
    ctx.*.time_base = .{ .num = 1, .den = 44100 };
    av.av_channel_layout_default(&ctx.*.ch_layout, 2);
    try expectEqual(0, av.avcodec_open2(ctx, null, null));
    const frame = av.av_frame_alloc() orelse return error.OutOfMemory;
    defer {
        var ptr = frame;
        av.av_frame_free(&ptr);
    }
    frame.*.sample_rate = ctx.*.sample_rate;
    frame.*.format = ctx.*.sample_fmt;
    frame.*.nb_samples = ctx.*.frame_size;
    av.av_channel_layout_default(&frame.*.ch_layout, 2);
    try expectEqual(0, av.av_frame_get_buffer(frame, 0));
    const packet = av.av_packet_alloc() orelse return error.OutOfMemory;
    defer {
        var ptr = packet;
        av.av_packet_free(&ptr);
    }
    var bytes: std.ArrayList(u8) = .empty;
    errdefer bytes.deinit(std.testing.allocator);
    var count: usize = 0;
    for (0..20) |i| {
        try expectEqual(0, av.av_frame_make_writable(frame));
        for (0..2) |ch| @memset(frame.*.data[ch][0..@intCast(frame.*.nb_samples * 2)], 0);
        frame.*.pts = @as(i64, @intCast(i)) * frame.*.nb_samples;
        try expectEqual(0, av.avcodec_send_frame(ctx, frame));
        while (av.avcodec_receive_packet(ctx, packet) == 0) {
            defer av.av_packet_unref(packet);
            try bytes.appendSlice(std.testing.allocator, packet.*.data[0..@intCast(packet.*.size)]);
            count += 1;
            if (count == 2) return bytes.toOwnedSlice(std.testing.allocator);
        }
    }
    return error.NoEncodedFrames;
}

test "multiple MP3 outputs retain one work until all outputs are consumed" {
    const encoded = try twoMp3Frames();
    defer std.testing.allocator.free(encoded);
    const tagged = codec.azk_codec_open("mp3") orelse return error.NoDecoder;
    defer codec.azk_codec_close(tagged);
    const reference = codec.azk_codec_open("mp3") orelse return error.NoDecoder;
    defer codec.azk_codec_close(reference);
    for ([_]*codec.Dec{ tagged, reference }) |d| try expectEqual(0, codec.azk_codec_configure(d, null, 0, 44100, 2));
    try expectEqual(0, codec.azk_codec_send_work(tagged, encoded.ptr, @intCast(encoded.len), 7654321, 99));
    try expectEqual(0, codec.azk_codec_send(reference, encoded.ptr, @intCast(encoded.len), 7654321, 0));
    var id: u64 = undefined;
    try expectEqual(0, codec.azk_codec_poll_completed_work(tagged, &id));
    var bytes: [16384]u8 = undefined;
    var expected: [16384]u8 = undefined;
    var meta: [3]c_int = undefined;
    var reference_meta: [3]c_int = undefined;
    var pts: i64 = undefined;
    var reference_pts: i64 = undefined;
    for (0..2) |_| {
        try expectEqual(0, codec.azk_codec_poll_completed_work(tagged, &id));
        try expectEqual(0, codec.azk_codec_receive_audio_work(tagged, &bytes, bytes.len, &meta, &pts, &id));
        try expectEqual(99, id);
        try expectEqual(0, codec.azk_codec_receive_audio(reference, &expected, expected.len, &reference_meta, &reference_pts));
        try std.testing.expectEqualSlices(c_int, &reference_meta, &meta);
        try expectEqual(reference_pts, pts);
        try std.testing.expectEqualSlices(u8, expected[0..@intCast(meta[0])], bytes[0..@intCast(meta[0])]);
    }
    try expectEqual(0, codec.azk_codec_send(tagged, null, 0, 0, 1));
    try expectEqual(2, codec.azk_codec_receive_audio_work(tagged, &bytes, bytes.len, &meta, &pts, &id));
    try expectEqual(1, codec.azk_codec_poll_completed_work(tagged, &id));
    try expectEqual(99, id);
    try expectEqual(0, codec.azk_codec_poll_completed_work(tagged, &id));
}
