/* artzig shim: PCM format description. */
package android.media;

public final class AudioFormat {
    public static final int ENCODING_INVALID = 0;
    public static final int ENCODING_DEFAULT = 1;
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int ENCODING_PCM_8BIT = 3;
    public static final int ENCODING_PCM_FLOAT = 4;
    public static final int ENCODING_AC3 = 5;
    public static final int ENCODING_E_AC3 = 6;
    public static final int ENCODING_DTS = 7;
    public static final int ENCODING_PCM_24BIT_PACKED = 21;
    public static final int ENCODING_PCM_32BIT = 22;
    public static final int CHANNEL_OUT_MONO = 0x4;
    public static final int CHANNEL_OUT_STEREO = 0xC;
    public static final int CHANNEL_OUT_QUAD = 0xCC;
    public static final int CHANNEL_OUT_5POINT1 = 0xFC;
    public static final int CHANNEL_OUT_7POINT1_SURROUND = 0x18FC;
    public static final int CHANNEL_INVALID = 0;
    public static final int SAMPLE_RATE_UNSPECIFIED = 0;

    private final int mEncoding, mSampleRate, mChannelMask;

    AudioFormat(int encoding, int sampleRate, int channelMask) {
        mEncoding = encoding;
        mSampleRate = sampleRate;
        mChannelMask = channelMask;
    }
    public int getEncoding() { return mEncoding; }
    public int getSampleRate() { return mSampleRate; }
    public int getChannelMask() { return mChannelMask; }
    public int getChannelCount() { return Integer.bitCount(mChannelMask); }

    public static int channelCountFromOutChannelMask(int mask) { return Integer.bitCount(mask); }

    public static class Builder {
        private int mEncoding = ENCODING_PCM_16BIT;
        private int mSampleRate = 44100;
        private int mChannelMask = CHANNEL_OUT_STEREO;
        public Builder() {}
        public Builder setEncoding(int encoding) { mEncoding = encoding; return this; }
        public Builder setSampleRate(int sampleRate) { mSampleRate = sampleRate; return this; }
        public Builder setChannelMask(int channelMask) { mChannelMask = channelMask; return this; }
        public Builder setChannelIndexMask(int channelIndexMask) { return this; }
        public AudioFormat build() { return new AudioFormat(mEncoding, mSampleRate, mChannelMask); }
    }
}
