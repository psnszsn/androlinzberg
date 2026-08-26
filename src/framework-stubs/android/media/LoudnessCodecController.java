/* artzig stub: host audio does not apply platform loudness metadata. */
package android.media;

import java.util.concurrent.Executor;

public class LoudnessCodecController implements AutoCloseable {
    private LoudnessCodecController() {}

    public static LoudnessCodecController create(int audioSessionId) {
        return new LoudnessCodecController();
    }

    public static LoudnessCodecController create(int audioSessionId, Executor executor,
            OnLoudnessCodecUpdateListener listener) {
        return new LoudnessCodecController();
    }

    public boolean addMediaCodec(MediaCodec mediaCodec) { return false; }
    public void removeMediaCodec(MediaCodec mediaCodec) {}
    public android.os.Bundle getLoudnessCodecParams(MediaCodec mediaCodec) {
        return android.os.Bundle.EMPTY;
    }
    public void close() {}

    public interface OnLoudnessCodecUpdateListener {
        default android.os.Bundle onLoudnessCodecUpdate(
                MediaCodec mediaCodec, android.os.Bundle parameters) {
            return parameters;
        }
    }
}
