package dev.artzig

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.lang.reflect.InvocationTargetException

/** Public capability decisions over the stock service-discovered catalogue. */
internal object StockCodecCatalogSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        val failure = if (thrown is InvocationTargetException) thrown.cause else thrown
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
    }

    fun run() {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val infos = list.codecInfos
        check(infos.count { it.supportedTypes.single().startsWith("audio/") } == 5)
        check(infos.size in 5..8)
        check(MediaCodecList.getCodecCount() == infos.size)
        check(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { it.name } == infos.map { it.name })
        for ((index, info) in infos.withIndex()) {
            check(MediaCodecList.getCodecInfoAt(index) === info)
            check(MediaCodecList.getInfoFor(info.name) === info)
            check(info.name == info.canonicalName && !info.isAlias && !info.isEncoder)
            val type = info.supportedTypes.single()
            val isVideo = type.startsWith("video/")
            check(info.isSoftwareOnly != isVideo && info.isHardwareAccelerated == isVideo)
            val returnedTypes = info.supportedTypes
            returnedTypes[0] = "invalid/type"
            check(info.supportedTypes.single() == type)
            val caps = info.getCapabilitiesForType(type)
            if (isVideo) check(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats)
            else check(caps.colorFormats.isEmpty())
            check(caps.mimeType == type && caps.defaultFormat.getString(MediaFormat.KEY_MIME) == type)
            check(caps.maxSupportedInstances == 32 && caps.encoderCapabilities == null)
            check(!caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback))
            check(!caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback))
            rejects<IllegalArgumentException> { info.getCapabilitiesForType("invalid/type") }
            val mimeOnly = MediaFormat().apply { setString(MediaFormat.KEY_MIME, type) }
            check(list.findDecoderForFormat(mimeOnly) == info.name)
            check(list.findEncoderForFormat(mimeOnly) == null)
            val codec = MediaCodec.createByCodecName(info.name)
            try {
                // Original getOwnCodecInfo constructs a fresh Java value.
                val own = codec.codecInfo
                check(own.name == info.name && own.canonicalName == info.canonicalName)
                check(own.supportedTypes.contentEquals(info.supportedTypes))
            } finally { codec.release() }
        }
        val firstName = infos.first().name
        infos[0] = infos.last()
        check(list.codecInfos.first().name == firstName)

        val aac = MediaCodecList.getInfoFor("c2.artzig.aac.decoder")
            .getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
        check(aac.videoCapabilities == null)
        val audio = checkNotNull(aac.audioCapabilities)
        check(audio.isSampleRateSupported(48000) && !audio.isSampleRateSupported(12345))
        check(audio.maxInputChannelCount == 8)
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
        check(aac.isFormatSupported(audioFormat))
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 9)
        check(!aac.isFormatSupported(audioFormat) && list.findDecoderForFormat(audioFormat) == null)
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 12345)
        check(!aac.isFormatSupported(audioFormat))
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
        audioFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectXHE)
        check(!aac.isFormatSupported(audioFormat))

        val avcInfo = MediaCodecList.getInfoFor("c2.artzig.avc.decoder")
        val avc = avcInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val video = checkNotNull(avc.videoCapabilities)
        check(avc.audioCapabilities == null)
        check(video.widthAlignment == 2 && video.heightAlignment == 2)
        check(video.isSizeSupported(1920, 1080))
        check(!video.isSizeSupported(1919, 1080) && !video.isSizeSupported(-2, 1080))
        check(!video.isSizeSupported(8192, 8192))
        rejects<IllegalArgumentException> { video.getSupportedFrameRatesFor(1919, 1080) }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        check(avc.isFormatSupported(format) && list.findDecoderForFormat(format) == avcInfo.name)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10)
        check(!avc.isFormatSupported(format) && list.findDecoderForFormat(format) == null)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel6)
        check(!avc.isFormatSupported(format))
        format.removeKey(MediaFormat.KEY_LEVEL)
        format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback, true)
        check(!avc.isFormatSupported(format) && list.findDecoderForFormat(format) == null)
        format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback, false)
        check(avc.isFormatSupported(format))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 1000)
        check(!avc.isFormatSupported(format))
        avc.colorFormats[0] = -1
        avc.profileLevels[0] = MediaCodecInfo.CodecProfileLevel()
        val next = avcInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        check(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in next.colorFormats)
        check(next.profileLevels[0].profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)

        rejects<IllegalArgumentException> { MediaCodecList.getCodecInfoAt(-1) }
        // Preserve the pinned Java boundary check, including its index == length behavior.
        rejects<ArrayIndexOutOfBoundsException> { MediaCodecList.getCodecInfoAt(infos.size) }
        rejects<IllegalArgumentException> { MediaCodecList.getInfoFor(null) }
        rejects<ArrayIndexOutOfBoundsException> { MediaCodecList.getInfoFor("missing.codec") }
        val nativeCaps = MediaCodecList::class.java.getDeclaredMethod(
            "getCodecCapabilities", Int::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }
        rejects<IllegalArgumentException> { nativeCaps.invoke(null, 0, null) }
        rejects<IllegalArgumentException> { nativeCaps.invoke(null, -1, MediaFormat.MIMETYPE_AUDIO_AAC) }
        rejects<IllegalArgumentException> { nativeCaps.invoke(null, 0, "invalid/type") }
        println("stock codec catalogue, capabilities and format selection: PASS")
    }
}
