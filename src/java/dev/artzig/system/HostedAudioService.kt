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
internal class HostedAudioService(private val enforcer: PermissionEnforcer, private val context: android.content.Context? = null) : IAudioService.Stub(enforcer),
    IAudioService by unsupported<IAudioService>() {
    override fun asBinder(): IBinder = this
    private val playback = HostedPlaybackRegistry(enforcer)
    private val focus by lazy {
        // AudioService constructs focus state under its own identity. Preserve that when
        // lazy initialization is first reached from an application Binder request.
        val identity = Binder.clearCallingIdentity()
        try { HostedAudioFocus(checkNotNull(context) { "Audio focus requires a service context" }, playback) }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun requestAudioFocus(attributes: AudioAttributes?, gain: Int, token: IBinder?,
        dispatcher: IAudioFocusDispatcher?, client: String?, packageName: String?, attributionTag: String?,
        flags: Int, policy: android.media.audiopolicy.IAudioPolicyCallback?, sdk: Int): Int =
        focus.request(attributes, gain, token, dispatcher, client, packageName, attributionTag, flags, sdk)
    override fun abandonAudioFocus(dispatcher: IAudioFocusDispatcher?, client: String?, attributes: AudioAttributes?, packageName: String?): Int =
        focus.abandon(dispatcher, client, attributes, packageName)
    override fun unregisterAudioFocusClient(client: String?) = focus.unregister(client)
    override fun getCurrentAudioFocus(): Int = focus.current()
    override fun setMultiAudioFocusEnabled(enabled: Boolean) {
        setMultiAudioFocusEnabled_enforcePermission()
        focus.setMultiFocus(enabled)
    }
    // AudioService.AudioManagerNativeImpl: the interface audioserver/libaudioclient
    // use for permission barriers and hardening events. Permission state is published
    // synchronously here, so the barrier has nothing to wait for; hardening mutes
    // and port mute events are reported by the native services and only logged.
    private val native = object : IAudioManagerNative.Stub() {
        override fun playbackHardeningEvent(uid: Int, type: Byte, bypassed: Boolean) {
            android.util.Log.i("AudioService", "playbackHardeningEvent uid=$uid type=$type bypassed=$bypassed")
        }
        override fun permissionUpdateBarrier() {}
        override fun portMuteEvent(portId: Int, event: Int) {
            android.util.Log.i("AudioService", "portMuteEvent portId=$portId event=$event")
        }
    }
    override fun getNativeInterface(): IAudioManagerNative = native
    override fun isVolumeFixed(): Boolean = true

    // Pinned AudioService.getMode returns MODE_NORMAL when its mode-owner stack
    // is empty. setMode remains unsupported here, so no caller can own a mode.
    // Stock AudioService replacement is blocked by SoundEffectsHelper's SoundPool
    // JNI/backend, which is not yet vendored or wired (aosp-closure.py).
    override fun getMode(): Int = AudioSystem.MODE_NORMAL

    // Pinned AudioService keeps mode listeners in a RemoteCallbackList and only
    // broadcasts from onUpdateAudioMode. With no mode owner, none is dispatched.
    private val modeDispatchers = RemoteCallbackList<IAudioModeDispatcher>()
    override fun registerModeDispatcher(dispatcher: IAudioModeDispatcher) { modeDispatchers.register(dispatcher) }
    override fun unregisterModeDispatcher(dispatcher: IAudioModeDispatcher) { modeDispatchers.unregister(dispatcher) }

    // Pinned AudioService's navigation/home effect switches start disabled and
    // retain the caller's setting. Its full AudioPolicy/SoundPool service cannot
    // run here; preserve the disabled SOUND_EFFECTS_ENABLED early return before
    // reaching the explicitly unsupported sample playback operation.
    @Volatile private var navigationRepeatEffects = false
    @Volatile private var homeEffect = false
    override fun setNavigationRepeatSoundEffectsEnabled(enabled: Boolean) { navigationRepeatEffects = enabled }
    override fun areNavigationRepeatSoundEffectsEnabled(): Boolean = navigationRepeatEffects
    override fun setHomeSoundEffectEnabled(enabled: Boolean) { homeEffect = enabled }
    override fun isHomeSoundEffectEnabled(): Boolean = homeEffect
    override fun playSoundEffect(effectType: Int, userId: Int) {
        val resolver = checkNotNull(context) { "Sound effects require a service context" }.contentResolver
        if (android.provider.Settings.System.getIntForUser(resolver,
                android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 0, userId) != 0) {
            playSoundEffectVolume(effectType, -1.0f)
        }
    }

    override fun trackPlayer(card: PlayerBase.PlayerIdCard): Int = playback.track(card)
    override fun playerAttributes(id: Int, attributes: AudioAttributes) = playback.attributes(id, attributes)
    override fun playerSessionId(id: Int, session: Int) {
        require(session > AudioSystem.AUDIO_SESSION_ALLOCATE) { "invalid session Id $session" }
        playback.session(id, session)
    }
    override fun playerEvent(id: Int, event: Int, devices: IntArray?) = playback.event(id, event, devices)
    override fun releasePlayer(id: Int) = playback.release(id)
    override fun getActivePlaybackConfigurations(): List<AudioPlaybackConfiguration> = playback.configurations()
    override fun registerPlaybackCallback(callback: IPlaybackConfigDispatcher) = playback.register(callback)
    override fun unregisterPlaybackCallback(callback: IPlaybackConfigDispatcher) = playback.unregister(callback)
    override fun playerHasOpPlayAudio(id: Int, allowed: Boolean) {
        // PlaybackActivityMonitor.playerHasOpPlayAudio only records this event.
        android.util.Log.d("HostedAudioService", "player $id AppOps play-audio=$allowed")
    }
    @Volatile private var ringtonePlayer: IRingtonePlayer? = null
    override fun getRingtonePlayer(): IRingtonePlayer? = ringtonePlayer
    override fun setRingtonePlayer(player: IRingtonePlayer?) {
        setRingtonePlayer_enforcePermission()
        ringtonePlayer = player
    }
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
    // AudioService's public route query maps the native policy result to stock device values.
    // The existing backend has one logical speaker; its address matches listAudioPorts.
    override fun getDevicesForAttributesUnprotected(attributes: AudioAttributes?): List<AudioDeviceAttributes> =
        AudioSystem.getDevicesForAttributes(nonNull(attributes), false)
    override fun getDevicesForAttributes(attributes: AudioAttributes?): List<AudioDeviceAttributes> {
        try { enforcer.enforcePermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING, Binder.getCallingPid(), Binder.getCallingUid()) }
        catch (_: SecurityException) {
            try { enforcer.enforcePermission(android.Manifest.permission.QUERY_AUDIO_STATE, Binder.getCallingPid(), Binder.getCallingUid()) }
            catch (_: SecurityException) { throw SecurityException("Missing MODIFY_AUDIO_ROUTING or QUERY_AUDIO_STATE permissions") }
        }
        return getDevicesForAttributesUnprotected(attributes)
    }
    override fun isBluetoothA2dpOn(): Boolean = false
    override fun getStreamVolume(streamType: Int): Int { require(streamType in 0..11); return 10 }
    override fun getStreamMaxVolume(streamType: Int): Int { require(streamType in 0..11); return 15 }
    override fun getStreamMinVolume(streamType: Int): Int { require(streamType in 0..11); return 0 }
    override fun isStreamMute(streamType: Int): Boolean { require(streamType in 0..11); return false }
    // The original AudioService exits these adjustments before stream/direction validation
    // under its fixed-volume policy. The host mixer retains ownership of master gain.
    override fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int, callingPackage: String?) {}
    override fun adjustStreamVolumeWithAttribution(streamType: Int, direction: Int, flags: Int, callingPackage: String?, attributionTag: String?) {}
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int, callingPackage: String?) {}

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
