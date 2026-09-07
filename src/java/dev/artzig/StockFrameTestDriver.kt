package dev.artzig

import android.os.SystemClock
import android.view.Choreographer

/** Test-only frame boundary for the stock DisplayEventReceiver/Choreographer path.
 * Frames arrive through the native event source. The scheduler flag is inspected
 * only to bound fixture settling; tests never call doFrame or invent timestamps. */
internal object StockFrameTestDriver {
    private val scheduled = Choreographer::class.java.getDeclaredField("mFrameScheduled")
        .apply { isAccessible = true }

    fun awaitFrame(drain: () -> Unit) {
        var committed = false
        Choreographer.getInstance().postCallback(Choreographer.CALLBACK_COMMIT,
            { committed = true }, null)
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (!committed && SystemClock.uptimeMillis() < deadline) {
            drain()
            if (!committed) SystemClock.sleep(1)
        }
        check(committed) { "Stock DisplayEventReceiver did not deliver a frame" }
    }

    fun settle(drain: () -> Unit) {
        repeat(16) {
            drain()
            awaitFrame(drain)
            drain()
            if (!scheduled.getBoolean(Choreographer.getInstance())) return
        }
        error("Activity fixture frame callbacks did not settle")
    }
}
