package android.view

/** Offscreen root operations retained for the legacy renderer during cutover. */
object HostedRootFrames {
    @JvmField val stockClients = false
    @JvmStatic fun stopped(root: ViewRootImpl) = root.`isWindowStopped$artzig`()
    @JvmStatic fun resize(root: ViewRootImpl, width: Int, height: Int) = root.setSize(width, height)
    @JvmStatic fun prepare(root: ViewRootImpl, params: WindowManager.LayoutParams, width: Int, height: Int) =
        root.`prepareWindowTraversal$artzig`(params, width, height)
    @JvmStatic fun recorded(root: ViewRootImpl) = root.`getRenderNode$artzig`()
    @JvmStatic fun traverse(root: ViewRootImpl) = root.doTraversal()
    @JvmStatic fun remove(root: ViewRootImpl) = root.destroy()
}
