/* artzig stub. */
package android.media.metrics;
public final class PlaybackSession implements AutoCloseable {
    public LogSessionId getSessionId() { return new LogSessionId(); }
    public void reportPlaybackMetrics(PlaybackMetrics metrics) {}
    public void reportPlaybackErrorEvent(PlaybackErrorEvent event) {}
    public void reportNetworkEvent(NetworkEvent event) {}
    public void reportPlaybackStateEvent(PlaybackStateEvent event) {}
    public void reportTrackChangeEvent(TrackChangeEvent event) {}
    @Override public void close() {}
}
