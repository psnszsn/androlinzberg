@file:Suppress("DEPRECATION")
package dev.artzig

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Camera
import android.media.AudioAttributes
import android.media.AudioDescriptor
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPatch
import android.media.AudioPort
import android.media.AudioProfile
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioSystem
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audio.common.AidlConversion
import android.media.audio.common.AudioEncapsulationMode
import android.media.audio.common.AudioStreamType
import android.media.audio.common.AudioUsage
import android.os.Parcel
import android.os.Parcelable
import android.os.Process

/** Public stock format contracts and the fixed-output/absent-recorder native boundary. */
internal object StockAudioFoundationSmoke {
    fun run() {
        val unspecified = AudioFormat.Builder().build()
        check(unspecified.encoding == AudioFormat.ENCODING_INVALID)
        check(unspecified.sampleRate == 0 && unspecified.channelCount == 0)
        check(unspecified.propertySetMask == AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_NONE)
        check(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_DEFAULT).build().encoding ==
            AudioFormat.ENCODING_PCM_16BIT)
        for (rate in listOf(AudioFormat.SAMPLE_RATE_HZ_MIN, AudioFormat.SAMPLE_RATE_HZ_MAX, 0)) {
            check(AudioFormat.Builder().setSampleRate(rate).build().sampleRate == rate)
        }
        for (rate in listOf(AudioFormat.SAMPLE_RATE_HZ_MIN - 1, AudioFormat.SAMPLE_RATE_HZ_MAX + 1)) {
            check(runCatching { AudioFormat.Builder().setSampleRate(rate) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { AudioFormat.Builder().setEncoding(Int.MAX_VALUE) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { AudioFormat.Builder().setChannelIndexMask(0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setChannelIndexMask(1)
        }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            AudioFormat.Builder().setChannelIndexMask(1).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        }.exceptionOrNull() is IllegalArgumentException)
        val format = AudioFormat.Builder().setSampleRate(48_000)
            .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setChannelIndexMask(0b1010).build()
        val restored = copy(format, AudioFormat.CREATOR)
        check(restored == format && restored.hashCode() == format.hashCode())
        check(restored.channelIndexMask == 0b1010 && restored.channelCount == 2)
        check(restored.frameSizeInBytes == 6)
        check(AudioFormat.Builder(format).build() == format)
        check(AudioFormat.toLogFriendlyEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED).contains("24"))
        check(AudioSystem.OUT_CHANNEL_COUNT_MAX >= 2)

        val descriptor = AudioDescriptor(AudioDescriptor.STANDARD_EDID,
            AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE, byteArrayOf(1, 2, 3))
        check(copy(descriptor, AudioDescriptor.CREATOR) == descriptor)
        val profile = AudioProfile(AudioFormat.ENCODING_PCM_16BIT, intArrayOf(48_000),
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO), intArrayOf(3),
            AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
        check(copy(profile, AudioProfile.CREATOR) == profile)
        check(profile.toString().contains("48000"))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).addTag("metadata fixture").build()
        check(copy(attributes, AudioAttributes.CREATOR) == attributes)
        check(attributes.toString().contains("USAGE_MEDIA"))
        check(runCatching {
            AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_ACCESSIBILITY)
        }.exceptionOrNull() is IllegalArgumentException)
        val defaultTrack = AudioTrack.Builder().build()
        try {
            check(defaultTrack.sampleRate == 48_000)
            check(defaultTrack.format.encoding == AudioFormat.ENCODING_PCM_16BIT)
            check(defaultTrack.channelCount == 2)
        } finally { defaultTrack.release() }
        enumConversions()
        outputDevices()
        absentRecording()
    }

    private fun enumConversions() {
        fun checkEnum(type: Class<*>, toNative: (Int) -> Int, fromNative: (Int) -> Int) {
            for (field in type.fields.filter { it.type == Int::class.javaPrimitiveType && it.name != "INVALID" }) {
                val value = field.getInt(null)
                check(fromNative(toNative(value)) == value)
            }
            for (convert in listOf(toNative, fromNative)) {
                val failure = runCatching { convert(Int.MAX_VALUE) }.exceptionOrNull()
                check(failure is IllegalArgumentException && failure.message!!.startsWith("Failed to convert "))
            }
        }
        checkEnum(AudioEncapsulationMode::class.java,
            AidlConversion::aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t,
            AidlConversion::legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode)
        checkEnum(AudioStreamType::class.java,
            AidlConversion::aidl2legacy_AudioStreamType_audio_stream_type_t,
            AidlConversion::legacy2aidl_audio_stream_type_t_AudioStreamType)
        checkEnum(AudioUsage::class.java, AidlConversion::aidl2legacy_AudioUsage_audio_usage_t,
            AidlConversion::legacy2aidl_audio_usage_t_AudioUsage)
        check(AidlConversion.aidl2legacy_AudioStreamType_audio_stream_type_t(AudioStreamType.MUSIC) == AudioSystem.STREAM_MUSIC)
        check(AidlConversion.aidl2legacy_AudioUsage_audio_usage_t(AudioUsage.MEDIA) == AudioAttributes.USAGE_MEDIA)
    }

    private fun outputDevices() {
        val devices = AudioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        check(devices.size == 1)
        val device = devices.single()
        check(device.id > 0 && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        check(device.productName.toString() == "Speaker" && device.address.isEmpty())
        check(device.isSink && !device.isSource)
        // Stock AudioPort merges the profiles' values into sets; compare without order.
        check(device.sampleRates.sortedArray().contentEquals(intArrayOf(44_100, 48_000))) { device.sampleRates.contentToString() }
        check(device.channelCounts.sortedArray().contentEquals(intArrayOf(1, 2))) { device.channelCounts.contentToString() }
        check(device.channelMasks.sortedArray().contentEquals(intArrayOf(AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_OUT_STEREO))) { device.channelMasks.contentToString() }
        check(device.encodings.sortedArray().contentEquals(intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))) { device.encodings.contentToString() }
        check(AudioManager().getDevices(AudioManager.GET_DEVICES_INPUTS).isEmpty())
        check(AudioManager.getDeviceForPortId(device.id, AudioManager.GET_DEVICES_OUTPUTS) != null)
        check(AudioManager.getDeviceForPortId(device.id, AudioManager.GET_DEVICES_INPUTS) == null)
        val ports = arrayListOf<AudioPort>()
        val generation = IntArray(1)
        // The speaker device port and the primary mix port, joined by the output's patch.
        check(AudioSystem.listAudioPorts(ports, generation) == AudioManager.SUCCESS)
        check(ports.size == 2 && generation[0] != 0) { "ports ${ports.size}" }
        check(ports.any { it.role() == AudioPort.ROLE_SINK } && ports.any { it.role() == AudioPort.ROLE_SOURCE })
        val patches = arrayListOf<AudioPatch>()
        val patchGeneration = IntArray(1)
        check(AudioSystem.listAudioPatches(patches, patchGeneration) == AudioManager.SUCCESS)
        check(patches.size == 1 && patchGeneration[0] == generation[0]) { "patches ${patches.size}" }
        check(patches.single().sinks().single().port().id() == device.id)
        check(AudioSystem.listAudioPorts(null, generation) == AudioManager.ERROR_BAD_VALUE)
        check(AudioSystem.listAudioPatches(patches, IntArray(2)) == AudioManager.ERROR_BAD_VALUE)
    }

    private fun absentRecording() {
        check(Camera.getNumberOfCameras() == 0)
        val unknownCamera = runCatching { Camera.getCameraInfo(0, Camera.CameraInfo()) }.exceptionOrNull()
        check(unknownCamera is RuntimeException && unknownCamera.message == "Unknown camera ID")
        check(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH) == null)
        check(!CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_HIGH))
        check(AudioEffect.queryEffects() == null)
        // AudioFlinger reports no input buffer: stock AudioRecord maps that to ERROR_BAD_VALUE.
        check(AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT) == AudioRecord.ERROR_BAD_VALUE)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, 48_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096)
        try {
            check(record.state == AudioRecord.STATE_UNINITIALIZED)
            check(record.recordingState == AudioRecord.RECORDSTATE_STOPPED)
            check(runCatching { record.startRecording() }.exceptionOrNull() is IllegalStateException)
        } finally {
            record.release()
        }
        val context = object : ContextWrapper(null) {
            override fun getPackageName() = "dev.artzig.audio-test"
            override fun getApplicationContext(): Context = this
            override fun getAttributionSource() = AttributionSource.Builder(Process.myUid())
                .setPackageName(packageName).build()
        }
        val recorderFailure = runCatching { MediaRecorder(context) }.exceptionOrNull()
        check(recorderFailure is RuntimeException && recorderFailure.message == "Unable to initialize media recorder")
    }

    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
