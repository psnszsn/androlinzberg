package android.view

/** Synthetic host input enters the pinned ViewRootImpl queue, including IME
 * and fallback stages. Callers retain their event; the queued copy belongs to
 * AOSP and is recycled by finishInputEvent. Native seat input uses InputChannel. */
object HostedRootInput {
    fun handler(root: ViewRootImpl): android.os.Handler = root.mHandler
    fun unhandled(root: ViewRootImpl, event: InputEvent) = root.dispatchUnhandledInputEvent(event)
    fun deliver(root: ViewRootImpl, event: InputEvent): Boolean {
        if (root.view == null) return false
        root.dispatchInputEvent(event.copy())
        return true
    }
    fun capture(root: ViewRootImpl, enabled: Boolean) = root.requestPointerCapture(enabled)
    fun cursorType(root: ViewRootImpl): Int = throw dev.artzig.ArtzigTestDiagnostics.unsupported(
        "HostedRootInput", "cursorType", "Stock cursor state belongs to the session input dispatcher")
}
