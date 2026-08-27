package android.view

/**
 * Maps Android window presentation state onto the host compositor.
 *
 * AOSP View's SYSTEM_UI_FLAG_LAYOUT_* contract keeps content laid out for immersive mode while
 * bars are temporarily revealed. The host has no SystemUI process, so this seam preserves that
 * transition when deciding whether the Wayland toplevel should remain fullscreen.
 */
@Suppress("DEPRECATION")
object HostedWindowPresentation {
    @JvmStatic
    fun requestsFullscreen(window: Window, wasFullscreen: Boolean): Boolean {
        val controller = window.insetsController
        val requestedVisibleTypes = controller?.requestedVisibleTypes
            ?: WindowInsets.Type.defaultVisible()
        return requestsFullscreen(
            window.decorView.systemUiVisibility,
            window.attributes.flags,
            requestedVisibleTypes,
            wasFullscreen,
        )
    }

    @JvmStatic
    fun requestsFullscreen(
        systemUiVisibility: Int,
        windowFlags: Int,
        requestedVisibleTypes: Int,
        wasFullscreen: Boolean,
    ): Boolean {
        if (windowFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) return true

        val explicitlyHidden = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        if (systemUiVisibility and explicitlyHidden != 0) return true

        val immersiveLayout = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (wasFullscreen && systemUiVisibility and immersiveLayout == immersiveLayout) return true

        val systemBars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
        return requestedVisibleTypes and systemBars != systemBars
    }
}
