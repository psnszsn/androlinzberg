/* artzig stub: media metrics are dropped. */
package android.media.metrics;
public final class MediaMetricsManager {
    public static final long INVALID_TIMESTAMP = -1;
    public PlaybackSession createPlaybackSession() { return new PlaybackSession(); }
    public RecordingSession createRecordingSession() { return new RecordingSession(); }
}
