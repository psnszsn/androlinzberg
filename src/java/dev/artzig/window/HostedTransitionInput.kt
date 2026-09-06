package dev.artzig.window

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * ViewRootImpl.InputStage.shouldDropInputEvent / isTerminalInputEvent (Android 16 r1).
 * The hosted root still dispatches directly until the stock window-client cutover.
 */
class HostedTransitionInput {
    var paused = false

    fun shouldDrop(event: InputEvent): Boolean {
        if (!paused || event is KeyEvent && event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val terminal = when (event) {
            is KeyEvent -> event.action == KeyEvent.ACTION_UP
            is MotionEvent -> event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL || event.action == MotionEvent.ACTION_HOVER_EXIT
            else -> false
        }
        // Deliver cancelled terminal events so widgets can release gesture/key state.
        if (terminal) event.cancel()
        return !terminal
    }
}
