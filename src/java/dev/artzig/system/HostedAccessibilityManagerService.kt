package dev.artzig.system

import android.content.pm.ParceledListSlice
import android.graphics.Color
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.IAccessibilityManager
import android.view.accessibility.IAccessibilityManagerClient
import com.android.internal.util.IntPair
import android.os.Binder
import android.os.IBinder
import android.os.UserHandle
import android.view.IWindow
import android.view.accessibility.IAccessibilityInteractionConnection
import android.view.accessibility.AccessibilityWindowAttributes
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The system-server half of the stock [AccessibilityManager] client.
 *
 * Android normally obtains this state from AccessibilityManagerService. The
 * hosted runtime has one always-enabled, in-process accessibility consumer:
 * the headless test controller. Keep that policy behind the real Binder
 * contract so application code observes the stock framework client.
 */
class HostedAccessibilityManagerService(private val context: android.content.Context) :
    IAccessibilityManager.Stub(),
    IAccessibilityManager by unsupported<IAccessibilityManager>() {
    override fun asBinder() = this

    /** AccessibilityWindowManager.add/removeAccessibilityInteractionConnection:
     * retain the remote endpoint, allocate an ID, link death and publish surface
     * metadata. Full AccessibilityWindowManager is coupled to accessibility
     * service/automation window tracking; the host's current consumer is local. */
    private val lock = Any()
    private var nextWindowId = 0
    private val windows = linkedMapOf<Int, Connection>()
    private inner class Connection(
        val id: Int, val token: IBinder, val leash: IBinder,
        val endpoint: IAccessibilityInteractionConnection, val packageName: String?,
        val uid: Int, val userId: Int,
    ) : IBinder.DeathRecipient {
        var attributes: AccessibilityWindowAttributes? = null
        override fun binderDied() = synchronized(lock) { removeLocked(this) }
    }

    private fun resolveUser(userId: Int): Int = android.app.ActivityManager.handleIncomingUser(
        Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false,
        "accessibility window", null)

    private fun metadata(token: IBinder, id: Int) {
        com.android.server.LocalServices.getService(com.android.server.wm.WindowManagerInternal::class.java)
            ?.setAccessibilityIdToSurfaceMetadata(token, id)
    }

    override fun addAccessibilityInteractionConnection(windowToken: IWindow, leashToken: IBinder,
        connection: IAccessibilityInteractionConnection, packageName: String?, userId: Int): Int {
        val user = resolveUser(userId)
        val uid = UserHandle.getUid(user, UserHandle.getAppId(Binder.getCallingUid()))
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        // AccessibilitySecurityPolicy.resolveValidReportedPackageLocked sanitizes
        // an invalid reported package instead of rejecting the whole connection.
        val reportedPackage = if (packageName == null || uid == android.os.Process.SYSTEM_UID ||
            packageName in packages || context.checkPermission(
                android.Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY, Binder.getCallingPid(), uid) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) packageName else packages.firstOrNull()
        val record = synchronized(lock) {
            check(nextWindowId < Int.MAX_VALUE) { "Accessibility window IDs exhausted" }
            val record = Connection(nextWindowId++, windowToken.asBinder(), leashToken,
                connection, reportedPackage, uid, user)
            connection.asBinder().linkToDeath(record, 0)
            windows[record.id] = record
            record
        }
        metadata(record.token, record.id)
        return record.id
    }

    override fun removeAccessibilityInteractionConnection(windowToken: IWindow) {
        resolveUser(UserHandle.getCallingUserId())
        synchronized(lock) {
            windows.values.firstOrNull { it.token == windowToken.asBinder() }?.let(::removeLocked)
        }
    }

    private fun removeLocked(record: Connection) {
        if (windows[record.id] !== record) return
        windows.remove(record.id)
        record.endpoint.asBinder().unlinkToDeath(record, 0)
        metadata(record.token, AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
    }

    override fun getAccessibilityWindowId(windowToken: IBinder?): Int {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID)
            throw SecurityException("Accessibility window lookup requires the system UID")
        return synchronized(lock) {
            windows.values.firstOrNull { it.token == windowToken }?.id ?: AccessibilityWindowInfo.UNDEFINED_WINDOW_ID
        }
    }

    override fun setAccessibilityWindowAttributes(displayId: Int, windowId: Int, userId: Int,
        attributes: AccessibilityWindowAttributes?) {
        val user = resolveUser(userId)
        synchronized(lock) {
            windows[windowId]?.takeIf { it.userId == user }?.attributes = attributes
        }
    }

    // AccessibilityManager.tryConnectToServiceLocked decodes the low word as
    // state and the high word as relevant event types (AOSP IntPair layout).
    override fun addClient(client: IAccessibilityManagerClient?, userId: Int): Long =
        IntPair.of(
            AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED,
            AccessibilityEvent.TYPES_ALL_MASK,
        )

    override fun removeClient(client: IAccessibilityManagerClient?, userId: Int): Boolean = true

    override fun sendAccessibilityEvent(event: AccessibilityEvent?, userId: Int) {
        // The client test observer uses AccessibilityManager.AccessibilityPolicy.
        // No enabled remote accessibility services currently consume this event.
    }

    override fun getInstalledAccessibilityServiceList(userId: Int) =
        ParceledListSlice(emptyList<android.accessibilityservice.AccessibilityServiceInfo>())

    override fun getEnabledAccessibilityServiceList(feedbackType: Int, userId: Int) =
        emptyList<android.accessibilityservice.AccessibilityServiceInfo>()

    override fun getRecommendedTimeoutMillis(): Long = IntPair.of(0, 0)

    override fun getFocusStrokeWidth(): Int = 0

    override fun getFocusColor(): Int = Color.TRANSPARENT
}
