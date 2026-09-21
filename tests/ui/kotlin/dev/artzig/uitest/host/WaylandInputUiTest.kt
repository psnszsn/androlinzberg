package dev.artzig.uitest.host

import android.graphics.Point
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import dev.artzig.uitest.UiTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Input travels through Wayland and stock InputDispatcher into real APK views. */
class WaylandInputUiTest {
    private val device get() = UiTest.device

    private fun await(selector: BySelector) =
        checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) { "missing $selector" }

    private fun tap(selector: BySelector) = WaylandSeat.tap(await(selector).visibleCenter)

    /** A drag across `selector` at mid-height, between two horizontal fractions. */
    private fun drag(selector: BySelector, from: Double, to: Double) {
        val bounds = await(selector).visibleBounds
        fun at(fraction: Double) = Point(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())
        WaylandSeat.drag(at(from), at(to))
    }

    @Test
    fun tapReachesTheView() {
        await(By.text("Counter: 0"))
        tap(By.text("INCREMENT COUNTER"))
        await(By.text("Counter: 1"))
    }

    @Test
    fun overlappingChildrenInterceptionAndRemoval() {
        tap(By.text("SHOW INPUT ROUTING"))
        await(By.text("Input routing ready"))
        tap(By.text("ROUTE SIBLINGS"))
        drag(By.desc("Pointer upper child"), .2, .8)
        await(By.text("Overlapping sibling routing verified"))
        tap(By.text("ROUTE INTERCEPT DOWN"))
        tap(By.desc("Pointer routing region"))
        await(By.text("Down interception bypasses child verified"))
        tap(By.text("ROUTE INTERCEPT MOVE"))
        drag(By.desc("Pointer routing region"), .1, .9)
        await(By.text("Move interception cancels child verified"))
        tap(By.text("ROUTE DETACH"))
        drag(By.desc("Pointer routing region"), .1, .9)
        await(By.text("Removed target canceled before detach verified"))
    }

    @Test
    fun seekBarDragPreservesTrackingCallbacks() {
        tap(By.text("SHOW INPUT ROUTING"))
        tap(By.text("ROUTE SEEK"))
        drag(By.desc("Seek pointer control"), .15, .85)
        await(By.text("Seek drag start, change and stop verified"))
    }

    @Test
    fun hostF11EntersAndExitsCompositorFullscreen() {
        val anchor = await(By.text("Counter: 0")).visibleCenter
        assertTrue("starts windowed", !WaylandSeat.window().fullscreen)
        WaylandSeat.tapThenKey(anchor, "F11")
        awaitFullscreen(true)
        WaylandSeat.tapThenKey(await(By.text("Counter: 0")).visibleCenter, "F11")
        awaitFullscreen(false)
    }

    private fun awaitFullscreen(expected: Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT
        while (WaylandSeat.window().fullscreen != expected) {
            assertTrue("compositor fullscreen=$expected", android.os.SystemClock.uptimeMillis() < deadline)
            Thread.sleep(100)
        }
        assertNotNull(await(By.text("Counter: 0")))
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
