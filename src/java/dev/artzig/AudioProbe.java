package dev.artzig;

import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

/** Plays a 2-second 440 Hz sine and checks that the playback clock advances. */
public final class AudioProbe {
    public static void main(String[] args) throws Exception {
        int rate = 48000;
        AudioTrack t = new AudioTrack.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT))
                .build();
        System.out.println("state=" + t.getState());
        int seconds = 2;
        byte[] pcm = new byte[rate * 4 * seconds];
        for (int i = 0; i < rate * seconds; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12000);
            pcm[i * 4] = (byte) v;
            pcm[i * 4 + 1] = (byte) (v >> 8);
            pcm[i * 4 + 2] = (byte) v;
            pcm[i * 4 + 3] = (byte) (v >> 8);
        }
        t.play();
        int off = 0;
        long deadline = System.currentTimeMillis() + 4000;
        while (off < pcm.length && System.currentTimeMillis() < deadline) {
            int n = t.write(pcm, off, Math.min(65536, pcm.length - off));
            if (n > 0) off += n;
            else Thread.sleep(20);
        }
        Thread.sleep(300);
        AudioTimestamp ts = new AudioTimestamp();
        boolean ok = t.getTimestamp(ts);
        System.out.println("wrote=" + off + " pos=" + t.getPlaybackHeadPosition()
                + " ts=" + ok + " tsFrames=" + ts.framePosition
                + " underruns=" + t.getUnderrunCount());
        long p1 = t.getPlaybackHeadPosition();
        Thread.sleep(500);
        long p2 = t.getPlaybackHeadPosition();
        System.out.println("clock advance over 500ms: " + (p2 - p1) + " frames"
                + " (expect ~24000 while data remains, 0 when drained)");
        t.release();
        System.out.println("audio probe done");
    }
}
