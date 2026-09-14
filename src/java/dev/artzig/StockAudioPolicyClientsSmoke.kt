package dev.artzig

import android.content.AttributionSource
import android.media.*
import android.os.Bundle
import android.os.PermissionEnforcer
import android.os.PersistableBundle
import android.permission.PermissionCheckerManager
import dev.artzig.system.HostedAudioService
import java.nio.ByteBuffer
import java.util.concurrent.Executor

internal object StockAudioPolicyClientsSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull().let {
            if (it is java.lang.reflect.InvocationTargetException) it.targetException else it
        }
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
    }

    private fun enforcer(granted: Boolean) = object : PermissionEnforcer() {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (granted) PermissionCheckerManager.PERMISSION_GRANTED else PermissionCheckerManager.PERMISSION_HARD_DENIED
        override fun checkPermission(permission: String, source: AttributionSource): Int =
            checkPermission(permission, 0, source.uid)
    }

    fun run() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0)
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        val spatializer = audio.spatializer
        check(!spatializer.isEnabled && !spatializer.isAvailable && !spatializer.isHeadTrackerAvailable)
        check(spatializer.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE)
        check(!spatializer.canBeSpatialized(AudioAttributes.Builder().build(),
            AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()))
        rejects<NullPointerException> {
            Spatializer::class.java.getMethod("canBeSpatialized", AudioAttributes::class.java,
                AudioFormat::class.java).invoke(spatializer, null, AudioFormat.Builder().build())
        }
        val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = error("Absent effect changed")
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) = error("Absent effect changed")
        }
        spatializer.addOnSpatializerStateChangedListener(Executor(Runnable::run), listener)
        rejects<IllegalArgumentException> { spatializer.addOnSpatializerStateChangedListener(Executor(Runnable::run), listener) }
        spatializer.removeOnSpatializerStateChangedListener(listener)
        rejects<IllegalArgumentException> { spatializer.removeOnSpatializerStateChangedListener(listener) }
        rejects<NullPointerException> {
            Spatializer::class.java.getMethod("addOnSpatializerStateChangedListener", Executor::class.java,
                Spatializer.OnSpatializerStateChangedListener::class.java).invoke(spatializer, null, listener)
        }

        val policy = HostedAudioService(enforcer(true))
        policy.setSpatializerEnabled(true)
        check(!policy.isSpatializerEnabled() && !policy.isSpatializerAvailable())
        check(policy.getSupportedHeadTrackingModes().isEmpty())
        check(policy.getActualHeadTrackingMode() == Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED)
        rejects<IllegalStateException> { policy.getSpatializerOutput() }
        rejects<IllegalStateException> { policy.getSpatializerParameter(1, byteArrayOf(7)) }
        rejects<IllegalStateException> { policy.setSpatializerParameter(1, byteArrayOf(7)) }
        rejects<IllegalArgumentException> { policy.setSpatializerGlobalTransform(FloatArray(5)) }
        policy.setSpatializerGlobalTransform(FloatArray(6))
        rejects<SecurityException> { HostedAudioService(enforcer(false)).setSpatializerEnabled(true) }
        check(!HostedAudioService(enforcer(false)).isSpatializerAvailable())

        testLoudnessSessions(policy)
        testCodecFormatsAndLoudness()
        println("stock audio policy clients: PASS")
    }

    private fun testLoudnessSessions(policy: HostedAudioService) {
        val updates = ArrayList<Pair<Int, PersistableBundle>>()
        val dispatcher = object : ILoudnessCodecUpdatesDispatcher.Stub() {
            override fun dispatchLoudnessCodecParameterChange(sessionId: Int, params: PersistableBundle) {
                updates += sessionId to PersistableBundle(params)
            }
        }
        val info = LoudnessCodecInfo().apply { metadataType = LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_4 }
        policy.registerLoudnessCodecUpdatesDispatcher(dispatcher)
        policy.addLoudnessCodecInfo(123, 91, info)
        check(updates.isEmpty()) // A stopped session cannot receive an initial update.
        policy.startLoudnessCodecUpdates(123)
        policy.startLoudnessCodecUpdates(123) // Duplicate starts preserve the active session.
        policy.addLoudnessCodecInfo(123, 91, info)
        check(updates.single().first == 123)
        check(updates.single().second.getPersistableBundle("91")!!.isEmpty)
        policy.stopLoudnessCodecUpdates(123)
        policy.addLoudnessCodecInfo(123, 92, info)
        check(updates.size == 1)
        policy.startLoudnessCodecUpdates(123)
        policy.addLoudnessCodecInfo(123, 93, info)
        check(updates.size == 2 && updates.last().second.containsKey("93"))
        policy.stopLoudnessCodecUpdates(123)
        policy.unregisterLoudnessCodecUpdatesDispatcher(dispatcher)
    }

    private fun testCodecFormatsAndLoudness() {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val updates = ArrayList<MediaCodec>()
        val controller = LoudnessCodecController.create(321, Executor { queue.add(it) },
            object : LoudnessCodecController.OnLoudnessCodecUpdateListener {
                override fun onLoudnessCodecUpdate(codec: MediaCodec, parameters: Bundle): Bundle {
                    check(parameters.isEmpty)
                    updates += codec
                    return parameters
                }
            })
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            rejects<NullPointerException> {
                LoudnessCodecController::class.java.getMethod("addMediaCodec", MediaCodec::class.java)
                    .invoke(controller, null)
            }
            check(!controller.addMediaCodec(codec))
            rejects<IllegalArgumentException> { controller.removeMediaCodec(codec) }
            val configured = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)
            configured.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            configured.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte())))
            codec.configure(configured, null, null, 0)
            configured.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectXHE)
            configured.getByteBuffer("csd-0")!!.put(0, 9)
            check(codec.inputFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
            check(codec.outputFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW)
            // Original CCodec exposes decoder profiles under KEY_PROFILE; AAC_PROFILE is encoder input.
            check(codec.inputFormat.getInteger(MediaFormat.KEY_PROFILE) == MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            // CCodec consumes codec-specific data; its negotiated input format need not echo it.
            codec.inputFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 8)
            check(codec.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
            check(controller.addMediaCodec(codec))
            rejects<IllegalArgumentException> { controller.addMediaCodec(codec) }
            check(controller.getLoudnessCodecParams(codec).isEmpty)
            // Binder callbacks can arrive asynchronously; only the supplied executor runs user code.
            val deadline = android.os.SystemClock.uptimeMillis() + 2000
            while (queue.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(1)
            check(updates.isEmpty() && queue.isNotEmpty())
            while (queue.isNotEmpty()) queue.poll()?.run()
            check(updates == listOf(codec))
            controller.removeMediaCodec(codec)
            rejects<IllegalArgumentException> { controller.removeMediaCodec(codec) }
            rejects<IllegalArgumentException> { controller.getLoudnessCodecParams(codec) }
            check(controller.addMediaCodec(codec))
            controller.close()
            while (queue.isNotEmpty()) queue.poll()?.run()
            rejects<IllegalArgumentException> { controller.getLoudnessCodecParams(codec) }
            codec.stop()
            rejects<IllegalStateException> { codec.inputFormat }
            rejects<IllegalStateException> { codec.outputFormat }
        } finally {
            controller.close()
            codec.release()
        }
    }
}
