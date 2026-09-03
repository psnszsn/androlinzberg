@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IMediaMetricsManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedMediaMetricsManager : android.media.metrics.IMediaMetricsManager.Stub() {
    override fun reportPlaybackMetrics(sessionId: String?, metrics: android.media.metrics.PlaybackMetrics?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportPlaybackMetrics is not hosted") }
    override fun getPlaybackSessionId(userId: Int): String? = throw UnsupportedOperationException("IMediaMetricsManager.getPlaybackSessionId is not hosted")
    override fun getRecordingSessionId(userId: Int): String? = throw UnsupportedOperationException("IMediaMetricsManager.getRecordingSessionId is not hosted")
    override fun reportNetworkEvent(sessionId: String?, event: android.media.metrics.NetworkEvent?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportNetworkEvent is not hosted") }
    override fun reportPlaybackErrorEvent(sessionId: String?, event: android.media.metrics.PlaybackErrorEvent?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportPlaybackErrorEvent is not hosted") }
    override fun reportPlaybackStateEvent(sessionId: String?, event: android.media.metrics.PlaybackStateEvent?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportPlaybackStateEvent is not hosted") }
    override fun reportTrackChangeEvent(sessionId: String?, event: android.media.metrics.TrackChangeEvent?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportTrackChangeEvent is not hosted") }
    override fun reportEditingEndedEvent(sessionId: String?, event: android.media.metrics.EditingEndedEvent?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportEditingEndedEvent is not hosted") }
    override fun getTranscodingSessionId(userId: Int): String? = throw UnsupportedOperationException("IMediaMetricsManager.getTranscodingSessionId is not hosted")
    override fun getEditingSessionId(userId: Int): String? = throw UnsupportedOperationException("IMediaMetricsManager.getEditingSessionId is not hosted")
    override fun getBundleSessionId(userId: Int): String? = throw UnsupportedOperationException("IMediaMetricsManager.getBundleSessionId is not hosted")
    override fun reportBundleMetrics(sessionId: String?, metrics: android.os.PersistableBundle?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.reportBundleMetrics is not hosted") }
    override fun releaseSessionId(sessionId: String?, userId: Int) { throw UnsupportedOperationException("IMediaMetricsManager.releaseSessionId is not hosted") }
}
