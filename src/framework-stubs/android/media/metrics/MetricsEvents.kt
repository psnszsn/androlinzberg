package android.media.metrics

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Android media metrics value objects. The hosted MediaMetricsManager drops
 * reports because there is no system media-metrics service, but callers still
 * observe the AOSP builder, getter, and Parcelable contracts.
 */
abstract class Event {
    abstract fun getTimeSinceCreatedMillis(): Long
    abstract fun getMetricsBundle(): Bundle
}

class PlaybackMetrics private constructor(
    private val mediaDurationMillis: Long,
    private val streamSource: Int,
    private val streamType: Int,
    private val playbackType: Int,
    private val drmType: Int,
    private val contentType: Int,
    private val playerName: String?,
    private val playerVersion: String?,
    experimentIds: LongArray,
    private val videoFramesPlayed: Int,
    private val videoFramesDropped: Int,
    private val audioUnderrunCount: Int,
    private val networkBytesRead: Long,
    private val localBytesRead: Long,
    private val networkTransferDurationMillis: Long,
    drmSessionId: ByteArray,
    private val metricsBundle: Bundle,
) : Parcelable {
    private val experimentIds = experimentIds.clone()
    private val drmSessionId = drmSessionId.clone()

    fun getMediaDurationMillis() = mediaDurationMillis
    fun getStreamSource() = streamSource
    fun getStreamType() = streamType
    fun getPlaybackType() = playbackType
    fun getDrmType() = drmType
    fun getContentType() = contentType
    fun getPlayerName() = playerName
    fun getPlayerVersion() = playerVersion
    fun getExperimentIds(): LongArray = experimentIds.clone()
    fun getVideoFramesPlayed() = videoFramesPlayed
    fun getVideoFramesDropped() = videoFramesDropped
    fun getAudioUnderrunCount() = audioUnderrunCount
    fun getNetworkBytesRead() = networkBytesRead
    fun getLocalBytesRead() = localBytesRead
    fun getNetworkTransferDurationMillis() = networkTransferDurationMillis
    fun getDrmSessionId(): ByteArray = drmSessionId.clone()
    fun getMetricsBundle() = Bundle(metricsBundle)

    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    class Builder {
        private var mediaDurationMillis = -1L
        private var streamSource = STREAM_SOURCE_UNKNOWN
        private var streamType = STREAM_TYPE_UNKNOWN
        private var playbackType = PLAYBACK_TYPE_UNKNOWN
        private var drmType = DRM_TYPE_NONE
        private var contentType = CONTENT_TYPE_UNKNOWN
        private var playerName: String? = null
        private var playerVersion: String? = null
        private val experimentIds = ArrayList<Long>()
        private var videoFramesPlayed = 0
        private var videoFramesDropped = 0
        private var audioUnderrunCount = 0
        private var networkBytesRead = 0L
        private var localBytesRead = 0L
        private var networkTransferDurationMillis = 0L
        private var drmSessionId = ByteArray(0)
        private var metricsBundle = Bundle()

        fun setMediaDurationMillis(value: Long) = apply { mediaDurationMillis = value }
        fun setStreamSource(value: Int) = apply { streamSource = value }
        fun setStreamType(value: Int) = apply { streamType = value }
        fun setPlaybackType(value: Int) = apply { playbackType = value }
        fun setDrmType(value: Int) = apply { drmType = value }
        fun setContentType(value: Int) = apply { contentType = value }
        fun setPlayerName(value: String) = apply { playerName = value }
        fun setPlayerVersion(value: String) = apply { playerVersion = value }
        fun addExperimentId(value: Long) = apply { experimentIds += value }
        fun setVideoFramesPlayed(value: Int) = apply { videoFramesPlayed = value }
        fun setVideoFramesDropped(value: Int) = apply { videoFramesDropped = value }
        fun setAudioUnderrunCount(value: Int) = apply { audioUnderrunCount = value }
        fun setNetworkBytesRead(value: Long) = apply { networkBytesRead = value }
        fun setLocalBytesRead(value: Long) = apply { localBytesRead = value }
        fun setNetworkTransferDurationMillis(value: Long) =
            apply { networkTransferDurationMillis = value }
        fun setDrmSessionId(value: ByteArray) = apply { drmSessionId = value.clone() }
        fun setMetricsBundle(value: Bundle) = apply { metricsBundle = Bundle(value) }

        fun build() = PlaybackMetrics(
            mediaDurationMillis,
            streamSource,
            streamType,
            playbackType,
            drmType,
            contentType,
            playerName,
            playerVersion,
            experimentIds.toLongArray(),
            videoFramesPlayed,
            videoFramesDropped,
            audioUnderrunCount,
            networkBytesRead,
            localBytesRead,
            networkTransferDurationMillis,
            drmSessionId,
            metricsBundle,
        )
    }

    companion object {
        const val CONTENT_TYPE_UNKNOWN = 0
        const val CONTENT_TYPE_MAIN = 1
        const val CONTENT_TYPE_AD = 2
        const val CONTENT_TYPE_OTHER = 3
        const val DRM_TYPE_NONE = 0
        const val DRM_TYPE_OTHER = 1
        const val DRM_TYPE_PLAY_READY = 2
        const val DRM_TYPE_WIDEVINE_L1 = 3
        const val DRM_TYPE_WIDEVINE_L3 = 4
        const val DRM_TYPE_WV_L3_FALLBACK = 5
        const val DRM_TYPE_CLEARKEY = 6
        const val PLAYBACK_TYPE_UNKNOWN = 0
        const val PLAYBACK_TYPE_VOD = 1
        const val PLAYBACK_TYPE_LIVE = 2
        const val PLAYBACK_TYPE_OTHER = 3
        const val STREAM_SOURCE_UNKNOWN = 0
        const val STREAM_SOURCE_NETWORK = 1
        const val STREAM_SOURCE_DEVICE = 2
        const val STREAM_SOURCE_MIXED = 3
        const val STREAM_TYPE_UNKNOWN = 0
        const val STREAM_TYPE_OTHER = 1
        const val STREAM_TYPE_PROGRESSIVE = 2
        const val STREAM_TYPE_DASH = 3
        const val STREAM_TYPE_HLS = 4
        const val STREAM_TYPE_SS = 5

        @JvmField
        val CREATOR: Parcelable.Creator<PlaybackMetrics> = creator { Builder().build() }
    }
}

