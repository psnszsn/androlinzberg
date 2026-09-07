package android.view

/** The legacy renderer's post-IME input boundary. */
object HostedRootInput {
    fun handler(root: ViewRootImpl): android.os.Handler = root.handler
    fun unhandled(root: ViewRootImpl, event: InputEvent) = root.processUnhandledInputEvent(event)
    fun deliver(root: ViewRootImpl, event: InputEvent): Boolean = when (event) {
        is KeyEvent -> root.dispatchKeyEvent(event)
        is MotionEvent -> when {
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) -> root.dispatchPointerEvent(event)
            root.hasPointerCapture() -> root.dispatchCapturedPointerEvent(event)
            else -> root.view?.dispatchGenericMotionEvent(event) ?: false
        }
        else -> false
    }
    fun capture(root: ViewRootImpl, enabled: Boolean) = root.handleHostedPointerCaptureChanged(enabled)
    fun cursorType(root: ViewRootImpl): Int = root.resolvedPointerIconType
}
