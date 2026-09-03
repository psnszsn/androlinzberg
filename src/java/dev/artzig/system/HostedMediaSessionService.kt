package dev.artzig.system

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.IActiveSessionsListener
import android.media.session.ISession
import android.media.session.ISessionCallback
import android.media.session.ISessionController
import android.media.session.ISessionControllerCallback
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.ParcelableListBinder
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.ResultReceiver
import android.util.Log
import android.view.KeyEvent

/**
 * The session's media session manager over the real `ISessionManager` surface,
 * published as `Context.MEDIA_SESSION_SERVICE`.
 *
 * Source note: `com.android.server.media.MediaSessionService` (`createSession`,
 * `getSessions`, `dispatchMediaKeyEvent`, the sessions listeners and the
 * permission gates), `MediaSessionRecord` (the `ISession` and `ISessionController`
 * binders, controller callback fan-out, `ParcelableListBinder` for the queue) and
 * `MediaSessionStack` (the media-button session: the playing session, else the most
 * recently active one). The AOSP service also owns volume-key routing to the audio
 * service and the system's media key listener policy; on this host volume keys
 * reach the session's volume provider only, and media keys arrive through the
 * public dispatch entry points (the desktop input seam may call them the same way).
 * Reading other packages' sessions and registering session listeners stay gated
 * as in AOSP: the system, or a package holding MEDIA_CONTENT_CONTROL / an enabled
 * notification listener, neither of which exists for a hosted app.
 */
class HostedMediaSessionService : android.media.session.ISessionManager.Stub(), android.media.session.ISessionManager by unsupported<android.media.session.ISessionManager>() {
    override fun asBinder(): android.os.IBinder = this

    @Volatile var packageService: HostedPackageManagerService? = null

    private val lock = Any()
    /** MediaSessionStack: every live session, most recently created first. */
    private val sessions = ArrayList<SessionRecord>()
    private val sessionsListeners = ArrayList<IActiveSessionsListener>()
    private var nextSequence = 0

    // ------------------------------------------------------------ MediaSessionRecord

