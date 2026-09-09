package android.view

/** Host frame inspection and window sizing around pinned ViewRootImpl's
 * traversal. WMS owns the frame and BLAST surface; the stock root measures,
 * draws, and reports completion. Package access avoids exposing host-only
 * methods on the Android framework class. */
object HostedRootFrames {
    @JvmField val stockClients = true
    @JvmStatic fun stopped(root: ViewRootImpl) = root.mStopped
    @JvmStatic fun managedRoots(): List<ViewRootImpl> =
        // setView assigns the root as parent before the first traversal attaches
        // the decor. View.getViewRootImpl is still null in that interval.
        WindowManagerGlobal.getInstance().windowViews.mapNotNull { it.parent as? ViewRootImpl }
    @JvmStatic fun frame(root: ViewRootImpl) = android.graphics.Rect(root.mWinFrame)
    @JvmStatic fun windowToken(root: ViewRootImpl): android.os.IBinder = root.mWindow.asBinder()
    @JvmStatic fun <T> inspectAccessibility(root: ViewRootImpl, block: () -> T): T {
        // AccessibilityInteractionController scopes these flags to each query.
        val info = root.mAttachInfo
        val previous = info.mAccessibilityFetchFlags
        info.mAccessibilityFetchFlags = previous or
            android.view.accessibility.AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS
        try { return block() } finally { info.mAccessibilityFetchFlags = previous }
    }

}