class PlaybackErrorEvent private constructor(
    private val exception: Exception?,
    private val errorCode: Int,
    private val subErrorCode: Int,
    private val timeSinceCreatedMillis: Long,
    private val metricsBundle: Bundle,
) : Event(), Parcelable {
    fun getErrorCode() = errorCode
    fun getSubErrorCode() = subErrorCode
    override fun getTimeSinceCreatedMillis() = timeSinceCreatedMillis
    override fun getMetricsBundle() = Bundle(metricsBundle)
    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    class Builder {
        private var exception: Exception? = null
        private var errorCode = ERROR_UNKNOWN
        private var subErrorCode = 0
        private var timeSinceCreatedMillis = 0L
        private var metricsBundle = Bundle()
        fun setException(value: Exception) = apply { exception = value }
        fun setErrorCode(value: Int) = apply { errorCode = value }
        fun setSubErrorCode(value: Int) = apply { subErrorCode = value }
        fun setTimeSinceCreatedMillis(value: Long) = apply { timeSinceCreatedMillis = value }
        fun setMetricsBundle(value: Bundle) = apply { metricsBundle = Bundle(value) }
        fun build() = PlaybackErrorEvent(
            exception,
            errorCode,
            subErrorCode,
            timeSinceCreatedMillis,
            metricsBundle,
        )
    }

    companion object {
        const val ERROR_UNKNOWN = 0
        const val ERROR_OTHER = 1
        const val ERROR_RUNTIME = 2
        const val ERROR_IO_NETWORK_UNAVAILABLE = 3
        const val ERROR_IO_NETWORK_CONNECTION_FAILED = 4
        const val ERROR_IO_BAD_HTTP_STATUS = 5
        const val ERROR_IO_DNS_FAILED = 6
        const val ERROR_IO_CONNECTION_TIMEOUT = 7
        const val ERROR_IO_CONNECTION_CLOSED = 8
        const val ERROR_IO_OTHER = 9
        const val ERROR_PARSING_MANIFEST_MALFORMED = 10
        const val ERROR_PARSING_CONTAINER_MALFORMED = 11
        const val ERROR_PARSING_OTHER = 12
        const val ERROR_DECODER_INIT_FAILED = 13
        const val ERROR_DECODING_FAILED = 14
        const val ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 15
        const val ERROR_DECODING_OTHER = 16
        const val ERROR_AUDIO_TRACK_INIT_FAILED = 17
        const val ERROR_AUDIO_TRACK_WRITE_FAILED = 18
        const val ERROR_AUDIO_TRACK_OTHER = 19
        const val ERROR_PLAYER_REMOTE = 20
        const val ERROR_PLAYER_BEHIND_LIVE_WINDOW = 21
        const val ERROR_PLAYER_OTHER = 22
        const val ERROR_DRM_SCHEME_UNSUPPORTED = 23
        const val ERROR_DRM_PROVISIONING_FAILED = 24
        const val ERROR_DRM_LICENSE_ACQUISITION_FAILED = 25
        const val ERROR_DRM_DISALLOWED_OPERATION = 26
        const val ERROR_DRM_SYSTEM_ERROR = 27
        const val ERROR_DRM_CONTENT_ERROR = 28
        const val ERROR_DRM_DEVICE_REVOKED = 29
        const val ERROR_DRM_OTHER = 30
        const val ERROR_IO_FILE_NOT_FOUND = 31
        const val ERROR_IO_NO_PERMISSION = 32
        const val ERROR_PARSING_MANIFEST_UNSUPPORTED = 33
        const val ERROR_PARSING_CONTAINER_UNSUPPORTED = 34
        const val ERROR_DECODING_FORMAT_UNSUPPORTED = 35

        @JvmField
        val CREATOR: Parcelable.Creator<PlaybackErrorEvent> = creator { Builder().build() }
    }
}

