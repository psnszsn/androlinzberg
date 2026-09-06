package dev.artzig

import android.icu.util.ULocale
import android.media.AudioHalVersionInfo
import android.media.AudioPresentation
import android.media.AudioRoutesInfo
import android.media.IPlayer
import android.media.InterpolatorConfig
import android.media.VolumeShaperConfiguration
import android.media.VolumeShaperConfigurationType
import android.media.VolumeShaperOperation
import android.media.audio.common.AudioChannelLayout
import android.media.audio.common.AudioConfigBase
import android.media.audio.common.AudioDeviceAddress
import android.media.audio.common.AudioFormatDescription
import android.media.audio.common.AudioFormatType
import android.media.audio.common.PcmType
import android.os.BadParcelableException
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import java.util.Locale

/** Stock audio metadata and generated Binder wire contracts; no audio hardware fixture. */
object StockAudioTransportSmoke {
    fun run() {
        val config = AudioConfigBase().apply {
            sampleRate = 48_000
            channelMask = AudioChannelLayout.layoutMask(AudioChannelLayout.LAYOUT_STEREO)
            format = AudioFormatDescription().apply {
                type = AudioFormatType.PCM
                pcm = PcmType.INT_16_BIT
            }
        }
        check(copy(config, AudioConfigBase.CREATOR) == config)
        val address = AudioDeviceAddress.alsa(intArrayOf(3, 7))
        check(copy(address, AudioDeviceAddress.CREATOR).alsa.contentEquals(intArrayOf(3, 7)))
        check(runCatching { address.mac }.exceptionOrNull() is IllegalStateException)
        val invalidTag = Parcel.obtain()
        try {
            invalidTag.writeInt(Int.MAX_VALUE)
            invalidTag.setDataPosition(0)
            check(runCatching { AudioDeviceAddress.CREATOR.createFromParcel(invalidTag) }
                .exceptionOrNull() is IllegalArgumentException)
        } finally {
            invalidTag.recycle()
        }
        val invalidSize = Parcel.obtain()
        try {
            invalidSize.writeInt(3)
            invalidSize.setDataPosition(0)
            check(runCatching { AudioConfigBase.CREATOR.createFromParcel(invalidSize) }
                .exceptionOrNull() is BadParcelableException)
        } finally {
            invalidSize.recycle()
        }

        val version = copy(AudioHalVersionInfo.AIDL_1_0, AudioHalVersionInfo.CREATOR)
        check(version.halType == AudioHalVersionInfo.AUDIO_HAL_TYPE_AIDL)
        check(version.majorVersion == 1 && version.minorVersion == 0)
        check(version.toString() == "AIDL:1.0")
        check(AudioHalVersionInfo.AIDL_1_0 > AudioHalVersionInfo.HIDL_7_1)
        val route = AudioRoutesInfo().apply {
            mainType = AudioRoutesInfo.MAIN_USB
            bluetoothName = "route fixture"
        }
        val routeCopy = copy(route, AudioRoutesInfo.CREATOR)
        check(routeCopy.mainType == route.mainType && routeCopy.bluetoothName == route.bluetoothName)
        check(routeCopy.toString().contains("type=USB"))

        val labels = mutableMapOf<ULocale, CharSequence>(ULocale.ENGLISH to StringBuilder("English"))
        val presentation = AudioPresentation.Builder(7).setProgramId(9)
            .setLocale(ULocale.ENGLISH).setLabels(labels)
            .setMasteringIndication(AudioPresentation.MASTERED_FOR_HEADPHONE)
            .setHasAudioDescription(true).setHasSpokenSubtitles(true)
            .setHasDialogueEnhancement(true).build()
        labels[ULocale.ENGLISH] = "changed"
        val presentationCopy = copy(presentation, AudioPresentation.CREATOR)
        check(presentationCopy == presentation && presentationCopy.hashCode() == presentation.hashCode())
        check(presentationCopy.presentationId == 7 && presentationCopy.programId == 9)
        check(presentationCopy.locale == Locale.ENGLISH)
        check(presentationCopy.labels[Locale.ENGLISH] == "English")
        check(presentationCopy.hasAudioDescription() && presentationCopy.hasSpokenSubtitles()
            && presentationCopy.hasDialogueEnhancement())
        check(runCatching { AudioPresentation.Builder(1).setMasteringIndication(-1) }
            .exceptionOrNull() is IllegalArgumentException)
        playerProtocol()
    }

    private fun playerProtocol() {
        val calls = mutableListOf<String>()
        val receiver = object : IPlayer.Stub() {
            override fun start() { calls += "start" }
            override fun pause() { calls += "pause" }
            override fun stop() { calls += "stop" }
            override fun setVolume(volume: Float) { calls += "volume:$volume" }
            override fun setPan(pan: Float) { calls += "pan:$pan" }
            override fun setStartDelayMs(delayMs: Int) { calls += "delay:$delayMs" }
            override fun applyVolumeShaper(configuration: VolumeShaperConfiguration,
                operation: VolumeShaperOperation) {
                check(configuration.type == VolumeShaperConfigurationType.SCALE)
                check(configuration.durationMs == 1500.0)
                check(configuration.interpolatorConfig.xy.contentEquals(floatArrayOf(0f, 0f, 1f, 1f)))
                check(operation.replaceId == 4 && operation.xOffset == 0.25f)
                calls += "shape"
            }
        }
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                receiver.asBinder().transact(code, data, reply, flags)
        }
        val player = IPlayer.Stub.asInterface(remote as IBinder)
        check(player !== receiver)
        player.start()
        player.setVolume(0.5f)
        player.setPan(-0.25f)
        player.setStartDelayMs(12)
        player.applyVolumeShaper(VolumeShaperConfiguration().apply {
            type = VolumeShaperConfigurationType.SCALE
            durationMs = 1500.0
            interpolatorConfig = InterpolatorConfig().apply { xy = floatArrayOf(0f, 0f, 1f, 1f) }
        }, VolumeShaperOperation().apply { replaceId = 4; xOffset = 0.25f })
        player.pause()
        player.stop()
        check(calls == listOf("start", "volume:0.5", "pan:-0.25", "delay:12", "shape", "pause", "stop"))
    }

    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
