package dev.artzig.system

import android.media.MediaRoute2Info
import android.media.RoutingSessionInfo

/** The existing desktop audio output, expressed as an AOSP route descriptor. */
object HostedMediaRoutes {
    private const val PROVIDER_ID = "com.android.server.media/.SystemMediaRoute2Provider"
    @JvmField
    val builtInAudio: MediaRoute2Info = MediaRoute2Info.Builder(MediaRoute2Info.ROUTE_ID_DEFAULT, "Built-in audio")
        .setProviderPackageName("com.android.server.media")
        .setProviderId(PROVIDER_ID)
        .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
        .setType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        .setSystemRoute(true)
        .setDescription("Desktop audio output")
        .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
        .setVolumeMax(15)
        .setVolume(10)
        .build()
    fun systemSession(packageName: String?): RoutingSessionInfo =
        RoutingSessionInfo.Builder("SYSTEM_SESSION", packageName ?: "")
            .setProviderId(PROVIDER_ID)
            .setSystemSession(true)
            .addSelectedRoute(MediaRoute2Info.ROUTE_ID_DEFAULT)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
            .setVolumeMax(15)
            .setVolume(10)
            .build()
}
