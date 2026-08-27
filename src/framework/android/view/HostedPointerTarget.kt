package android.view

import android.graphics.Rect

/**
 * Resolves desktop pointer presses on descriptive Android controls.
 *
 * AOSP normally performs this hit test inside ViewRootImpl's input stages and ViewGroup dispatch.
 * The hosted runtime has no InputChannel/InputEventReceiver stage chain, and transformed player
 * overlays can otherwise consume the gesture before an exposed control receives ACTION_DOWN.
 * Non-control gestures remain on the normal ViewRoot dispatch path so scrolling and application
 * gesture detectors retain AOSP interception semantics.
 */
object HostedPointerTarget {
    @JvmStatic
    fun findDescriptiveControl(root: View?, screenX: Float, screenY: Float): View? {
        root ?: return null
        return find(root, screenX.toInt(), screenY.toInt())
    }

    private fun find(view: View, screenX: Int, screenY: Int): View? {
        if (!view.isShown) return null

        val bounds = Rect()
        view.getBoundsOnScreen(bounds)
        if (!bounds.contains(screenX, screenY)) return null

        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                find(view.getChildAt(index), screenX, screenY)?.let { return it }
            }
        }

        return view.takeIf {
            it.isEnabled && it.isClickable && !it.contentDescription.isNullOrEmpty()
        }
    }
}
