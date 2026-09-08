package dev.artzig

import android.app.ActivityThread
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.*
import android.os.Binder
import android.os.PermissionEnforcer
import dev.artzig.system.HostedAudioService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

internal object StockAudioManagerSmoke {
    private external fun nativePermission(permission: String, pid: Int, uid: Int): Boolean

    fun run(drain: () -> Unit) {
        val context = ActivityThread.currentApplication() ?: ActivityThread.currentActivityThread().systemContext
        // Original native PermissionCache/IPermissionController reach the published Java service.
        check(nativePermission(android.Manifest.permission.DUMP, -1, 0))
        check(!nativePermission(android.Manifest.permission.DUMP, -1, 1_000_000_000))
        val permission = android.os.IPermissionController.Stub.asInterface(android.os.ServiceManager.getService("permission"))
        check(permission.isRuntimePermission(android.Manifest.permission.CAMERA))
        check(!permission.isRuntimePermission(android.Manifest.permission.INTERNET))
        check(permission.getPackageUid("dev.artzig.no.such.package", 0) == -1)
        val privileged = object : ContextWrapper(context) {
            override fun checkCallingOrSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        }
        val service = HostedAudioService(PermissionEnforcer.fromContext(privileged), privileged)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val events = ArrayList<Pair<String, Int>>()
        val dispatcher = object : IAudioFocusDispatcher.Stub() {
            override fun dispatchAudioFocusChange(change: Int, client: String) { events.add(client to change) }
            override fun dispatchFocusResultFromExtPolicy(result: Int, client: String) = error("No external policy")
        }
        val first = Binder()
        val second = Binder()
        fun request(client: String, gain: Int, token: Binder, flags: Int = 0) = service.requestAudioFocus(
            attributes, gain, token, dispatcher, client, context.packageName, null, flags, null, 35)
        check(request("first", AudioManager.AUDIOFOCUS_GAIN, first) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events.isEmpty())
        check(request("second", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events == listOf("first" to AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { events }
        check(service.getCurrentAudioFocus() == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        check(service.abandonAudioFocus(dispatcher, "second", attributes, context.packageName) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events.last() == "first" to AudioManager.AUDIOFOCUS_GAIN)
        check(request("second", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events.last() == "first" to AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        service.abandonAudioFocus(dispatcher, "second", attributes, context.packageName)
        check(request("second", AudioManager.AUDIOFOCUS_GAIN, second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(events.last() == "first" to AudioManager.AUDIOFOCUS_LOSS)
        service.abandonAudioFocus(dispatcher, "second", attributes, context.packageName)
        check(service.getCurrentAudioFocus() == AudioManager.AUDIOFOCUS_NONE)
        check(request("invalid", AudioManager.AUDIOFOCUS_GAIN, Binder(), AudioManager.AUDIOFOCUS_FLAG_LOCK) == AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        check(runCatching { request("test", AudioManager.AUDIOFOCUS_GAIN, Binder(), AudioManager.AUDIOFOCUS_FLAG_TEST) }.exceptionOrNull() is IllegalArgumentException)

        // Exercise the stock client and published service together, including its real Handler dispatcher.
        val audio = context.getSystemService(AudioManager::class.java)!!
        check(audio.isVolumeFixed)
        val callbackEvents = ArrayList<Int>()
        val firstRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { callbackEvents.add(it) }.build()
        val secondRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener {}.build()
        check(audio.requestAudioFocus(firstRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        check(audio.requestAudioFocus(secondRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        repeat(10) { drain(); if (callbackEvents.isEmpty()) Thread.sleep(10) }
        check(callbackEvents == listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { callbackEvents }
        audio.abandonAudioFocusRequest(secondRequest)
        repeat(10) { drain(); if (callbackEvents.size < 2) Thread.sleep(10) }
        check(callbackEvents == listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN)) { callbackEvents }
        audio.abandonAudioFocusRequest(firstRequest)
        testCryptoValues()
        testWave()
        testFramedAudio()
        println("stock-audio-manager: PASS focus ordering, duck fallback, stock Binder client, crypto values and WAV/MP3/AAC samples")
    }

    private fun testCryptoValues() {
        val value = MediaCodec.CryptoInfo()
        val clear = intArrayOf(3)
        val encrypted = intArrayOf(7)
        val key = ByteArray(16) { it.toByte() }
        value.set(1, clear, encrypted, key, ByteArray(16), MediaCodec.CRYPTO_MODE_AES_CTR)
        check(value.numBytesOfClearData === clear && value.numBytesOfEncryptedData === encrypted && value.key === key)
        val pattern = MediaCodec.CryptoInfo.Pattern(2, 5)
        value.setPattern(pattern)
        pattern.set(9, 9)
        check(value.pattern.encryptBlocks == 2 && value.pattern.skipBlocks == 5)
        value.pattern.set(8, 8)
        check(value.pattern.encryptBlocks == 2)
        value.setPattern(null)
        check(value.pattern.encryptBlocks == 0 && value.pattern.skipBlocks == 0)
        val error = MediaCodec.CryptoException(MediaCodec.CryptoException.ERROR_NO_KEY, "missing key")
        check(error.errorCode == MediaCodec.CryptoException.ERROR_NO_KEY && error.message == "missing key")
    }

    private fun testFramedAudio() {
        // Synthetic framing fixtures exercise extraction, not compressed-audio decoding.
        val mp3 = ByteArray(417).also {
            byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(it)
        }
        val aac = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x3f, 0xfc.toByte(), 0, 0)
        for ((frame, mime, payload) in listOf(
            Triple(mp3, "audio/mpeg", mp3),
            Triple(aac, "audio/mp4a-latm", byteArrayOf(0, 0)),
        )) {
            val path = Files.createTempFile("stock-extractor-frames-", ".bin")
            try {
                Files.write(path, ByteArray(frame.size * 8) { frame[it % frame.size] })
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(path.toString())
                    check(extractor.trackCount == 1)
                    val format = extractor.getTrackFormat(0)
                    check(format.getString(MediaFormat.KEY_MIME) == mime) { format }
                    check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 44100) { format }
                    check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) { format }
                    extractor.selectTrack(0)
                    val sample = ByteBuffer.allocateDirect(1024)
                    check(extractor.readSampleData(sample, 0) == payload.size) { mime }
                    val observed = ByteArray(payload.size)
                    sample.position(0); sample.get(observed)
                    check(observed.contentEquals(payload)) { mime }
                    check(extractor.sampleTime == 0L)
                    check(extractor.advance() && extractor.sampleTime > 0L)
                } finally { extractor.release() }
            } finally { Files.deleteIfExists(path) }
        }
    }

    private fun testWave() {
        val pcm = byteArrayOf(0, 0, 1, 0, -1, 127, 0, -128)
        val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        val path = Files.createTempFile("stock-extractor-", ".wav")
        try {
            Files.write(path, bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path.toString())
                check(extractor.trackCount == 1)
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == "audio/raw") { format }
                check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 8000)
                check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                extractor.selectTrack(0)
                val sample = ByteBuffer.allocateDirect(64)
                check(extractor.readSampleData(sample, 0) == pcm.size)
                val observed = ByteArray(pcm.size); sample.position(0); sample.get(observed)
                check(observed.contentEquals(pcm)) { observed.contentToString() }
                check(extractor.sampleTime == 0L && extractor.sampleTrackIndex == 0)
                check(!extractor.advance())
                check(extractor.sampleTime == -1L)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                check(extractor.readSampleData(sample, 0) == pcm.size)
            } finally { extractor.release() }
            Files.write(path, byteArrayOf(1, 2, 3))
            val invalid = MediaExtractor()
            try { check(runCatching { invalid.setDataSource(path.toString()) }.exceptionOrNull() is java.io.IOException) }
            finally { invalid.release() }
        } finally { Files.deleteIfExists(path) }
    }
}
