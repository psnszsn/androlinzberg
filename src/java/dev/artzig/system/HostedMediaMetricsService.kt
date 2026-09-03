package dev.artzig.system

import android.media.metrics.EditingEndedEvent
import android.media.metrics.NetworkEvent
import android.media.metrics.PlaybackErrorEvent
import android.media.metrics.PlaybackMetrics
import android.media.metrics.PlaybackStateEvent
import android.media.metrics.TrackChangeEvent
import android.os.PersistableBundle
import android.util.Base64
import java.security.SecureRandom

/**
 * The session's media metrics manager over the real `IMediaMetricsManager`
 * surface, published as `Context.MEDIA_METRICS_SERVICE`.
 *
 * Source note: `com.android.server.media.metrics.MediaMetricsManagerService`:
 * session ids are random 16-character Base64 strings, and every reported
 * metric is logged (there it is a statsd atom; here it is a log line, since
 * no statsd exists in a hosted session).
 */
class HostedMediaMetricsService : UnsupportedMediaMetricsManager() {
    private val random = SecureRandom()

    /** MediaMetricsManagerService.getSessionIdInternal. */
    private fun newSessionId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    override fun getPlaybackSessionId(userId: Int): String = newSessionId()
    override fun getRecordingSessionId(userId: Int): String = newSessionId()
    override fun getTranscodingSessionId(userId: Int): String = newSessionId()
    override fun getEditingSessionId(userId: Int): String = newSessionId()
    override fun getBundleSessionId(userId: Int): String = newSessionId()
    override fun releaseSessionId(sessionId: String?, userId: Int) {}
    override fun reportPlaybackMetrics(sessionId: String?, metrics: PlaybackMetrics?, userId: Int) {
        android.util.Log.d(TAG, "playback metrics $sessionId: $metrics")
    }
    override fun reportNetworkEvent(sessionId: String?, event: NetworkEvent?, userId: Int) {
        android.util.Log.d(TAG, "network event $sessionId: $event")
    }
    override fun reportPlaybackErrorEvent(sessionId: String?, event: PlaybackErrorEvent?, userId: Int) {
        android.util.Log.d(TAG, "playback error $sessionId: $event")
    }
    override fun reportPlaybackStateEvent(sessionId: String?, event: PlaybackStateEvent?, userId: Int) {
        android.util.Log.d(TAG, "playback state $sessionId: $event")
    }
    override fun reportTrackChangeEvent(sessionId: String?, event: TrackChangeEvent?, userId: Int) {
        android.util.Log.d(TAG, "track change $sessionId: $event")
    }
    override fun reportEditingEndedEvent(sessionId: String?, event: EditingEndedEvent?, userId: Int) {
        android.util.Log.d(TAG, "editing ended $sessionId: $event")
    }
    override fun reportBundleMetrics(sessionId: String?, metrics: PersistableBundle?, userId: Int) {
        android.util.Log.d(TAG, "bundle metrics $sessionId: $metrics")
    }

    private companion object {
        const val TAG = "HostedMediaMetrics"
    }
}
