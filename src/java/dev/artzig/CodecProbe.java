package dev.artzig;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.FileInputStream;
import java.nio.ByteBuffer;

/** Full-chain test: mp3 file -> MediaCodec (ffmpeg) -> AudioTrack (PipeWire). */
public final class CodecProbe {
    public static void main(String[] args) throws Exception {
        String path = System.getenv("AZK_TEST_MP3");
        if (path == null) path = "/tmp/azk-test.mp3";
        byte[] data;
        try (FileInputStream in = new FileInputStream(path)) {
            data = in.readAllBytes();
        }
        System.out.println("mp3 bytes=" + data.length);

        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG);
        codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44100, 2),
                null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        AudioTrack track = null;
        long totalSamples = 0;
        int off = 0;
        boolean inputDone = false;
        boolean outputDone = false;
        while (!outputDone) {
            if (!inputDone) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    int frame = nextFrameLength(data, off);
                    if (frame <= 0 || off + frame > data.length) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        ByteBuffer in = codec.getInputBuffer(idx);
                        in.put(data, off, frame);
                        codec.queueInputBuffer(idx, 0, frame, off, 0);
                        off += frame;
                    }
                }
            }
            int idx = codec.dequeueOutputBuffer(info, 10000);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                System.out.println("output format: " + rate + " Hz, " + ch + " ch");
                track = new AudioTrack.Builder()
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setChannelMask(ch == 1 ? AudioFormat.CHANNEL_OUT_MONO
                                        : AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(256 * 1024)
                        .build();
                track.play();
            } else if (idx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                if (info.size > 0 && track != null) {
                    ByteBuffer out = codec.getOutputBuffer(idx);
                    totalSamples += info.size / 4;
                    while (out.hasRemaining()) {
                        int n = track.write(out, out.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                        if (n <= 0) Thread.sleep(10);
                    }
                }
                codec.releaseOutputBuffer(idx, false);
            }
        }
        System.out.println("decoded samples=" + totalSamples + " ("
                + (totalSamples / 44100.0) + " s) " + (totalSamples > 40000 ? "PASS" : "FAIL"));
        // let the tail drain
        Thread.sleep(400);
        if (track != null) track.release();
        codec.release();
        System.out.println("codec probe done");
    }

    /** Minimal MPEG-1 Layer III frame-length parser. */
    private static int nextFrameLength(byte[] d, int off) {
        while (off + 4 < d.length
                && !((d[off] & 0xFF) == 0xFF && (d[off + 1] & 0xE0) == 0xE0)) off++;
        if (off + 4 >= d.length) return -1;
        int bitrateIdx = (d[off + 2] >> 4) & 0xF;
        int srIdx = (d[off + 2] >> 2) & 0x3;
        int padding = (d[off + 2] >> 1) & 0x1;
        int[] bitrates = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0 };
        int[] rates = { 44100, 48000, 32000, 0 };
        if (bitrates[bitrateIdx] == 0 || rates[srIdx] == 0) return -1;
        return 144 * bitrates[bitrateIdx] * 1000 / rates[srIdx] + padding;
    }
}
