/* artzig shim: describes the ffmpeg-backed decoders (audio for now). */
package android.media;

public final class MediaCodecInfo {
    // Pinned MediaCodecInfo security model API. The class still describes the
    // hosted FFmpeg codecs, whose native capability discovery is not yet stock.
    public static final int SECURITY_MODEL_SANDBOXED = 0;
    public static final int SECURITY_MODEL_MEMORY_SAFE = 1;
    public static final int SECURITY_MODEL_TRUSTED_CONTENT_ONLY = 2;

    private final String mName;
    private final String[] mTypes;

    MediaCodecInfo(String name, String[] types) {
        mName = name;
        mTypes = types;
    }

    public String getName() { return mName; }
    public String getCanonicalName() { return mName; }
    public boolean isEncoder() { return false; }
    public boolean isVendor() { return false; }
    public boolean isAlias() { return false; }
    public boolean isSoftwareOnly() { return true; }
    public boolean isHardwareAccelerated() { return false; }
    public String[] getSupportedTypes() { return mTypes.clone(); }

    public CodecCapabilities getCapabilitiesForType(String type) {
        for (String t : mTypes) {
            if (t.equalsIgnoreCase(type)) return new CodecCapabilities(t);
        }
        throw new IllegalArgumentException("unsupported type " + type + " for " + mName);
    }

    public static final class CodecCapabilities {
        public static final int COLOR_FormatYUV420Flexible = 0x7F420888;
        public static final int COLOR_FormatYUV420Planar = 19;
        public static final int COLOR_FormatYUVP010 = 54;
        public static final String FEATURE_AdaptivePlayback = "adaptive-playback";
        public static final String FEATURE_SecurePlayback = "secure-playback";
        public static final String FEATURE_TunneledPlayback = "tunneled-playback";
        public static final String FEATURE_PartialFrame = "partial-frame";
        public static final String FEATURE_FrameParsing = "frame-parsing";
        public static final String FEATURE_LowLatency = "low-latency";
        public static final String FEATURE_MultipleFrames = "multiple-frames";
        public static final String FEATURE_DynamicTimestamp = "dynamic-timestamp";

        public CodecProfileLevel[] profileLevels = new CodecProfileLevel[0];
        public int[] colorFormats = new int[0];
        private final String mMime;

        CodecCapabilities(String mime) {
            mMime = mime;
            if (mime.equals("video/avc")) {
                profileLevels = profiles(new int[][] {
                    { 0x01, 0x10000 }, { 0x02, 0x10000 }, { 0x08, 0x10000 },  // baseline/main/high @ L5.2
                });
                colorFormats = new int[] { COLOR_FormatYUV420Flexible, COLOR_FormatYUV420Planar };
            } else if (mime.equals("video/x-vnd.on2.vp9")) {
                profileLevels = profiles(new int[][] { { 0x01, 0x2000 } });
                colorFormats = new int[] { COLOR_FormatYUV420Flexible, COLOR_FormatYUV420Planar };
            } else if (mime.equals("audio/mp4a-latm")) {
                profileLevels = profiles(new int[][] { { CodecProfileLevel.AACObjectLC, 0 },
                        { CodecProfileLevel.AACObjectHE, 0 }, { CodecProfileLevel.AACObjectHE_PS, 0 } });
            }
        }
        private static CodecProfileLevel[] profiles(int[][] rows) {
            CodecProfileLevel[] out = new CodecProfileLevel[rows.length];
            for (int i = 0; i < rows.length; i++) {
                out[i] = new CodecProfileLevel();
                out[i].profile = rows[i][0];
                out[i].level = rows[i][1];
            }
            return out;
        }
        public CodecCapabilities() { mMime = "audio/raw"; }

        public String getMimeType() { return mMime; }
        public boolean isFormatSupported(MediaFormat format) { return true; }
        public boolean isFeatureSupported(String name) { return false; }
        public boolean isFeatureRequired(String name) { return false; }
        public int getMaxSupportedInstances() { return 32; }
        public AudioCapabilities getAudioCapabilities() {
            return mMime.startsWith("audio/") ? new AudioCapabilities() : null;
        }
        public VideoCapabilities getVideoCapabilities() {
            return mMime.startsWith("video/") ? new VideoCapabilities() : null;
        }
        public EncoderCapabilities getEncoderCapabilities() { return null; }
    }

    public static final class AudioCapabilities {
        public int getMaxInputChannelCount() { return 8; }
        public int getMinInputChannelCount() { return 1; }
        public boolean isSampleRateSupported(int sampleRate) {
            return sampleRate >= 8000 && sampleRate <= 192000;
        }
        public int[] getSupportedSampleRates() {
            return new int[] { 8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000 };
        }
    }

    public static final class VideoCapabilities {
        public java.util.List<PerformancePoint> getSupportedPerformancePoints() { return null; }
        public static final class PerformancePoint {
            public PerformancePoint(int width, int height, int frameRate) {}
            public boolean covers(android.media.MediaFormat format) { return true; }
        }
        public android.util.Range<Integer> getSupportedWidths() { return android.util.Range.create(2, 4096); }
        public android.util.Range<Integer> getSupportedHeights() { return android.util.Range.create(2, 4096); }
        public android.util.Range<Integer> getBitrateRange() { return android.util.Range.create(1, 200000000); }
        public android.util.Range<Double> getSupportedFrameRatesFor(int width, int height) {
            return android.util.Range.create(1.0, 120.0);
        }
        public boolean isSizeSupported(int width, int height) {
            return width <= 4096 && height <= 4096;
        }
        public boolean areSizeAndRateSupported(int width, int height, double frameRate) {
            return isSizeSupported(width, height) && frameRate <= 120;
        }
        public int getWidthAlignment() { return 2; }
        public int getHeightAlignment() { return 2; }
    }

    public static final class EncoderCapabilities {}

    public static final class CodecProfileLevel {
        public static final int AACObjectLC = 2;
        public static final int AACObjectHE = 5;
        public static final int AACObjectHE_PS = 29;
        public static final int AVCProfileBaseline = 0x01;
        public static final int AVCProfileMain = 0x02;
        public static final int AVCProfileHigh = 0x08;
        public static final int AVCLevel42 = 0x8000;
        public static final int VP9Profile0 = 0x01;
        public static final int VP9Level41 = 0x80;
        public int profile;
        public int level;
    }
}
