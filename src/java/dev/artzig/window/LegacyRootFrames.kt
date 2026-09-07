package android.view

/** Offscreen root operations retained for the legacy renderer during cutover. */
object HostedRootFrames {
    @JvmField val stockClients = false
    @JvmStatic fun managedRoots(): List<ViewRootImpl> = emptyList()
    @JvmStatic fun frame(root: ViewRootImpl) = android.graphics.Rect(0, 0, root.width, root.height)
    @JvmStatic fun windowToken(root: ViewRootImpl): android.os.IBinder = root.mWindow.asBinder()
    @JvmStatic fun <T> inspectAccessibility(root: ViewRootImpl, block: () -> T): T {
        val info = root.mAttachInfo ?: return block()
        val previous = info.mAccessibilityFetchFlags
        info.mAccessibilityFetchFlags = previous or
            android.view.accessibility.AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS
        try { return block() } finally { info.mAccessibilityFetchFlags = previous }
    }
    @JvmStatic fun stopped(root: ViewRootImpl) = root.`isWindowStopped$artzig`()
    @JvmStatic fun resize(root: ViewRootImpl, width: Int, height: Int) = root.setSize(width, height)
    @JvmStatic fun prepare(root: ViewRootImpl, params: WindowManager.LayoutParams, width: Int, height: Int) =
        root.`prepareWindowTraversal$artzig`(params, width, height)
    @JvmStatic fun recorded(root: ViewRootImpl) = root.`getRenderNode$artzig`()
    @JvmStatic fun traverse(root: ViewRootImpl) = root.doTraversal()
    @JvmStatic fun remove(root: ViewRootImpl) = root.destroy()
}
