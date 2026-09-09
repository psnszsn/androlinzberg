package dev.artzig.testing

import android.graphics.Rect
import android.os.IBinder
import android.view.IWindow
import android.view.ViewRootImpl
import android.view.WindowManagerGlobal
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Test observation of stock roots. WMS and ViewRootImpl own layout and drawing.
 * The pinned framework keeps stopped/frame/window and accessibility query state
 * package-private. Reflection stays in this inspection adapter so no Android
 * class or production caller needs a test hook.
 */
object HostedRootFrames {
    private fun field(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).apply { isAccessible = true }

    private val stoppedField = field(ViewRootImpl::class.java, "mStopped")
    private val frameField = field(ViewRootImpl::class.java, "mWinFrame")
    private val windowField = field(ViewRootImpl::class.java, "mWindow")
    private val attachField = field(ViewRootImpl::class.java, "mAttachInfo")
    private val fetchFlagsField = field(attachField.type, "mAccessibilityFetchFlags")

    @JvmField val stockClients = true
    @JvmStatic fun stopped(root: ViewRootImpl): Boolean = stoppedField.getBoolean(root)
    @JvmStatic fun managedRoots(): List<ViewRootImpl> =
        // setView assigns the parent before traversal attaches the decor.
        WindowManagerGlobal.getInstance().windowViews.mapNotNull { it.parent as? ViewRootImpl }
    @JvmStatic fun frame(root: ViewRootImpl): Rect = Rect(frameField.get(root) as Rect)
    @JvmStatic fun windowToken(root: ViewRootImpl): IBinder =
        (windowField.get(root) as IWindow).asBinder()

    @JvmStatic fun <T> inspectAccessibility(root: ViewRootImpl, block: () -> T): T {
        // Match AccessibilityInteractionController's query-scoped fetch flags.
        val info = attachField.get(root)
        val previous = fetchFlagsField.getInt(info)
        fetchFlagsField.setInt(info, previous or
            AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS)
        try { return block() } finally { fetchFlagsField.setInt(info, previous) }
    }
}