class NetworkEvent private constructor(
    private val networkType: Int,
    private val timeSinceCreatedMillis: Long,
    private val metricsBundle: Bundle,
) : Event(), Parcelable {
    fun getNetworkType() = networkType
    override fun getTimeSinceCreatedMillis() = timeSinceCreatedMillis
    override fun getMetricsBundle() = Bundle(metricsBundle)
    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    class Builder {
        private var networkType = NETWORK_TYPE_UNKNOWN
        private var timeSinceCreatedMillis = 0L
        private var metricsBundle = Bundle()
        fun setNetworkType(value: Int) = apply { networkType = value }
        fun setTimeSinceCreatedMillis(value: Long) = apply { timeSinceCreatedMillis = value }
        fun setMetricsBundle(value: Bundle) = apply { metricsBundle = Bundle(value) }
        fun build() = NetworkEvent(networkType, timeSinceCreatedMillis, metricsBundle)
    }

    companion object {
        const val NETWORK_TYPE_UNKNOWN = 0
        const val NETWORK_TYPE_OTHER = 1
        const val NETWORK_TYPE_WIFI = 2
        const val NETWORK_TYPE_ETHERNET = 3
        const val NETWORK_TYPE_2G = 4
        const val NETWORK_TYPE_3G = 5
        const val NETWORK_TYPE_4G = 6
        const val NETWORK_TYPE_5G_NSA = 7
        const val NETWORK_TYPE_5G_SA = 8
        const val NETWORK_TYPE_OFFLINE = 9

        @JvmField
        val CREATOR: Parcelable.Creator<NetworkEvent> = creator { Builder().build() }
    }
}

class PlaybackStateEvent private constructor(
    private val state: Int,
    private val timeSinceCreatedMillis: Long,
    private val metricsBundle: Bundle,
) : Event(), Parcelable {
    fun getState() = state
    override fun getTimeSinceCreatedMillis() = timeSinceCreatedMillis
    override fun getMetricsBundle() = Bundle(metricsBundle)
    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    class Builder {
        private var state = STATE_NOT_STARTED
        private var timeSinceCreatedMillis = 0L
        private var metricsBundle = Bundle()
        fun setState(value: Int) = apply { state = value }
        fun setTimeSinceCreatedMillis(value: Long) = apply { timeSinceCreatedMillis = value }
        fun setMetricsBundle(value: Bundle) = apply { metricsBundle = Bundle(value) }
        fun build() = PlaybackStateEvent(state, timeSinceCreatedMillis, metricsBundle)
    }

    companion object {
        const val STATE_NOT_STARTED = 0
        const val STATE_JOINING_BACKGROUND = 1
        const val STATE_JOINING_FOREGROUND = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_SEEKING = 5
        const val STATE_BUFFERING = 6
        const val STATE_PAUSED_BUFFERING = 7
        const val STATE_SUPPRESSED = 9
        const val STATE_SUPPRESSED_BUFFERING = 10
        const val STATE_ENDED = 11
        const val STATE_STOPPED = 12
        const val STATE_FAILED = 13
        const val STATE_INTERRUPTED_BY_AD = 14
        const val STATE_ABANDONED = 15

        @JvmField
        val CREATOR: Parcelable.Creator<PlaybackStateEvent> = creator { Builder().build() }
    }
}

