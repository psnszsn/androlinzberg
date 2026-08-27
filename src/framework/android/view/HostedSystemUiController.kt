package android.view

/**
 * Reconciles legacy View system-UI flags with the hosted InsetsController.
 *
 * This follows AOSP ViewRootImpl.collectViewAttributes() and
 * ViewRootImpl.controlInsetsForCompatibility(). The stock ViewRootImpl cannot be compiled here
 * because it requires WindowManagerService, IWindowSession, and input-channel infrastructure.
 * The host has no SystemUI surfaces to animate, so it applies the same requested-visible-types
 * transition directly while preserving which types were hidden specifically by legacy flags.
 */
@Suppress("DEPRECATION")
object HostedSystemUiController {
    @JvmStatic
    fun reconcileLegacyVisibility(
        controller: InsetsController?,
        previouslyHiddenTypes: Int,
        systemUiVisibility: Int,
    ): Int {
        val hiddenTypes = hiddenTypesForLegacyVisibility(systemUiVisibility)
        val typesToHide = hiddenTypes and previouslyHiddenTypes.inv()
        val typesToShow = previouslyHiddenTypes and hiddenTypes.inv()

        if (controller != null) {
            if (typesToHide != 0) {
                controller.setRequestedVisibleTypes(0, typesToHide)
            }
            if (typesToShow != 0) {
                controller.setRequestedVisibleTypes(typesToShow, typesToShow)
            }
        }
        return hiddenTypes
    }

    @JvmStatic
    fun hiddenTypesForLegacyVisibility(systemUiVisibility: Int): Int {
        var hiddenTypes = 0
        if (systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0) {
            hiddenTypes = hiddenTypes or WindowInsets.Type.statusBars()
        }
        if (systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0) {
            hiddenTypes = hiddenTypes or WindowInsets.Type.navigationBars()
        }
        return hiddenTypes
    }
}
