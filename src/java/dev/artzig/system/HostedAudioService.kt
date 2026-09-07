package dev.artzig.system

import android.media.*
import android.os.Binder
import android.os.IBinder
import android.os.PermissionEnforcer
import android.os.PersistableBundle
import android.os.RemoteCallbackList
import android.os.RemoteException
import java.util.Objects

/** AudioService's capability and codec-session boundary.
 * Source: pinned AudioService, SpatializerHelper.init(false)/STATE_NOT_SUPPORTED and
 * LoudnessCodecHelper. The host has no native spatializer or AudioPolicy device loudness
 * model. Preserve their absent-effect/absent-route results, listener ownership and session
 * transitions here; decoding and PCM presentation remain in the existing native backend. */
internal class HostedAudioService(enforcer: PermissionEnforcer) : IAudioService.Stub(enforcer),
    IAudioService by unsupported<IAudioService>() {
    override fun asBinder(): IBinder = this
    // AudioService.readCameraSoundForced: the host has no active SIM subscriptions.
    // Read the current host resource/property policy when legacy Camera asks for it.
    override fun isCameraSoundForced(): Boolean =
        android.os.SystemProperties.getBoolean("audio.camerasound.force", false) ||
            android.content.res.Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_camera_sound_forced)

    // AudioDeviceInventory.startWatchingRoutes: a snapshot plus listener ownership. There is
    // one speaker output, no Bluetooth route, and no framework master gain on this backend.
    private val routeCallbacks = RemoteCallbackList<IAudioRoutesObserver>()
    override fun startWatchingRoutes(observer: IAudioRoutesObserver?): AudioRoutesInfo {
        routeCallbacks.register(nonNull(observer))
        return AudioRoutesInfo()
    }
    override fun isBluetoothA2dpOn(): Boolean = false
    override fun getStreamVolume(streamType: Int): Int { require(streamType in 0..11); return 10 }
    override fun getStreamMaxVolume(streamType: Int): Int { require(streamType in 0..11); return 15 }
    override fun setStreamVolumeWithAttribution(streamType: Int, index: Int, flags: Int, callingPackage: String?, attributionTag: String?) {
        // AudioService.setStreamVolume returns before validation when mUseFixedVolume is true.
    }
    private val stateCallbacks = RemoteCallbackList<ISpatializerCallback>()
    private val trackingCallbacks = RemoteCallbackList<ISpatializerHeadTrackingModeCallback>()
    private val trackerCallbacks = RemoteCallbackList<ISpatializerHeadTrackerAvailableCallback>()
    private val poseCallbacks = RemoteCallbackList<ISpatializerHeadToSoundStagePoseCallback>()
    private val outputCallbacks = RemoteCallbackList<ISpatializerOutputCallback>()
    private val lock = Any()
    private val dispatcherLock = Any()
    private data class Session(val pid: Int, val id: Int)
    private val sessions = HashMap<Session, MutableSet<LoudnessCodecInfo?>>()
    private val loudnessCallbacks = object : RemoteCallbackList<ILoudnessCodecUpdatesDispatcher>() {
        override fun onCallbackDied(callback: ILoudnessCodecUpdatesDispatcher, cookie: Any?) {
            synchronized(lock) { sessions.keys.removeAll { it.pid == cookie } }
        }
    }

    private fun <T : Any> nonNull(value: T?): T = Objects.requireNonNull(value)!!
    override fun getSpatializerImmersiveAudioLevel(): Int = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
    override fun isSpatializerEnabled(): Boolean = false
    override fun isSpatializerAvailable(): Boolean = false
    override fun isHeadTrackerAvailable(): Boolean = false
    override fun getSpatializedChannelMasks(): List<Int> = emptyList()
    override fun canBeSpatialized(attributes: AudioAttributes?, format: AudioFormat?): Boolean {
        nonNull(attributes); nonNull(format)
        return false
    }
    override fun isSpatializerAvailableForDevice(device: AudioDeviceAttributes?): Boolean {
        isSpatializerAvailableForDevice_enforcePermission(); nonNull(device)
        return false
    }
    override fun hasHeadTracker(device: AudioDeviceAttributes?): Boolean {
        hasHeadTracker_enforcePermission(); nonNull(device)
        return false
    }
    override fun isHeadTrackerEnabled(device: AudioDeviceAttributes?): Boolean {
        isHeadTrackerEnabled_enforcePermission(); nonNull(device)
        return false
    }
    override fun setHeadTrackerEnabled(enabled: Boolean, device: AudioDeviceAttributes?) {
        setHeadTrackerEnabled_enforcePermission(); nonNull(device)
    }
    override fun setSpatializerEnabled(enabled: Boolean) {
        setSpatializerEnabled_enforcePermission()
        // SpatializerHelper cannot enable an unsupported effect; state stays disabled.
    }
    override fun getSpatializerCompatibleAudioDevices(): List<AudioDeviceAttributes> {
        getSpatializerCompatibleAudioDevices_enforcePermission()
        return ArrayList()
    }
    override fun addSpatializerCompatibleAudioDevice(device: AudioDeviceAttributes?) {
        addSpatializerCompatibleAudioDevice_enforcePermission(); nonNull(device)
        // No supported spatialization mode can accept this device.
    }
    override fun removeSpatializerCompatibleAudioDevice(device: AudioDeviceAttributes?) {
        removeSpatializerCompatibleAudioDevice_enforcePermission(); nonNull(device)
    }
    override fun getSupportedHeadTrackingModes(): IntArray {
        getSupportedHeadTrackingModes_enforcePermission()
        return intArrayOf()
    }
    override fun getActualHeadTrackingMode(): Int {
        getActualHeadTrackingMode_enforcePermission()
        return Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED
    }
    override fun getDesiredHeadTrackingMode(): Int {
        getDesiredHeadTrackingMode_enforcePermission()
        // init(false) returns before changing the pinned helper's desired-mode default.
        return Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD
    }
    override fun setDesiredHeadTrackingMode(mode: Int) { setDesiredHeadTrackingMode_enforcePermission() }
    override fun recenterHeadTracker() { recenterHeadTracker_enforcePermission() }
    override fun setSpatializerGlobalTransform(transform: FloatArray?) {
        setSpatializerGlobalTransform_enforcePermission()
        require(nonNull(transform).size == 6) { "invalid array size${transform!!.size}" }
    }
    override fun setSpatializerParameter(key: Int, value: ByteArray?) {
        setSpatializerParameter_enforcePermission(); nonNull(value)
        throw IllegalStateException("Can't set parameter key:$key without a spatializer")
    }
    override fun getSpatializerParameter(key: Int, value: ByteArray?) {
        getSpatializerParameter_enforcePermission(); nonNull(value)
        throw IllegalStateException("Can't get parameter key:$key without a spatializer")
    }
    override fun getSpatializerOutput(): Int {
        getSpatializerOutput_enforcePermission()
        throw IllegalStateException("Can't get output without a spatializer")
    }
    override fun registerSpatializerCallback(callback: ISpatializerCallback?) { stateCallbacks.register(nonNull(callback)) }
    override fun unregisterSpatializerCallback(callback: ISpatializerCallback?) { stateCallbacks.unregister(nonNull(callback)) }
    override fun registerSpatializerHeadTrackingCallback(callback: ISpatializerHeadTrackingModeCallback?) {
        registerSpatializerHeadTrackingCallback_enforcePermission(); trackingCallbacks.register(nonNull(callback))
    }
    override fun unregisterSpatializerHeadTrackingCallback(callback: ISpatializerHeadTrackingModeCallback?) {
        unregisterSpatializerHeadTrackingCallback_enforcePermission(); trackingCallbacks.unregister(nonNull(callback))
    }
    override fun registerSpatializerHeadTrackerAvailableCallback(callback: ISpatializerHeadTrackerAvailableCallback?, register: Boolean) {
        if (register) trackerCallbacks.register(nonNull(callback)) else trackerCallbacks.unregister(nonNull(callback))
    }
    override fun registerHeadToSoundstagePoseCallback(callback: ISpatializerHeadToSoundStagePoseCallback?) {
        registerHeadToSoundstagePoseCallback_enforcePermission(); poseCallbacks.register(nonNull(callback))
    }
    override fun unregisterHeadToSoundstagePoseCallback(callback: ISpatializerHeadToSoundStagePoseCallback?) {
        unregisterHeadToSoundstagePoseCallback_enforcePermission(); poseCallbacks.unregister(nonNull(callback))
    }
    override fun registerSpatializerOutputCallback(callback: ISpatializerOutputCallback?) {
        registerSpatializerOutputCallback_enforcePermission(); outputCallbacks.register(nonNull(callback))
    }
    override fun unregisterSpatializerOutputCallback(callback: ISpatializerOutputCallback?) {
        unregisterSpatializerOutputCallback_enforcePermission(); outputCallbacks.unregister(nonNull(callback))
    }

    override fun registerLoudnessCodecUpdatesDispatcher(dispatcher: ILoudnessCodecUpdatesDispatcher?) {
        synchronized(dispatcherLock) { loudnessCallbacks.register(nonNull(dispatcher), Binder.getCallingPid()) }
    }
    override fun unregisterLoudnessCodecUpdatesDispatcher(dispatcher: ILoudnessCodecUpdatesDispatcher?) {
        synchronized(dispatcherLock) { loudnessCallbacks.unregister(nonNull(dispatcher)) }
    }
    override fun startLoudnessCodecUpdates(sessionId: Int) {
        synchronized(lock) { sessions.getOrPut(Session(Binder.getCallingPid(), sessionId)) { HashSet() } }
    }
    override fun stopLoudnessCodecUpdates(sessionId: Int) {
        synchronized(lock) { sessions.remove(Session(Binder.getCallingPid(), sessionId)) }
    }
    override fun addLoudnessCodecInfo(sessionId: Int, mediaCodecHash: Int, codecInfo: LoudnessCodecInfo?) {
        synchronized(lock) {
            val codecs = sessions[Session(Binder.getCallingPid(), sessionId)] ?: return
            codecs.add(codecInfo?.let { source -> LoudnessCodecInfo().apply {
                metadataType = source.metadataType
                isDownmixing = source.isDownmixing
            } })
        }
        // LoudnessCodecHelper sends the initial per-codec update even when the routed
        // parameter bundle is empty. The stock client filters it before setParameters.
        val parameters = PersistableBundle().apply {
            putPersistableBundle(mediaCodecHash.toString(), getLoudnessParams(codecInfo))
        }
        synchronized(dispatcherLock) {
            val count = loudnessCallbacks.beginBroadcast()
            try {
                repeat(count) { index ->
                    try { loudnessCallbacks.getBroadcastItem(index)
                        .dispatchLoudnessCodecParameterChange(sessionId, parameters) }
                    catch (_: RemoteException) { /* RemoteCallbackList owns death cleanup. */ }
                }
            } finally { loudnessCallbacks.finishBroadcast() }
        }
    }
    override fun removeLoudnessCodecInfo(sessionId: Int, codecInfo: LoudnessCodecInfo?) {
        synchronized(lock) { sessions[Session(Binder.getCallingPid(), sessionId)]?.remove(codecInfo) }
    }
    override fun getLoudnessParams(codecInfo: LoudnessCodecInfo?): PersistableBundle = PersistableBundle()
}