class TrackChangeEvent private constructor(
    private val trackType: Int,
    private val trackState: Int,
    private val trackChangeReason: Int,
    private val containerMimeType: String?,
    private val sampleMimeType: String?,
    private val codecName: String?,
    private val bitrate: Int,
    private val timeSinceCreatedMillis: Long,
    private val language: String?,
    private val languageRegion: String?,
    private val channelCount: Int,
    private val audioSampleRate: Int,
    private val width: Int,
    private val height: Int,
    private val videoFrameRate: Float,
    private val metricsBundle: Bundle,
) : Event(), Parcelable {
    fun getTrackState() = trackState
    fun getTrackChangeReason() = trackChangeReason
    fun getContainerMimeType() = containerMimeType
    fun getSampleMimeType() = sampleMimeType
    fun getCodecName() = codecName
    fun getBitrate() = bitrate
    override fun getTimeSinceCreatedMillis() = timeSinceCreatedMillis
    fun getTrackType() = trackType
    fun getLanguage() = language
    fun getLanguageRegion() = languageRegion
    fun getChannelCount() = channelCount
    fun getAudioSampleRate() = audioSampleRate
    fun getWidth() = width
    fun getHeight() = height
    fun getVideoFrameRate() = videoFrameRate
    override fun getMetricsBundle() = Bundle(metricsBundle)
    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    class Builder(private val trackType: Int) {
        private var trackState = TRACK_STATE_OFF
        private var trackChangeReason = TRACK_CHANGE_REASON_UNKNOWN
        private var containerMimeType: String? = null
        private var sampleMimeType: String? = null
        private var codecName: String? = null
        private var bitrate = -1
        private var timeSinceCreatedMillis = 0L
        private var language: String? = null
        private var languageRegion: String? = null
        private var channelCount = -1
        private var audioSampleRate = -1
        private var width = -1
        private var height = -1
        private var videoFrameRate = -1f
        private var metricsBundle = Bundle()
        fun setTrackState(value: Int) = apply { trackState = value }
        fun setTrackChangeReason(value: Int) = apply { trackChangeReason = value }
        fun setContainerMimeType(value: String) = apply { containerMimeType = value }
        fun setSampleMimeType(value: String) = apply { sampleMimeType = value }
        fun setCodecName(value: String) = apply { codecName = value }
        fun setBitrate(value: Int) = apply { bitrate = value }
        fun setTimeSinceCreatedMillis(value: Long) = apply { timeSinceCreatedMillis = value }
        fun setLanguage(value: String) = apply { language = value }
        fun setLanguageRegion(value: String) = apply { languageRegion = value }
        fun setChannelCount(value: Int) = apply { channelCount = value }
        fun setAudioSampleRate(value: Int) = apply { audioSampleRate = value }
        fun setWidth(value: Int) = apply { width = value }
        fun setHeight(value: Int) = apply { height = value }
        fun setVideoFrameRate(value: Float) = apply { videoFrameRate = value }
        fun setMetricsBundle(value: Bundle) = apply { metricsBundle = Bundle(value) }
        fun build() = TrackChangeEvent(
            trackType,
            trackState,
            trackChangeReason,
            containerMimeType,
            sampleMimeType,
            codecName,
            bitrate,
            timeSinceCreatedMillis,
            language,
            languageRegion,
            channelCount,
            audioSampleRate,
            width,
            height,
            videoFrameRate,
            metricsBundle,
        )
    }

    companion object {
        const val TRACK_CHANGE_REASON_UNKNOWN = 0
        const val TRACK_CHANGE_REASON_OTHER = 1
        const val TRACK_CHANGE_REASON_INITIAL = 2
        const val TRACK_CHANGE_REASON_MANUAL = 3
        const val TRACK_CHANGE_REASON_ADAPTIVE = 4
        const val TRACK_STATE_OFF = 0
        const val TRACK_STATE_ON = 1
        const val TRACK_TYPE_AUDIO = 0
        const val TRACK_TYPE_VIDEO = 1
        const val TRACK_TYPE_TEXT = 2

        @JvmField
        val CREATOR: Parcelable.Creator<TrackChangeEvent> = creator { Builder(TRACK_TYPE_AUDIO).build() }
    }
}

private fun <T> creator(defaultValue: () -> T): Parcelable.Creator<T> =
    object : Parcelable.Creator<T> {
        override fun createFromParcel(source: Parcel): T = defaultValue()

        @Suppress("UNCHECKED_CAST")
        override fun newArray(size: Int): Array<T?> = arrayOfNulls<Any?>(size) as Array<T?>
    }
