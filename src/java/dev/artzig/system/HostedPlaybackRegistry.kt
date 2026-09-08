package dev.artzig.system

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.media.AudioSystem
import android.media.IPlaybackConfigDispatcher
import android.media.PlayerBase
import android.os.Binder
import android.os.Parcel
import android.os.PermissionEnforcer
import android.os.RemoteCallbackList
import android.os.RemoteException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * PlaybackActivityMonitor's PlayerBase registration, caller ownership and configuration
 * delivery. Its audio-policy ducking/fading collaborators require AudioService; the host
 * keeps that unavailable policy separate from the stock configuration state machine.
 */
internal class HostedPlaybackRegistry(private val enforcer: PermissionEnforcer) {
    private data class Entry(val config: AudioPlaybackConfiguration)
    private companion object {
        val owners = ConcurrentHashMap<Int, WeakReference<HostedPlaybackRegistry>>()
        init {
            // The stock configuration retains IPlayerShell only when this monitor is set.
            // One dispatcher also supports independent in-memory service fixtures.
            AudioPlaybackConfiguration.sPlayerDeathMonitor = AudioPlaybackConfiguration.PlayerDeathMonitor { id ->
                owners[id]?.get()?.release(id, 0)
            }
        }
    }
    private val lock = Any()
    private val dispatchLock = Any()
    private val players = LinkedHashMap<Int, Entry>()
    private val callbacks = RemoteCallbackList<IPlaybackConfigDispatcher>()

    fun track(card: PlayerBase.PlayerIdCard): Int {
        validateUsage(card.mAttributes)
        val id = AudioSystem.newAudioPlayerId()
        if (id == -1) return id
        val config = AudioPlaybackConfiguration(card, id, Binder.getCallingUid(), Binder.getCallingPid())
        synchronized(lock) {
            players[id] = Entry(config)
            owners[id] = WeakReference(this)
            config.init()
        }
        return id
    }

    private fun owned(id: Int, uid: Int): Entry? = players[id]?.takeIf { uid == 0 || it.config.clientUid == uid }
    private fun update(id: Int, flush: Boolean = false, operation: (AudioPlaybackConfiguration) -> Boolean) {
        val changed = synchronized(lock) { owned(id, Binder.getCallingUid())?.let { operation(it.config) } ?: false }
        if (changed) dispatch(flush)
    }
    fun attributes(id: Int, attributes: AudioAttributes) {
        validateUsage(attributes)
        update(id) { it.handleAudioAttributesEvent(attributes) }
    }
    fun session(id: Int, session: Int) = update(id) { it.handleSessionIdEvent(session) }
    fun event(id: Int, event: Int, devices: IntArray?) {
        // Port IDs are policy-engine bookkeeping, not player lifecycle states.
        if (event == AudioPlaybackConfiguration.PLAYER_UPDATE_PORT_ID) return
        update(id, event == AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) {
            if (it.playerType == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL &&
                event != AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) false
            else it.handleStateEvent(event, devices?.clone())
        }
    }
    fun release(id: Int, uid: Int = Binder.getCallingUid()) {
        val changed = synchronized(lock) {
            val entry = owned(id, uid) ?: return
            players.remove(id)
            owners.remove(id)
            entry.config.handleStateEvent(AudioPlaybackConfiguration.PLAYER_STATE_RELEASED,
                AudioPlaybackConfiguration.PLAYER_DEVICEIDS_INVALID)
        }
        if (changed) dispatch(true)
    }
    private fun hasPermission(permission: String): Boolean = try {
        enforcer.enforcePermission(permission, Binder.getCallingPid(), Binder.getCallingUid())
        true
    } catch (_: SecurityException) { false }
    private fun privileged(): Boolean = hasPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    private fun validateUsage(attributes: AudioAttributes) {
        val usage = attributes.systemUsage
        if (!AudioAttributes.isSystemUsage(usage)) return
        val interception = usage == AudioAttributes.USAGE_CALL_ASSISTANT &&
            attributes.allFlags and AudioAttributes.FLAG_CALL_REDIRECTION != 0 &&
            hasPermission(Manifest.permission.CALL_AUDIO_INTERCEPTION)
        if (!interception && !privileged()) throw SecurityException("Missing MODIFY_AUDIO_ROUTING permission")
        // AudioService's default supported-system-usage list; the host has no car policy override.
        require(usage == AudioAttributes.USAGE_CALL_ASSISTANT) { "Unsupported usage ${AudioAttributes.usageToString(usage)}" }
    }

    fun configurations(): List<AudioPlaybackConfiguration> = snapshot(privileged())
    private fun snapshot(privileged: Boolean): List<AudioPlaybackConfiguration> = synchronized(lock) {
        players.values.mapNotNull { entry ->
            if (privileged) {
                val parcel = Parcel.obtain()
                try {
                    entry.config.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    AudioPlaybackConfiguration.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
            } else if (entry.config.isActive) AudioPlaybackConfiguration.anonymizedCopy(entry.config) else null
        }
    }
    fun register(callback: IPlaybackConfigDispatcher) { callbacks.register(callback, privileged()) }
    fun unregister(callback: IPlaybackConfigDispatcher) { callbacks.unregister(callback) }
    private fun dispatch(flush: Boolean) = synchronized(dispatchLock) {
        // Pinned PlaybackActivityMonitor suppresses dispatch when its last player is removed.
        if (synchronized(lock) { players.isEmpty() }) return@synchronized
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                val privileged = callbacks.getBroadcastCookie(index) == true
                try { callbacks.getBroadcastItem(index).dispatchPlaybackConfigChange(snapshot(privileged), flush && privileged) }
                catch (_: RemoteException) { /* RemoteCallbackList owns callback death cleanup. */ }
            }
        } finally { callbacks.finishBroadcast() }
    }
}
