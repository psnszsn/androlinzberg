@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ISessionManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedSessionManager : android.media.session.ISessionManager.Stub() {
    override fun createSession(packageName: String?, sessionCb: android.media.session.ISessionCallback?, tag: String?, sessionInfo: android.os.Bundle?, userId: Int): android.media.session.ISession? = throw UnsupportedOperationException("ISessionManager.createSession is not hosted")
    override fun getSessions(compName: android.content.ComponentName?, userId: Int): MutableList<android.media.session.MediaSession.Token?>? = throw UnsupportedOperationException("ISessionManager.getSessions is not hosted")
    override fun getMediaKeyEventSession(packageName: String?): android.media.session.MediaSession.Token? = throw UnsupportedOperationException("ISessionManager.getMediaKeyEventSession is not hosted")
    override fun getMediaKeyEventSessionPackageName(packageName: String?): String? = throw UnsupportedOperationException("ISessionManager.getMediaKeyEventSessionPackageName is not hosted")
    override fun dispatchMediaKeyEvent(packageName: String?, asSystemService: Boolean, keyEvent: android.view.KeyEvent?, needWakeLock: Boolean) { throw UnsupportedOperationException("ISessionManager.dispatchMediaKeyEvent is not hosted") }
    override fun dispatchMediaKeyEventToSessionAsSystemService(packageName: String?, keyEvent: android.view.KeyEvent?, sessionToken: android.media.session.MediaSession.Token?): Boolean = throw UnsupportedOperationException("ISessionManager.dispatchMediaKeyEventToSessionAsSystemService is not hosted")
    override fun dispatchVolumeKeyEvent(packageName: String?, opPackageName: String?, asSystemService: Boolean, keyEvent: android.view.KeyEvent?, stream: Int, musicOnly: Boolean) { throw UnsupportedOperationException("ISessionManager.dispatchVolumeKeyEvent is not hosted") }
    override fun dispatchVolumeKeyEventToSessionAsSystemService(packageName: String?, opPackageName: String?, keyEvent: android.view.KeyEvent?, sessionToken: android.media.session.MediaSession.Token?) { throw UnsupportedOperationException("ISessionManager.dispatchVolumeKeyEventToSessionAsSystemService is not hosted") }
    override fun dispatchAdjustVolume(packageName: String?, opPackageName: String?, suggestedStream: Int, delta: Int, flags: Int) { throw UnsupportedOperationException("ISessionManager.dispatchAdjustVolume is not hosted") }
    override fun addSessionsListener(listener: android.media.session.IActiveSessionsListener?, compName: android.content.ComponentName?, userId: Int) { throw UnsupportedOperationException("ISessionManager.addSessionsListener is not hosted") }
    override fun removeSessionsListener(listener: android.media.session.IActiveSessionsListener?) { throw UnsupportedOperationException("ISessionManager.removeSessionsListener is not hosted") }
    override fun addSession2TokensListener(listener: android.media.session.ISession2TokensListener?, userId: Int) { throw UnsupportedOperationException("ISessionManager.addSession2TokensListener is not hosted") }
    override fun removeSession2TokensListener(listener: android.media.session.ISession2TokensListener?) { throw UnsupportedOperationException("ISessionManager.removeSession2TokensListener is not hosted") }
    override fun registerRemoteSessionCallback(rvc: android.media.IRemoteSessionCallback?) { throw UnsupportedOperationException("ISessionManager.registerRemoteSessionCallback is not hosted") }
    override fun unregisterRemoteSessionCallback(rvc: android.media.IRemoteSessionCallback?) { throw UnsupportedOperationException("ISessionManager.unregisterRemoteSessionCallback is not hosted") }
    override fun isGlobalPriorityActive(): Boolean = throw UnsupportedOperationException("ISessionManager.isGlobalPriorityActive is not hosted")
    override fun addOnMediaKeyEventDispatchedListener(listener: android.media.session.IOnMediaKeyEventDispatchedListener?) { throw UnsupportedOperationException("ISessionManager.addOnMediaKeyEventDispatchedListener is not hosted") }
    override fun removeOnMediaKeyEventDispatchedListener(listener: android.media.session.IOnMediaKeyEventDispatchedListener?) { throw UnsupportedOperationException("ISessionManager.removeOnMediaKeyEventDispatchedListener is not hosted") }
    override fun addOnMediaKeyEventSessionChangedListener(listener: android.media.session.IOnMediaKeyEventSessionChangedListener?, packageName: String?) { throw UnsupportedOperationException("ISessionManager.addOnMediaKeyEventSessionChangedListener is not hosted") }
    override fun removeOnMediaKeyEventSessionChangedListener(listener: android.media.session.IOnMediaKeyEventSessionChangedListener?) { throw UnsupportedOperationException("ISessionManager.removeOnMediaKeyEventSessionChangedListener is not hosted") }
    override fun setOnVolumeKeyLongPressListener(listener: android.media.session.IOnVolumeKeyLongPressListener?) { throw UnsupportedOperationException("ISessionManager.setOnVolumeKeyLongPressListener is not hosted") }
    override fun setOnMediaKeyListener(listener: android.media.session.IOnMediaKeyListener?) { throw UnsupportedOperationException("ISessionManager.setOnMediaKeyListener is not hosted") }
    override fun isTrusted(controllerPackageName: String?, controllerPid: Int, controllerUid: Int): Boolean = throw UnsupportedOperationException("ISessionManager.isTrusted is not hosted")
    override fun setCustomMediaKeyDispatcher(name: String?) { throw UnsupportedOperationException("ISessionManager.setCustomMediaKeyDispatcher is not hosted") }
    override fun setCustomMediaSessionPolicyProvider(name: String?) { throw UnsupportedOperationException("ISessionManager.setCustomMediaSessionPolicyProvider is not hosted") }
    override fun hasCustomMediaKeyDispatcher(componentName: String?): Boolean = throw UnsupportedOperationException("ISessionManager.hasCustomMediaKeyDispatcher is not hosted")
    override fun hasCustomMediaSessionPolicyProvider(componentName: String?): Boolean = throw UnsupportedOperationException("ISessionManager.hasCustomMediaSessionPolicyProvider is not hosted")
    override fun getSessionPolicies(token: android.media.session.MediaSession.Token?): Int = throw UnsupportedOperationException("ISessionManager.getSessionPolicies is not hosted")
    override fun setSessionPolicies(token: android.media.session.MediaSession.Token?, policies: Int) { throw UnsupportedOperationException("ISessionManager.setSessionPolicies is not hosted") }
    override fun expireTempEngagedSessions() { throw UnsupportedOperationException("ISessionManager.expireTempEngagedSessions is not hosted") }
}
