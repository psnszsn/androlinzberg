package dev.artzig.system

import android.media.MediaRoute2Info

/** The existing desktop audio output, expressed as an AOSP route descriptor. */
object HostedMediaRoutes {
    @JvmField
    val builtInAudio: MediaRoute2Info = MediaRoute2Info.Builder("artzig-built-in-audio", "Built-in audio")
        .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
        .setType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        .setSystemRoute(true)
        .setDescription("Desktop audio output")
        .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
        .setVolumeMax(100)
        .setVolume(100)
        .build()
}
