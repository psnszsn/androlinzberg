/* artzig shim: PCM playback over PipeWire (platform/audio.zig); 16-bit PCM only.
 * Non-PCM-16 input is accepted but converted/refused at write time. */
package android.media;

import java.nio.ByteBuffer;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.RequiresPermission;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class AudioTrack implements AudioRouting {
    // Pinned AudioTrack write-mode annotation used by virtual audio injection.
    @IntDef({ WRITE_BLOCKING, WRITE_NON_BLOCKING })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WriteMode {}
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
    private AudioDeviceInfo mPreferredDevice;

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
        mFormat = resolveHostedFormat(format);
        mBufferSize = bufferSizeInBytes;
        mSessionId = sessionId != 0 ? sessionId : nextSessionId();
        mHandle = nativeOpen(mFormat.getSampleRate(), Math.max(1, mFormat.getChannelCount()));
    }

    /**
     * Pinned AudioTrack constructor defaults: unspecified encoding selects PCM16 and
     * absent channel masks select stereo. The native AudioTrack resolves a zero sample
     * rate from the output; this adapter resolves it from the existing 48 kHz host output.
     */
    private static AudioFormat resolveHostedFormat(AudioFormat format) {
        if (format == null) throw new IllegalArgumentException("Illegal null AudioFormat");
        AudioFormat.Builder resolved = new AudioFormat.Builder(format);
        if (format.getSampleRate() == AudioFormat.SAMPLE_RATE_UNSPECIFIED) {
            resolved.setSampleRate(48000);
        }
        if ((format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_ENCODING) == 0) {
            resolved.setEncoding(AudioFormat.ENCODING_DEFAULT);
        }
        if (format.getChannelMask() == 0 && format.getChannelIndexMask() == 0) {
            resolved.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
        }
        return resolved.build();
    }

    private static synchronized int nextSessionId() { return sNextSessionId++; }

    public static class Builder {
        private AudioAttributes mAttrs = new AudioAttributes.Builder().build();
        private AudioFormat mFmt = new AudioFormat.Builder().build();
        private int mBufferSize = 65536;
        private int mSessionId = 0;
        private int mCallRedirectionMode = AudioManager.CALL_REDIRECT_NONE;
        public Builder() {}
        public Builder setAudioAttributes(AudioAttributes attributes) { mAttrs = attributes; return this; }
        public Builder setAudioFormat(AudioFormat format) { mFmt = format; return this; }
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) { mBufferSize = bufferSizeInBytes; return this; }
        public Builder setTransferMode(int mode) { return this; }
        public Builder setSessionId(int sessionId) { mSessionId = sessionId; return this; }
        public Builder setPerformanceMode(int performanceMode) { return this; }
        public Builder setOffloadedPlayback(boolean offload) { return this; }
        // Pinned Builder.setCallRedirectionMode validation. Call injection requires
        // the native audio-policy route, which the existing PCM backend does not own.
        public Builder setCallRedirectionMode(int callRedirectionMode) {
            switch (callRedirectionMode) {
                case AudioManager.CALL_REDIRECT_NONE:
                case AudioManager.CALL_REDIRECT_PSTN:
                case AudioManager.CALL_REDIRECT_VOIP:
                    mCallRedirectionMode = callRedirectionMode;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid call redirection mode " + callRedirectionMode);
            }
            return this;
        }
        public AudioTrack build() {
            dev.artzig.HostedAudioArrayWrites.checkCallRedirection(mCallRedirectionMode);
            return new AudioTrack(mAttrs, mFmt, mBufferSize, MODE_STREAM, mSessionId);
        }
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

    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
        return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
    }
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts, int writeMode) {
        return dev.artzig.HostedAudioArrayWrites.write(this, audioData, offsetInShorts, sizeInShorts, writeMode);
    }
    public int write(float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode) {
        return dev.artzig.HostedAudioArrayWrites.write(this, audioData, offsetInFloats, sizeInFloats, writeMode);
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
    // AOSP android_media_AudioTrack_get_playback_params marks every returned field set.
    // The current hosted stream supports the default rate only.
    public PlaybackParams getPlaybackParams() {
        if (mHandle == 0) throw new IllegalStateException("AudioTrack not initialized");
        return new PlaybackParams().allowDefaults();
    }
    public int setPlaybackRate(int sampleRateInHz) { return SUCCESS; }
    public int attachAuxEffect(int effectId) { return SUCCESS; }
    public int setAuxEffectSendLevel(float level) { return SUCCESS; }
    /**
     * Hosted adaptation of AOSP AudioTrack's AudioRouting implementation. The host exposes one
     * fixed PipeWire-backed sink, so preference changes are retained but cannot cause rerouting.
     */
    @Override
    public synchronized boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo != null && !deviceInfo.isSink()) return false;
        mPreferredDevice = deviceInfo;
        return true;
    }
    @Override
    public synchronized AudioDeviceInfo getPreferredDevice() { return mPreferredDevice; }
    @Override
    public AudioDeviceInfo getRoutedDevice() {
        if (mPlayState != PLAYSTATE_PLAYING) return null;
        AudioDeviceInfo[] outputs = new AudioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        return outputs.length == 0 ? null : outputs[0];
    }
    @Override
    public java.util.List<AudioDeviceInfo> getRoutedDevices() {
        AudioDeviceInfo routed = getRoutedDevice();
        return routed == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(routed);
    }
    @Override
    public void addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener listener, android.os.Handler handler) {
        // AOSP only dispatches when the native route changes. The hosted route is fixed.
    }
    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {}
    public boolean isOffloadedPlayback() { return false; }
    public void setLogSessionId(android.media.metrics.LogSessionId id) {}
    public android.media.metrics.LogSessionId getLogSessionId() {
        return android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE;
    }
    public static boolean isDirectPlaybackSupported(AudioFormat format, AudioAttributes attributes) {
        return false;
    }


    // Pinned AudioTrack encapsulation declarations used by stock AudioDevicePort.
    /** @hide */
    @IntDef({
        ENCAPSULATION_MODE_NONE,
        ENCAPSULATION_MODE_ELEMENTARY_STREAM,
        // ENCAPSULATION_MODE_HANDLE, @SystemApi
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapsulationMode {}

    // Important: The ENCAPSULATION_MODE values must be kept in sync with native header files.
    /**
     * This mode indicates no metadata encapsulation,
     * which is the default mode for sending audio data
     * through {@code AudioTrack}.
     */
    public static final int ENCAPSULATION_MODE_NONE = 0;
    /**
     * This mode indicates metadata encapsulation with an elementary stream payload.
     * Both compressed and PCM format is allowed.
     */
    public static final int ENCAPSULATION_MODE_ELEMENTARY_STREAM = 1;
    /**
     * This mode indicates metadata encapsulation with a handle payload
     * and is set through {@link Builder#setEncapsulationMode(int)}.
     * The handle is a 64 bit long, provided by the Tuner API
     * in {@link android.os.Build.VERSION_CODES#R}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int ENCAPSULATION_MODE_HANDLE = 2;

    /**
     * Enumeration of metadata types permitted for use by
     * encapsulation mode audio streams.
     * @hide
     */
    @IntDef(prefix = {"ENCAPSULATION_METADATA_TYPE_"},
            value =
                    {
                            ENCAPSULATION_METADATA_TYPE_NONE, /* reserved */
                            ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER,
                            ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR,
                            ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapsulationMetadataType {}

    /**
     * Reserved do not use.
     * @hide
     */
    public static final int ENCAPSULATION_METADATA_TYPE_NONE = 0; // reserved

    /**
     * Encapsulation metadata type for framework tuner information.
     *
     * Refer to the Android Media TV Tuner API for details.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER = 1;

    /**
     * Encapsulation metadata type for DVB AD descriptor.
     *
     * This metadata is formatted per ETSI TS 101 154 Table E.1: AD_descriptor.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR = 2;

    /**
     * Encapsulation metadata type for placement of supplementary audio.
     *
     * A 32 bit integer constant, one of {@link #SUPPLEMENTARY_AUDIO_PLACEMENT_NORMAL}, {@link
     * #SUPPLEMENTARY_AUDIO_PLACEMENT_LEFT}, {@link #SUPPLEMENTARY_AUDIO_PLACEMENT_RIGHT}.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT = 3;


}
