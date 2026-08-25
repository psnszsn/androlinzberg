/* artzig shim: PCM playback over PipeWire (platform/audio.zig); 16-bit PCM only.
 * Non-PCM-16 input is accepted but converted/refused at write time. */
package android.media;

import java.nio.ByteBuffer;

public class AudioTrack {
    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_NO_STATIC_DATA = 2;
    public static final int PLAYSTATE_STOPPED = 1;
    public static final int PLAYSTATE_PAUSED = 2;
    public static final int PLAYSTATE_PLAYING = 3;
    public static final int MODE_STATIC = 0;
    public static final int MODE_STREAM = 1;
    public static final int WRITE_BLOCKING = 0;
    public static final int WRITE_NON_BLOCKING = 1;
    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int ERROR_BAD_VALUE = -2;
    public static final int ERROR_INVALID_OPERATION = -3;
    public static final int ERROR_DEAD_OBJECT = -6;

    private static int sNextSessionId = 1;

    private long mHandle;
    private final AudioFormat mFormat;
    private final int mBufferSize;
    private final int mSessionId;
    private int mPlayState = PLAYSTATE_STOPPED;
    private long mFlushedFrames;

    private static native long nativeOpen(int sampleRate, int channels);
    private static native int nativeWrite(long h, ByteBuffer buf, int offset, int size);
    private static native int nativeWriteArray(long h, byte[] data, int offset, int size);
    private static native void nativePlay(long h);
    private static native void nativePause(long h);
    private static native void nativeFlush(long h);
    private static native long nativeGetPosition(long h);
    private static native long nativeGetPositionNanos(long h);
    private static native int nativeGetUnderruns(long h);
    private static native void nativeSetVolume(long h, float v);
    private static native void nativeRelease(long h);

    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode) {
        this(new AudioAttributes.Builder().build(),
                new AudioFormat.Builder().setSampleRate(sampleRateInHz)
                        .setChannelMask(channelConfig).setEncoding(audioFormat).build(),
                bufferSizeInBytes, mode, nextSessionId());
    }

    public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes,
            int mode, int sessionId) {
        mFormat = format;
        mBufferSize = bufferSizeInBytes;
        mSessionId = sessionId != 0 ? sessionId : nextSessionId();
        mHandle = nativeOpen(format.getSampleRate(), Math.max(1, format.getChannelCount()));
    }

    private static synchronized int nextSessionId() { return sNextSessionId++; }

    public static class Builder {
        private AudioAttributes mAttrs = new AudioAttributes.Builder().build();
        private AudioFormat mFmt = new AudioFormat.Builder().build();
        private int mBufferSize = 65536;
        private int mSessionId = 0;
        public Builder() {}
        public Builder setAudioAttributes(AudioAttributes attributes) { mAttrs = attributes; return this; }
        public Builder setAudioFormat(AudioFormat format) { mFmt = format; return this; }
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) { mBufferSize = bufferSizeInBytes; return this; }
        public Builder setTransferMode(int mode) { return this; }
        public Builder setSessionId(int sessionId) { mSessionId = sessionId; return this; }
        public Builder setPerformanceMode(int performanceMode) { return this; }
        public Builder setOffloadedPlayback(boolean offload) { return this; }
        public AudioTrack build() { return new AudioTrack(mAttrs, mFmt, mBufferSize, MODE_STREAM, mSessionId); }
    }

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        int channels = Integer.bitCount(channelConfig);
        if (channels == 0) channels = 2;
        // a quarter second of s16 PCM
        return Math.max(4096, sampleRateInHz * channels * 2 / 4);
    }

    public int getState() { return mHandle != 0 ? STATE_INITIALIZED : STATE_UNINITIALIZED; }
    public int getPlayState() { return mPlayState; }
    public int getAudioSessionId() { return mSessionId; }
    public int getSampleRate() { return mFormat.getSampleRate(); }
    public int getPlaybackRate() { return mFormat.getSampleRate(); }
    public AudioFormat getFormat() { return mFormat; }
    public int getChannelCount() { return Math.max(1, mFormat.getChannelCount()); }
    public int getBufferSizeInFrames() { return mBufferSize / (2 * getChannelCount()); }
    public int getBufferCapacityInFrames() { return getBufferSizeInFrames(); }

    public void play() {
        mPlayState = PLAYSTATE_PLAYING;
        nativePlay(mHandle);
    }
    public void pause() {
        mPlayState = PLAYSTATE_PAUSED;
        nativePause(mHandle);
    }
    public void stop() {
        mPlayState = PLAYSTATE_STOPPED;
        nativePause(mHandle);
    }
    public void flush() {
        mFlushedFrames = nativeGetPosition(mHandle);
        nativeFlush(mHandle);
    }
    public void release() {
        if (mHandle != 0) { nativeRelease(mHandle); mHandle = 0; }
        mPlayState = PLAYSTATE_STOPPED;
    }

    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return nativeWriteArray(mHandle, audioData, offsetInBytes, sizeInBytes);
    }
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        return write(audioData, offsetInBytes, sizeInBytes);
    }
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
        int written;
        if (audioData.isDirect()) {
            written = nativeWrite(mHandle, audioData, audioData.position(), sizeInBytes);
        } else {
            byte[] tmp = new byte[sizeInBytes];
            int p = audioData.position();
            audioData.get(tmp, 0, sizeInBytes);
            audioData.position(p);
            written = nativeWriteArray(mHandle, tmp, 0, sizeInBytes);
        }
        if (written > 0) audioData.position(audioData.position() + written);
        return written;
    }
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        return write(audioData, sizeInBytes, writeMode);
    }

    public int getPlaybackHeadPosition() {
        // int wraps like the platform's unsigned 32-bit counter
        return (int) nativeGetPosition(mHandle);
    }
    public boolean getTimestamp(AudioTimestamp timestamp) {
        long pos = nativeGetPosition(mHandle);
        long ns = nativeGetPositionNanos(mHandle);
        if (ns == 0) return false;
        timestamp.framePosition = pos;
        timestamp.nanoTime = ns;
        return true;
    }
    public int getUnderrunCount() { return nativeGetUnderruns(mHandle); }

    public int setVolume(float gain) { nativeSetVolume(mHandle, gain); return SUCCESS; }
    public int setStereoVolume(float leftGain, float rightGain) { return setVolume(leftGain); }
    public void setPlaybackParams(PlaybackParams params) {}
    public PlaybackParams getPlaybackParams() { return new PlaybackParams(); }
    public int setPlaybackRate(int sampleRateInHz) { return SUCCESS; }
    public int attachAuxEffect(int effectId) { return SUCCESS; }
    public int setAuxEffectSendLevel(float level) { return SUCCESS; }
    public void setPreferredDevice(Object deviceInfo) {}
    public boolean isOffloadedPlayback() { return false; }
    public void setLogSessionId(android.media.metrics.LogSessionId id) {}
    public android.media.metrics.LogSessionId getLogSessionId() {
        return android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;
    }
    public static boolean isDirectPlaybackSupported(AudioFormat format, AudioAttributes attributes) {
        return false;
    }
}