    private inner class SessionRecord(
        val packageName: String,
        val pid: Int,
        val uid: Int,
        val tag: String?,
        val sessionInfo: Bundle?,
        val callback: ISessionCallback,
    ) : IBinder.DeathRecipient {
        val createdAt = System.nanoTime()
        var lastActiveAt = 0L
        var flags = 0L
        var active = false
        var metadata: MediaMetadata? = null
        var playbackState: PlaybackState? = null
        var queue: List<MediaSession.QueueItem>? = null
        var queueTitle: CharSequence? = null
        var extras: Bundle? = null
        var ratingType = Rating.RATING_NONE
        var mediaButtonReceiver: PendingIntent? = null
        var launchIntent: PendingIntent? = null
        var volumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        var volumeControl = android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE
        var maxVolume = 100
        var currentVolume = 100
        var volumeControlId: String? = null
        var audioAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        var destroyed = false
        val controllerCallbacks = ArrayList<ISessionControllerCallback>()

        val session: ISession.Stub = SessionStub()
        val controller: ISessionController.Stub = ControllerStub()
        val token: MediaSession.Token get() = MediaSession.Token(uid, controller)

        override fun binderDied() = destroy("session owner died")

        fun isPlaying(): Boolean = playbackState?.let {
            it.state == PlaybackState.STATE_PLAYING || it.state == PlaybackState.STATE_BUFFERING
        } ?: false

        fun playbackInfo(): MediaController.PlaybackInfo =
            MediaController.PlaybackInfo(volumeType, volumeControl, maxVolume, currentVolume, audioAttributes, volumeControlId)

        /** MediaSessionRecord.pushXxxUpdate: fan out to the controller callbacks, dropping dead ones. */
        fun push(what: String, call: (ISessionControllerCallback) -> Unit) {
            val callbacks = synchronized(lock) { ArrayList(controllerCallbacks) }
            for (cb in callbacks) {
                try {
                    call(cb)
                } catch (e: RemoteException) {
                    synchronized(lock) { controllerCallbacks.remove(cb) }
                    Log.w(TAG, "Removing dead controller callback ($what) for $packageName/$tag")
                }
            }
        }

        fun destroy(reason: String) {
            val listeners: List<IActiveSessionsListener>
            synchronized(lock) {
                if (destroyed) return
                destroyed = true
                sessions.remove(this)
                listeners = ArrayList(sessionsListeners)
            }
            push("destroyed") { it.onSessionDestroyed() }
            Log.i(TAG, "session destroyed ($reason): $packageName/$tag")
            notifySessionsChanged(listeners)
        }

        private inner class SessionStub : ISession.Stub() {
            override fun sendEvent(event: String?, data: Bundle?) = push("event") { it.onEvent(event, data) }
            override fun getController(): ISessionController = this@SessionRecord.controller
            override fun setFlags(flags: Int) { synchronized(lock) { this@SessionRecord.flags = flags.toLong() } }

            /** MediaSessionRecord.setActive: the stack and the sessions listeners see the change. */
            override fun setActive(active: Boolean) {
                val listeners: List<IActiveSessionsListener>
                synchronized(lock) {
                    if (this@SessionRecord.active == active) return
                    this@SessionRecord.active = active
                    if (active) lastActiveAt = System.nanoTime()
                    listeners = ArrayList(sessionsListeners)
                }
                Log.i(TAG, "session ${if (active) "active" else "inactive"}: $packageName/$tag")
                notifySessionsChanged(listeners)
            }

            override fun setMediaButtonReceiver(mbr: PendingIntent?) { synchronized(lock) { mediaButtonReceiver = mbr } }
            override fun setMediaButtonBroadcastReceiver(broadcastReceiver: ComponentName?) {}
            override fun setLaunchPendingIntent(pi: PendingIntent?) { synchronized(lock) { launchIntent = pi } }
            override fun destroySession() = destroy("destroySession")
            override fun setMetadata(metadata: MediaMetadata?, duration: Long, metadataDescription: String?) {
                synchronized(lock) { this@SessionRecord.metadata = metadata }
                push("metadata") { it.onMetadataChanged(metadata) }
            }
            override fun setPlaybackState(state: PlaybackState?) {
                synchronized(lock) {
                    val wasPlaying = isPlaying()
                    playbackState = state
                    // MediaSessionStack.onPlaybackStateChanged: a session that starts
                    // playing becomes the media-button session.
                    if (!wasPlaying && isPlaying()) lastActiveAt = System.nanoTime()
                }
                push("playback state") { it.onPlaybackStateChanged(state) }
            }
            override fun resetQueue() {
                synchronized(lock) { queue = null }
                push("queue") { it.onQueueChanged(null) }
            }
            override fun getBinderForSetQueue(): IBinder =
                ParcelableListBinder<MediaSession.QueueItem>(MediaSession.QueueItem::class.java) { list ->
                    synchronized(lock) { queue = list }
                    push("queue") { it.onQueueChanged(list?.let { l -> android.content.pm.ParceledListSlice<MediaSession.QueueItem>(l) }) }
                }
            override fun setQueueTitle(title: CharSequence?) {
                synchronized(lock) { queueTitle = title }
                push("queue title") { it.onQueueTitleChanged(title) }
            }
            override fun setExtras(extras: Bundle?) {
                synchronized(lock) { this@SessionRecord.extras = extras }
                push("extras") { it.onExtrasChanged(extras) }
            }
            override fun setRatingType(type: Int) { synchronized(lock) { ratingType = type } }
            override fun setPlaybackToLocal(attributes: AudioAttributes?) {
                val info: MediaController.PlaybackInfo
                synchronized(lock) {
                    volumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
                    volumeControl = android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE
                    volumeControlId = null
                    if (attributes != null) audioAttributes = attributes
                    info = playbackInfo()
                }
                push("volume info") { it.onVolumeInfoChanged(info) }
            }
            override fun setPlaybackToRemote(control: Int, max: Int, controlId: String?) {
                val info: MediaController.PlaybackInfo
                synchronized(lock) {
                    volumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                    volumeControl = control
                    maxVolume = max
                    volumeControlId = controlId
                    info = playbackInfo()
                }
                push("volume info") { it.onVolumeInfoChanged(info) }
            }
            override fun setCurrentVolume(currentVolume: Int) {
                val info: MediaController.PlaybackInfo
                synchronized(lock) {
                    this@SessionRecord.currentVolume = currentVolume
                    info = playbackInfo()
                }
                push("volume info") { it.onVolumeInfoChanged(info) }
            }
        }

        /** MediaSessionRecord.ControllerStub: commands carry the controller's identity to the session callback. */
        private inner class ControllerStub : ISessionController.Stub() {
            private fun cb(): ISessionCallback = callback
            private val pid get() = Binder.getCallingPid()
            private val uid get() = Binder.getCallingUid()

            override fun sendCommand(packageName: String?, command: String?, args: Bundle?, cb: ResultReceiver?) = cb().onCommand(packageName, pid, uid, command, args, cb)
            override fun sendMediaButton(packageName: String?, mediaButton: KeyEvent?): Boolean {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, mediaButton)
                cb().onMediaButtonFromController(packageName, pid, uid, intent)
                return true
            }
            override fun registerCallback(packageName: String?, cb: ISessionControllerCallback?) {
                cb ?: return
                synchronized(lock) {
                    if (destroyed) {
                        try { cb.onSessionDestroyed() } catch (e: RemoteException) {}
                        return
                    }
                    if (controllerCallbacks.none { it.asBinder() == cb.asBinder() }) controllerCallbacks.add(cb)
                }
                try {
                    cb.asBinder().linkToDeath({ synchronized(lock) { controllerCallbacks.removeAll { it.asBinder() == cb.asBinder() } } }, 0)
                } catch (e: RemoteException) {
                    synchronized(lock) { controllerCallbacks.remove(cb) }
                }
            }
            override fun unregisterCallback(cb: ISessionControllerCallback?) {
                synchronized(lock) { controllerCallbacks.removeAll { it.asBinder() == cb?.asBinder() } }
            }
            // The record's fields, qualified: inside this stub the bare names resolve to
            // its own getters (getPackageName(), getFlags(), ...).
            override fun getPackageName(): String = this@SessionRecord.packageName
            override fun getTag(): String? = this@SessionRecord.tag
            override fun getSessionInfo(): Bundle? = this@SessionRecord.sessionInfo
            override fun getLaunchPendingIntent(): PendingIntent? = synchronized(lock) { launchIntent }
            override fun getFlags(): Long = synchronized(lock) { this@SessionRecord.flags }
            override fun getVolumeAttributes(): MediaController.PlaybackInfo = synchronized(lock) { playbackInfo() }
            override fun adjustVolume(packageName: String?, opPackageName: String?, direction: Int, flags: Int) {
                // Local playback adjusts the stream through the audio service (none hosted);
                // remote playback asks the session's VolumeProvider.
                if (synchronized(lock) { volumeType } == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) cb().onAdjustVolume(packageName, pid, uid, direction)
            }
            override fun setVolumeTo(packageName: String?, opPackageName: String?, value: Int, flags: Int) {
                if (synchronized(lock) { volumeType } == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) cb().onSetVolumeTo(packageName, pid, uid, value)
            }
            override fun prepare(packageName: String?) = cb().onPrepare(packageName, pid, uid)
            override fun prepareFromMediaId(packageName: String?, mediaId: String?, extras: Bundle?) = cb().onPrepareFromMediaId(packageName, pid, uid, mediaId, extras)
            override fun prepareFromSearch(packageName: String?, string: String?, extras: Bundle?) = cb().onPrepareFromSearch(packageName, pid, uid, string, extras)
            override fun prepareFromUri(packageName: String?, uri: android.net.Uri?, extras: Bundle?) = cb().onPrepareFromUri(packageName, pid, uid, uri, extras)
            override fun play(packageName: String?) = cb().onPlay(packageName, pid, uid)
            override fun playFromMediaId(packageName: String?, mediaId: String?, extras: Bundle?) = cb().onPlayFromMediaId(packageName, pid, uid, mediaId, extras)
            override fun playFromSearch(packageName: String?, string: String?, extras: Bundle?) = cb().onPlayFromSearch(packageName, pid, uid, string, extras)
            override fun playFromUri(packageName: String?, uri: android.net.Uri?, extras: Bundle?) = cb().onPlayFromUri(packageName, pid, uid, uri, extras)
            override fun skipToQueueItem(packageName: String?, id: Long) = cb().onSkipToTrack(packageName, pid, uid, id)
            override fun pause(packageName: String?) = cb().onPause(packageName, pid, uid)
            override fun stop(packageName: String?) = cb().onStop(packageName, pid, uid)
            override fun next(packageName: String?) = cb().onNext(packageName, pid, uid)
            override fun previous(packageName: String?) = cb().onPrevious(packageName, pid, uid)
            override fun fastForward(packageName: String?) = cb().onFastForward(packageName, pid, uid)
            override fun rewind(packageName: String?) = cb().onRewind(packageName, pid, uid)
            override fun seekTo(packageName: String?, pos: Long) = cb().onSeekTo(packageName, pid, uid, pos)
            override fun rate(packageName: String?, rating: Rating?) = cb().onRate(packageName, pid, uid, rating)
            override fun setPlaybackSpeed(packageName: String?, speed: Float) = cb().onSetPlaybackSpeed(packageName, pid, uid, speed)
            override fun sendCustomAction(packageName: String?, action: String?, args: Bundle?) = cb().onCustomAction(packageName, pid, uid, action, args)
            override fun getMetadata(): MediaMetadata? = synchronized(lock) { this@SessionRecord.metadata }
            override fun getPlaybackState(): PlaybackState? = synchronized(lock) { this@SessionRecord.playbackState }
            override fun getQueue(): android.content.pm.ParceledListSlice<*>? {
                val q: List<MediaSession.QueueItem> = synchronized(lock) { this@SessionRecord.queue } ?: return null
                return android.content.pm.ParceledListSlice<MediaSession.QueueItem>(q)
            }
            override fun getQueueTitle(): CharSequence? = synchronized(lock) { this@SessionRecord.queueTitle }
            override fun getExtras(): Bundle? = synchronized(lock) { this@SessionRecord.extras }
            override fun getRatingType(): Int = synchronized(lock) { this@SessionRecord.ratingType }
        }
    }

    // ------------------------------------------------------------ permissions (MediaSessionService)

    private fun enforcePackageName(packageName: String?, uid: Int) {
        if (uid == Process.SYSTEM_UID || uid == 0) return
        val packages = packageService?.getPackagesForUid(uid)
        if (packages == null || packages.isEmpty() || packages.contains(packageName)) return
        throw SecurityException("Package $packageName does not belong to the calling uid $uid")
    }

    /** MediaSessionService.enforceMediaPermissions: MEDIA_CONTENT_CONTROL or an enabled notification listener. */
    private fun enforceMediaPermissions(what: String) {
        if (isTrustedUid(Binder.getCallingUid())) return
        throw SecurityException("Missing permission to control media ($what).")
    }

    private fun isTrustedUid(uid: Int): Boolean =
        uid == Process.SYSTEM_UID || uid == 0 ||
            packageService?.checkUidPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, uid) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ ISessionManager

    override fun createSession(packageName: String?, sessionCb: ISessionCallback?, tag: String?, sessionInfo: Bundle?, userId: Int): ISession? {
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        enforcePackageName(packageName, uid)
        requireNotNull(sessionCb) { "Controller callback cannot be null" }
        requireNotNull(packageName) { "packageName cannot be null" }
        val record = SessionRecord(packageName, pid, uid, tag, sessionInfo, sessionCb)
        try {
            sessionCb.asBinder().linkToDeath(record, 0)
        } catch (e: RemoteException) {
            throw IllegalStateException("Failed to create a new session record")
        }
        synchronized(lock) { sessions.add(0, record) }
        Log.i(TAG, "session created: $packageName/$tag uid=$uid")
        return record.session
    }

    /** MediaSessionStack.getActiveSessions: playing sessions first, then by last activation. */
    private fun activeSessionsLocked(): List<SessionRecord> =
        sessions.filter { it.active && !it.destroyed }
            .sortedWith(compareByDescending<SessionRecord> { it.isPlaying() }.thenByDescending { Math.max(it.lastActiveAt, it.createdAt) })

    private fun mediaButtonSessionLocked(): SessionRecord? = activeSessionsLocked().firstOrNull()

    override fun getSessions(compName: ComponentName?, userId: Int): MutableList<MediaSession.Token?>? {
        enforceMediaPermissions("getSessions")
        return synchronized(lock) { activeSessionsLocked().map { it.token }.toMutableList<MediaSession.Token?>() }
    }

    override fun getMediaKeyEventSession(packageName: String?): MediaSession.Token? {
        enforceMediaPermissions("getMediaKeyEventSession")
        return synchronized(lock) { mediaButtonSessionLocked()?.token }
    }

    override fun getMediaKeyEventSessionPackageName(packageName: String?): String? {
        enforceMediaPermissions("getMediaKeyEventSessionPackageName")
        return synchronized(lock) { mediaButtonSessionLocked()?.packageName ?: "" }
    }

    /** MediaSessionService.dispatchMediaKeyEvent: the media-button session, else its last media button receiver. */
    override fun dispatchMediaKeyEvent(packageName: String?, asSystemService: Boolean, keyEvent: KeyEvent?, needWakeLock: Boolean) {
        requireNotNull(keyEvent) { "keyEvent cannot be null" }
        if (!KeyEvent.isMediaSessionKey(keyEvent.keyCode)) {
            Log.w(TAG, "Attempted to dispatch a non-media key event: $keyEvent")
            return
        }
        val pid = Binder.getCallingPid()
        val uid = Binder.getCallingUid()
        val record = synchronized(lock) { mediaButtonSessionLocked() }
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        if (record != null) {
            val seq = synchronized(lock) { ++nextSequence }
            try {
                if (asSystemService) record.callback.onMediaButton("android", Process.myPid(), Process.SYSTEM_UID, intent, seq, null)
                else record.callback.onMediaButton(packageName, pid, uid, intent, seq, null)
            } catch (e: RemoteException) {
                Log.w(TAG, "media key delivery failed: ${record.packageName}", e)
            }
            return
        }
        Log.i(TAG, "media key ${KeyEvent.keyCodeToString(keyEvent.keyCode)} dropped: no active session")
    }

    override fun dispatchMediaKeyEventToSessionAsSystemService(packageName: String?, keyEvent: KeyEvent?, sessionToken: MediaSession.Token?): Boolean {
        enforceMediaPermissions("dispatchMediaKeyEventToSessionAsSystemService")
        keyEvent ?: return false
        val record = synchronized(lock) { sessions.firstOrNull { it.controller.asBinder() == sessionToken?.binder?.asBinder() } } ?: return false
        val seq = synchronized(lock) { ++nextSequence }
        record.callback.onMediaButton("android", Process.myPid(), Process.SYSTEM_UID, Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, keyEvent), seq, null)
        return true
    }

    /** Volume keys: the media-button session's remote volume provider; local streams have no hosted audio service. */
    override fun dispatchVolumeKeyEvent(packageName: String?, opPackageName: String?, asSystemService: Boolean, keyEvent: KeyEvent?, stream: Int, musicOnly: Boolean) {
        keyEvent ?: return
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return
        val direction = when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> android.media.AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
            else -> return
        }
        dispatchAdjustVolume(packageName, opPackageName, stream, direction, 0)
    }

    override fun dispatchVolumeKeyEventToSessionAsSystemService(packageName: String?, opPackageName: String?, keyEvent: KeyEvent?, sessionToken: MediaSession.Token?) {
        enforceMediaPermissions("dispatchVolumeKeyEventToSessionAsSystemService")
    }

    override fun dispatchAdjustVolume(packageName: String?, opPackageName: String?, suggestedStream: Int, delta: Int, flags: Int) {
        val record = synchronized(lock) { mediaButtonSessionLocked() } ?: return
        if (synchronized(lock) { record.volumeType } == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            record.callback.onAdjustVolume(packageName, Binder.getCallingPid(), Binder.getCallingUid(), delta)
        }
    }

    private fun notifySessionsChanged(listeners: List<IActiveSessionsListener>) {
        if (listeners.isEmpty()) return
        val tokens = synchronized(lock) { activeSessionsLocked().map { it.token } }
        for (listener in listeners) {
            try {
                listener.onActiveSessionsChanged(tokens)
            } catch (e: RemoteException) {
                synchronized(lock) { sessionsListeners.remove(listener) }
            }
        }
    }

    override fun addSessionsListener(listener: IActiveSessionsListener?, compName: ComponentName?, userId: Int) {
        enforceMediaPermissions("addSessionsListener")
        listener ?: return
        synchronized(lock) { if (sessionsListeners.none { it.asBinder() == listener.asBinder() }) sessionsListeners.add(listener) }
    }

    override fun removeSessionsListener(listener: IActiveSessionsListener?) {
        synchronized(lock) { sessionsListeners.removeAll { it.asBinder() == listener?.asBinder() } }
    }

    override fun addSession2TokensListener(listener: android.media.session.ISession2TokensListener?, userId: Int) {}
    override fun removeSession2TokensListener(listener: android.media.session.ISession2TokensListener?) {}
    override fun registerRemoteSessionCallback(rvc: android.media.IRemoteSessionCallback?) = enforceMediaPermissions("registerRemoteSessionCallback")
    override fun unregisterRemoteSessionCallback(rvc: android.media.IRemoteSessionCallback?) {}
    override fun isGlobalPriorityActive(): Boolean = false
    override fun addOnMediaKeyEventDispatchedListener(listener: android.media.session.IOnMediaKeyEventDispatchedListener?) = enforceMediaPermissions("addOnMediaKeyEventDispatchedListener")
    override fun removeOnMediaKeyEventDispatchedListener(listener: android.media.session.IOnMediaKeyEventDispatchedListener?) {}
    override fun addOnMediaKeyEventSessionChangedListener(listener: android.media.session.IOnMediaKeyEventSessionChangedListener?, packageName: String?) = enforceMediaPermissions("addOnMediaKeyEventSessionChangedListener")
    override fun removeOnMediaKeyEventSessionChangedListener(listener: android.media.session.IOnMediaKeyEventSessionChangedListener?) {}
    override fun setOnVolumeKeyLongPressListener(listener: android.media.session.IOnVolumeKeyLongPressListener?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setOnVolumeKeyLongPressListener requires android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER")
    }
    override fun setOnMediaKeyListener(listener: android.media.session.IOnMediaKeyListener?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setOnMediaKeyListener requires android.permission.SET_MEDIA_KEY_LISTENER")
    }
    /** MediaSessionService.isTrusted: the system, MEDIA_CONTENT_CONTROL holders and the session's own package. */
    override fun isTrusted(controllerPackageName: String?, controllerPid: Int, controllerUid: Int): Boolean =
        isTrustedUid(controllerUid) || (packageService?.getPackagesForUid(Binder.getCallingUid())?.contains(controllerPackageName) == true)
    override fun setCustomMediaKeyDispatcher(name: String?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setCustomMediaKeyDispatcher requires the system")
    }
    override fun setCustomMediaSessionPolicyProvider(name: String?) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setCustomMediaSessionPolicyProvider requires the system")
    }
    override fun hasCustomMediaKeyDispatcher(componentName: String?): Boolean = false
    override fun hasCustomMediaSessionPolicyProvider(componentName: String?): Boolean = false
    override fun getSessionPolicies(token: MediaSession.Token?): Int = 0
    override fun setSessionPolicies(token: MediaSession.Token?, policies: Int) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setSessionPolicies requires the system")
    }
    override fun expireTempEngagedSessions() {}

    /** Live sessions of a package, for dumps and the system test. */
    fun sessionCount(packageName: String?): Int = synchronized(lock) { sessions.count { packageName == null || it.packageName == packageName } }

    private companion object {
        const val TAG = "HostedMediaSessionService"
    }
}
