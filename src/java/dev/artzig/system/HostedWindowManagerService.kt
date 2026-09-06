package dev.artzig.system

import android.app.IApplicationThread
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.WindowContextInfoChangeItem
import android.content.res.Configuration
import android.graphics.Point
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.ArtzigDisplay
import android.view.Display
import android.view.IWindowManager
import android.window.WindowContextInfo
import com.android.window.flags.Flags

/**
 * Process-local window service for the same logical display as HostedDisplayManagerService.
 * Source note: follows WindowManagerService.attach/detachWindowContext and
 * WindowContextListenerController registration, ownership, and configuration delivery.
 * There is no DisplayContent/DisplayArea graph on this host: the one display is
 * the configuration container. Window sessions and compositor operations remain
 * separate hosted seams and unserved Binder methods report unsupported.
 */
internal class HostedWindowManagerService(
    private val surfaceSyncGroups: com.android.server.wm.HostedSurfaceSyncGroups? = null,
) : IWindowManager.Stub(),
    IWindowManager by unsupported<IWindowManager>() {
    companion object {
        const val SURFACE_SYNC_SERVICE = "hosted_window_sync"
        /** SurfaceView.syncNextFrame requires a buffer producer with transaction callbacks. */
        @JvmStatic fun syncNextSurfaceFrame(consumer: java.util.function.Consumer<android.view.SurfaceControl.Transaction>): Unit =
            throw dev.artzig.ArtzigTestDiagnostics.unsupported(
                "android.view.SurfaceView", "syncNextFrame",
                "The hosted video layer has no BLAST buffer transaction producer",
            )

        /** SurfaceControl.Transaction.apply: no SurfaceFlinger transaction service is hosted. */
        @JvmStatic fun applySurfaceTransaction(sync: Boolean): Unit =
            throw dev.artzig.ArtzigTestDiagnostics.unsupported(
                "android.view.SurfaceControl.Transaction", "apply",
                "SurfaceFlinger transactions are not hosted (sync=$sync)",
            )
    }

    override fun asBinder(): IBinder = this

    // Geometry remains process-local, but a sync token must identify one controller
    // across all peers. Use the stock IWindowManager protocol to the session service.
    private val syncCoordinator: IWindowManager by lazy {
        checkNotNull(IWindowManager.Stub.asInterface(
            android.os.ServiceManager.waitForService(SURFACE_SYNC_SERVICE)))
    }

    override fun addToSurfaceSyncGroup(token: IBinder, parentSyncGroupMerge: Boolean,
        completedListener: android.window.ISurfaceSyncGroupCompletedListener?,
        result: android.window.AddToSurfaceSyncGroupResult): Boolean =
        surfaceSyncGroups?.add(token, parentSyncGroupMerge, completedListener, result)
            ?: syncCoordinator.addToSurfaceSyncGroup(token, parentSyncGroupMerge, completedListener, result)

    override fun markSurfaceSyncGroupReady(token: IBinder) {
        if (surfaceSyncGroups != null) surfaceSyncGroups.markReady(token)
        else syncCoordinator.markSurfaceSyncGroupReady(token)
    }

    private val lock = Any()
    private var configuration = Configuration().apply { setToDefaults() }
    private val clients = LinkedHashMap<IBinder, Registration>()
    private class Registration(
        val thread: IApplicationThread,
        val uid: Int,
        val type: Int,
        val death: IBinder.DeathRecipient,
    )

    private fun attach(thread: IApplicationThread, token: IBinder, type: Int, displayId: Int): WindowContextInfo? {
        if (displayId != Display.DEFAULT_DISPLAY) return null
        synchronized(lock) {
            val old = clients[token]
            if (old != null) enforceOwner(old)
            val death = IBinder.DeathRecipient { synchronized(lock) { clients.remove(token) } }
            try {
                token.linkToDeath(death, 0)
            } catch (_: RemoteException) {
                return null
            }
            if (old != null) token.unlinkToDeath(old.death, 0)
            clients[token] = Registration(thread, Binder.getCallingUid(), type, death)
            return WindowContextInfo(Configuration(configuration), displayId)
        }
    }

    override fun attachWindowContextToDisplayArea(
        appThread: IApplicationThread, clientToken: IBinder, type: Int, displayId: Int, options: Bundle?,
    ): WindowContextInfo? = attach(appThread, clientToken, type, displayId)

    override fun attachWindowContextToDisplayContent(
        appThread: IApplicationThread, clientToken: IBinder, displayId: Int,
    ): WindowContextInfo? = attach(appThread, clientToken, -1, displayId)

    override fun attachWindowContextToWindowToken(
        appThread: IApplicationThread, clientToken: IBinder, token: IBinder,
    ): WindowContextInfo? {
        // WMS returns null for a token absent from its window graph. Hosted windows
        // do not yet publish server WindowTokens; do not pretend a client is attached.
        return null
    }

    override fun detachWindowContext(clientToken: IBinder) {
        synchronized(lock) {
            val record = clients[clientToken] ?: return
            enforceOwner(record)
            clients.remove(clientToken)
            clientToken.unlinkToDeath(record.death, 0)
        }
    }

    override fun reparentWindowContextToDisplayArea(
        appThread: IApplicationThread, clientToken: IBinder, displayId: Int,
    ): Boolean {
        if (!Flags.reparentWindowTokenApi()) return false
        if (displayId != Display.DEFAULT_DISPLAY) return false
        synchronized(lock) {
            val record = clients[clientToken] ?: return false
            enforceOwner(record)
            // WindowContextListenerController rejects reparenting to the current display.
            return false
        }
    }

    private fun enforceOwner(record: Registration) {
        val uid = Binder.getCallingUid()
        val context: android.content.Context = android.app.ActivityThread.currentActivityThread().systemContext
        if (uid != record.uid && context.checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw UnsupportedOperationException("Uid mismatch. Caller uid is $uid, while the listener's owner is from ${record.uid}")
        }
    }

    /** Called by the host after applying a new display configuration to ResourcesManager. */
    fun applyConfiguration(next: Configuration) {
        synchronized(lock) {
            if (configuration == next) return
            configuration = Configuration(next)
            val dead = ArrayList<IBinder>()
            for ((token, record) in clients) {
                val transaction = ClientTransaction(record.thread)
                transaction.addTransactionItem(WindowContextInfoChangeItem(token, next, Display.DEFAULT_DISPLAY))
                try {
                    record.thread.scheduleTransaction(transaction)
                } catch (_: RemoteException) {
                    dead.add(token)
                }
            }
            for (token in dead) clients.remove(token)?.let { token.unlinkToDeath(it.death, 0) }
        }
    }

    override fun openSession(callback: android.view.IWindowSessionCallback): android.view.IWindowSession = HostedWindowSession()

    private class HostedWindowSession : android.view.IWindowSession.Stub(),
        android.view.IWindowSession by unsupported<android.view.IWindowSession>() {
        override fun asBinder(): IBinder = this

        override fun notifyImeWindowVisibilityChangedFromClient(
            window: android.view.IWindow, visible: Boolean, statsToken: android.view.inputmethod.ImeTracker.Token,
        ) {
            // AOSP Session: a window absent from the server graph fails IME tracking.
            // Hosted roots have not registered WindowStates with a server window graph.
            android.view.inputmethod.ImeTracker.forLogging().onFailed(
                statsToken, android.view.inputmethod.ImeTracker.PHASE_WM_NOTIFY_IME_VISIBILITY_CHANGED_FROM_CLIENT,
            )
        }
    }

    override fun getCurrentAnimatorScale(): Float = 1f
    // AOSP WMS selects the top focused display's local IME, or the default display.
    override fun getImeDisplayId(): Int = Display.DEFAULT_DISPLAY
    override fun getInitialDisplayDensity(displayId: Int): Int = displayInfo(displayId).logicalDensityDpi
    override fun getBaseDisplayDensity(displayId: Int): Int = displayInfo(displayId).logicalDensityDpi
    override fun getInitialDisplaySize(displayId: Int, size: Point) {
        val info = displayInfo(displayId)
        size.set(info.logicalWidth, info.logicalHeight)
    }
    override fun getBaseDisplaySize(displayId: Int, size: Point) = getInitialDisplaySize(displayId, size)
    private fun displayInfo(displayId: Int): android.view.DisplayInfo {
        require(displayId == Display.DEFAULT_DISPLAY) { "Unknown display $displayId" }
        return ArtzigDisplay.displayInfo()
    }
    override fun getPossibleDisplayInfo(displayId: Int): List<android.view.DisplayInfo> =
        if (displayId == Display.DEFAULT_DISPLAY) listOf(ArtzigDisplay.displayInfo()) else emptyList()
    override fun hasNavigationBar(displayId: Int): Boolean = false
    override fun getPreferredOptionsPanelGravity(displayId: Int): Int = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
    override fun getDefaultDisplayRotation(): Int = ArtzigDisplay.displayInfo().rotation
    override fun watchRotation(watcher: android.view.IRotationWatcher, displayId: Int): Int = displayInfo(displayId).rotation
    override fun removeRotationWatcher(watcher: android.view.IRotationWatcher) {}
    override fun isKeyguardLocked(): Boolean = false
    override fun isKeyguardSecure(userId: Int): Boolean = false
    override fun disableKeyguard(token: IBinder, tag: String?, userId: Int) {}
    override fun reenableKeyguard(token: IBinder, userId: Int) {}
    override fun exitKeyguardSecurely(callback: android.view.IOnKeyguardExitResult?) { callback?.onKeyguardExitResult(true) }
    override fun dismissKeyguard(callback: com.android.internal.policy.IKeyguardDismissCallback?, message: CharSequence?) { callback?.onDismissSucceeded() }
    override fun addKeyguardLockedStateListener(listener: com.android.internal.policy.IKeyguardLockedStateListener) { listener.onKeyguardLockedStateChanged(false) }
    override fun removeKeyguardLockedStateListener(listener: com.android.internal.policy.IKeyguardLockedStateListener) {}
    override fun registerCrossWindowBlurEnabledListener(listener: android.view.ICrossWindowBlurEnabledListener): Boolean = false
    override fun unregisterCrossWindowBlurEnabledListener(listener: android.view.ICrossWindowBlurEnabledListener) {}
    override fun registerScreenRecordingCallback(callback: android.window.IScreenRecordingCallback): Boolean = false
    override fun unregisterScreenRecordingCallback(callback: android.window.IScreenRecordingCallback) {}
    override fun getWindowInsets(displayId: Int, token: IBinder?, outInsetsState: android.view.InsetsState): Boolean {
        val info = displayInfo(displayId)
        outInsetsState.set(android.view.InsetsState())
        outInsetsState.setDisplayFrame(android.graphics.Rect(0, 0, info.logicalWidth, info.logicalHeight))
        return false
    }
    override fun getCurrentImeTouchRegion(): android.graphics.Region = android.graphics.Region()
    override fun getSupportedDisplayHashAlgorithms(): Array<String> = emptyArray()
    override fun verifyDisplayHash(hash: android.view.displayhash.DisplayHash): android.view.displayhash.VerifiedDisplayHash? = null
}
