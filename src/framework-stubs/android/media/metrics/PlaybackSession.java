/* artzig stub. */
package android.media.metrics;
public final class PlaybackSession implements AutoCloseable {
    public LogSessionId getSessionId() { return new LogSessionId(); }
    public void reportPlaybackMetrics(Object metrics) {}
    public void reportPlaybackErrorEvent(Object event) {}
    public void reportNetworkEvent(Object event) {}
    public void reportPlaybackStateEvent(Object event) {}
    public void reportTrackChangeEvent(Object event) {}
    @Override public void close() {}
}
