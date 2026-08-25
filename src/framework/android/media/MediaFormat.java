/* artzig shim: map-backed MediaFormat. */
package android.media;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class MediaFormat {
    public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm";
    public static final String MIMETYPE_AUDIO_OPUS = "audio/opus";
    public static final String MIMETYPE_AUDIO_MPEG = "audio/mpeg";
    public static final String MIMETYPE_AUDIO_FLAC = "audio/flac";
    public static final String MIMETYPE_AUDIO_VORBIS = "audio/vorbis";
    public static final String MIMETYPE_AUDIO_RAW = "audio/raw";
    public static final String MIMETYPE_VIDEO_AVC = "video/avc";
    public static final String MIMETYPE_VIDEO_HEVC = "video/hevc";
    public static final String MIMETYPE_VIDEO_VP9 = "video/x-vnd.on2.vp9";
    public static final String MIMETYPE_VIDEO_AV1 = "video/av01";
    public static final String MIMETYPE_IMAGE_ANDROID_HEIC = "image/vnd.android.heic";

    public static final String KEY_MIME = "mime";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_SAMPLE_RATE = "sample-rate";
    public static final String KEY_CHANNEL_COUNT = "channel-count";
    public static final String KEY_MAX_INPUT_SIZE = "max-input-size";
    public static final String KEY_BIT_RATE = "bitrate";
    public static final String KEY_DURATION = "durationUs";
    public static final String KEY_PCM_ENCODING = "pcm-encoding";
    public static final String KEY_FRAME_RATE = "frame-rate";
    public static final String KEY_ROTATION = "rotation-degrees";
    public static final String KEY_PRIORITY = "priority";
    public static final String KEY_OPERATING_RATE = "operating-rate";
    public static final String KEY_LOW_LATENCY = "low-latency";
    public static final String KEY_MAX_WIDTH = "max-width";
    public static final String KEY_MAX_HEIGHT = "max-height";
    public static final String KEY_PROFILE = "profile";
    public static final String KEY_LEVEL = "level";
    public static final String KEY_COLOR_FORMAT = "color-format";
    public static final String KEY_CROP_LEFT = "crop-left";
    public static final String KEY_CROP_RIGHT = "crop-right";
    public static final String KEY_CROP_TOP = "crop-top";
    public static final String KEY_CROP_BOTTOM = "crop-bottom";
    public static final String KEY_STRIDE = "stride";
    public static final String KEY_SLICE_HEIGHT = "slice-height";
    public static final String KEY_TILE_WIDTH = "tile-width";
    public static final String KEY_TILE_HEIGHT = "tile-height";
    public static final String KEY_AAC_PROFILE = "aac-profile";
    public static final String KEY_IS_ADTS = "is-adts";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_ENCODER_DELAY = "encoder-delay";
    public static final String KEY_ENCODER_PADDING = "encoder-padding";

    private final Map<String, Object> mMap = new HashMap<>();

    public MediaFormat() {}
    public MediaFormat(MediaFormat other) { mMap.putAll(other.mMap); }

    public boolean containsKey(String name) { return mMap.containsKey(name); }
    public void setString(String name, String value) { mMap.put(name, value); }
    public void setInteger(String name, int value) { mMap.put(name, value); }
    public void setLong(String name, long value) { mMap.put(name, value); }
    public void setFloat(String name, float value) { mMap.put(name, value); }
    public void setByteBuffer(String name, ByteBuffer bytes) { mMap.put(name, bytes); }
    public void removeKey(String name) { mMap.remove(name); }
    public String getString(String name) { return (String) mMap.get(name); }
    public String getString(String name, String defaultValue) {
        Object v = mMap.get(name);
        return v != null ? (String) v : defaultValue;
    }
    public int getInteger(String name) { return ((Number) mMap.get(name)).intValue(); }
    public int getInteger(String name, int defaultValue) {
        Object v = mMap.get(name);
        return v != null ? ((Number) v).intValue() : defaultValue;
    }
    public long getLong(String name) { return ((Number) mMap.get(name)).longValue(); }
    public long getLong(String name, long defaultValue) {
        Object v = mMap.get(name);
        return v != null ? ((Number) v).longValue() : defaultValue;
    }
    public float getFloat(String name) { return ((Number) mMap.get(name)).floatValue(); }
    public float getFloat(String name, float defaultValue) {
        Object v = mMap.get(name);
        return v != null ? ((Number) v).floatValue() : defaultValue;
    }
    public ByteBuffer getByteBuffer(String name) { return (ByteBuffer) mMap.get(name); }
    public java.util.Set<String> getKeys() { return mMap.keySet(); }

    public static MediaFormat createAudioFormat(String mime, int sampleRate, int channelCount) {
        MediaFormat f = new MediaFormat();
        f.setString(KEY_MIME, mime);
        f.setInteger(KEY_SAMPLE_RATE, sampleRate);
        f.setInteger(KEY_CHANNEL_COUNT, channelCount);
        return f;
    }
    public static MediaFormat createVideoFormat(String mime, int width, int height) {
        MediaFormat f = new MediaFormat();
        f.setString(KEY_MIME, mime);
        f.setInteger(KEY_WIDTH, width);
        f.setInteger(KEY_HEIGHT, height);
        return f;
    }
    @Override public String toString() { return "MediaFormat" + mMap; }
}
