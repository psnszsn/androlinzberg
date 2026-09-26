package dev.artzig.uitest

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.description
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.text
import dev.artzig.uitest.UiApp.waitFor
import dev.artzig.uitest.UiApp.waitUntilGone
import dev.artzig.uitest.host.WaylandSeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** NewPipe 0.29.1 in its narrow phone layout (412x915). */
class NewPipeUiTest {
    @Test
    fun launchesHomeWithoutRuntimeIssues() {
        NewPipe.dismissKeepAndroidOpenDialog()
        expectVisible("Live")
        waitFor(description("Search"))
        waitFor(description("Open Drawer"))
        waitFor(By.res("org.schabi.newpipe:id/main_tab_layout"))
    }

    @Test
    fun backHonorsNonCancelableKeepAndroidOpenDialog() {
        expectVisible("Keep Android Open")
        repeat(2) {
            UiApp.pressBack()
            SystemClock.sleep(500)
            expectVisible("Keep Android Open")
        }
        click("OK")
        waitUntilGone(text("Keep Android Open"))
        expectVisible("Live")
    }

    @Test
    fun opensAndEditsSearch() {
        NewPipe.dismissKeepAndroidOpenDialog()
        click(description("Search"))
        waitFor(By.clazz("android.widget.EditText")).text = "kotlin integration"
        expectVisible("kotlin integration")
        UiTest.device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
        click(description("Navigate up"))
        expectVisible("Live")
    }

    @Test
    fun hamburgerOpensNavigationDrawer() = NewPipe.assertDrawerOpens(412, 915)
}

/** NewPipe 0.29.1 in its desktop layout (1600x900): drawer and the player's
 * immersive presentation. As in Android desktop mode, the app's immersive
 * request fills its own window; F11 is the user's full-immersive action. */
class NewPipeDesktopUiTest {
    @Test
    fun hamburgerOpensNavigationDrawer() = NewPipe.assertDrawerOpens(1600, 900)

    // In its narrow phone UI, NewPipe requests screen rotation for this button.
    // Stock Android ignores orientation requests from resizable freeform tasks
    // (DesktopActivityOrientationChangeHandler handles only non-resizable ones).
    // Desktop UI requests immersive presentation directly.
    @Test
    fun videoButtonFillsItsWindowWithoutCompositorFullscreen() {
        NewPipe.openFirstLiveVideo()
        NewPipe.awaitFullscreen(false)
        NewPipe.startPlayback()
        NewPipe.revealPlayerControls()
        // Exercise presentation changes without continuous video frames.
        NewPipe.pausePlayer()
        NewPipe.enterPlayerFullscreen()
        // Desktop mode never resizes a freeform task for an immersive request.
        SystemClock.sleep(1_000)
        assertTrue("an immersive request entered compositor fullscreen", !WaylandSeat.window().fullscreen)
        val bounds = waitFor(NewPipe.PLAYER).visibleBounds
        assertEquals(1600 to 900, bounds.width() to bounds.height())
    }

    // DesktopTasksController.onTaskInfoChanged: a task the user put into full
    // immersive leaves it when the app stops requesting immersive.
    @Test
    fun userFullscreenEndsWhenThePlayerLeavesFullscreen() {
        NewPipe.openFirstLiveVideo()
        val title = waitFor(By.res("org.schabi.newpipe:id/detail_video_title_view")).text
        NewPipe.startPlayback()
        NewPipe.revealPlayerControls()
        NewPipe.enterPlayerFullscreen()
        NewPipe.pressF11()
        NewPipe.awaitFullscreen(true)

        NewPipe.revealPlayerControls()
        click(NewPipe.ORIENTATION)
        NewPipe.awaitFullscreen(false)
        waitFor(By.res("org.schabi.newpipe:id/detail_video_title_view").text(title))
        waitFor(description("Comments"))
    }

    // Visible controls keep only LAYOUT_* flags; that must not count as the app
    // leaving immersive, or the user's fullscreen would end with every tap.
    @Test
    fun revealingVideoControlsKeepsCompositorFullscreen() {
        NewPipe.openFirstLiveVideo()
        NewPipe.startPlayback()
        NewPipe.revealPlayerControls()
        NewPipe.enterPlayerFullscreen()
        NewPipe.pressF11()
        NewPipe.awaitFullscreen(true)
        // Live footage may legitimately be almost black: assert the fullscreen
        // player geometry, not its content.
        val bounds = waitFor(NewPipe.PLAYER).visibleBounds
        assertEquals(1600 to 900, bounds.width() to bounds.height())
        val capture = UiApp.screenshot()
        assertEquals(1600 to 900, capture.width to capture.height)
        // The controls auto-hide about two seconds after they appear: assert
        // while they are showing, not after a fixed wait.
        NewPipe.revealPlayerControls()
        var observed = 0
        while (UiTest.device.hasObject(NewPipe.ORIENTATION)) {
            assertTrue("compositor fullscreen was lost", WaylandSeat.window().fullscreen)
            observed += 1
            SystemClock.sleep(200)
        }
        assertTrue("the controls hid before fullscreen could be observed", observed > 0)
    }

    @Test
    fun fullscreenButtonExitsAndRestoresVideoDetails() {
        NewPipe.openFirstLiveVideo()
        expectVisible("Background")
        NewPipe.awaitFullscreen(false)
        // Player controls can precede the title in accessibility traversal.
        // Preserve the actual video's identity across the fullscreen cycle.
        val titleId = By.res("org.schabi.newpipe:id/detail_video_title_view")
        val title = waitFor(titleId).text
        assertTrue(title.isNotBlank())

        NewPipe.startPlayback()
        NewPipe.revealPlayerControls()
        NewPipe.enterPlayerFullscreen()

        NewPipe.tapPlayerSurface()
        waitFor(NewPipe.ORIENTATION)
        click(NewPipe.ORIENTATION)

        waitFor(NewPipe.PLAYER)
        waitFor(By.res("org.schabi.newpipe:id/detail_video_title_view").text(title))
        waitFor(description("Comments"))
        expectVisible("Background")
        assertTrue("an immersive request entered compositor fullscreen", !WaylandSeat.window().fullscreen)
    }
}

private object NewPipe {
    val PLAYER: BySelector = By.descStartsWith("Play video, duration:")
    val ORIENTATION: BySelector = By.desc("Toggle screen orientation")
    private const val TIMEOUT = 30_000L
    private val device get() = UiTest.device

    fun dismissKeepAndroidOpenDialog() {
        expectVisible("Keep Android Open")
        expectVisible("SOLUTION")
        expectVisible("DETAILS")
        click("OK")
        waitUntilGone(text("Keep Android Open"))
    }

    /** The live YouTube feed can return a transient extraction error; retry
     * that explicit application error once before requiring a live card. */
    fun openFirstLiveVideo() {
        dismissKeepAndroidOpenDialog()
        if (device.wait(Until.findObject(By.text("LIVE")), TIMEOUT) == null) {
            check(device.hasObject(By.text("Could not parse website")) && device.hasObject(By.text("RETRY"))) { "no live feed" }
            click("RETRY")
        }
        // The feed is dynamic: exercise its first live card, not a pinned channel.
        waitFor(By.text("LIVE")).click()
        waitFor(description("Comments"))
    }

    fun startPlayback() = waitFor(PLAYER).click()

    /** The upper quarter of the surface: its centre is the play/pause button
     * whenever the controls are showing. */
    fun tapPlayerSurface() {
        val bounds = waitFor(PLAYER).visibleBounds
        device.click(bounds.centerX(), bounds.top + bounds.height() / 4)
    }

    /** NewPipe shows the controls when playback starts and auto-hides them about
     * two seconds later; a tap toggles them, so tap only while they are hidden. */
    fun revealPlayerControls() {
        repeat(10) {
            if (device.hasObject(ORIENTATION)) return
            if (device.hasObject(PLAYER)) tapPlayerSurface()
            SystemClock.sleep(700)
        }
        waitFor(ORIENTATION)
    }

    /** Visible controls can retain only LAYOUT_* flags when the player expands.
     * Finish that layout transition, then hide the controls so the app actually
     * requests hidden bars: layout flags alone are not an immersive request. */
    fun enterPlayerFullscreen() {
        click(ORIENTATION)
        waitUntilGone(description("Comments"))
        if (device.hasObject(ORIENTATION)) tapPlayerSurface()
        waitUntilGone(ORIENTATION)
    }

    /** The user's full-immersive action: F11 on the real Wayland keyboard. The
     * focusing tap lands low on the player, away from its play/pause button. */
    fun pressF11() {
        val bounds = waitFor(PLAYER).visibleBounds
        WaylandSeat.tapThenKey(Point(bounds.left + bounds.width() / 8, bounds.bottom - bounds.height() / 8), "F11")
        SystemClock.sleep(500)
        if (device.hasObject(ORIENTATION)) tapPlayerSurface()
        waitUntilGone(ORIENTATION)
    }

    fun pausePlayer() {
        repeat(5) {
            revealPlayerControls()
            if (device.hasObject(By.desc("Play"))) return
            // The overlay can auto-hide between observation and the click.
            device.findObject(By.desc("Pause"))?.click() ?: return@repeat
            if (device.wait(Until.hasObject(By.desc("Play")), 5_000)) return
        }
        throw AssertionError("player did not retain its play/pause control for input")
    }

    /** Compositor (xdg-toplevel) fullscreen, as the task presenter reports it. */
    fun awaitFullscreen(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (WaylandSeat.window().fullscreen != expected) {
            assertTrue("compositor fullscreen=$expected", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        }
    }

    /** DrawerLayout hides its content child from accessibility while its drawer
     * is open: the visible panel is its remaining FrameLayout child. */
    private fun drawerPanelBounds(): Rect {
        val drawer = waitFor(By.clazz("androidx.drawerlayout.widget.DrawerLayout"))
        return drawer.children.single { it.className == "android.widget.FrameLayout" }.visibleBounds
    }

    private fun settledPanelBounds(): Rect {
        var bounds = drawerPanelBounds()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            SystemClock.sleep(300)
            val next = drawerPanelBounds()
            if (next == bounds) return next
            bounds = next
            assertTrue("drawer bounds kept moving", SystemClock.uptimeMillis() < deadline)
        }
    }

    fun assertDrawerOpens(width: Int, height: Int) {
        dismissKeepAndroidOpenDialog()
        waitFor(description("Open Drawer"))
        val closed = UiApp.screenshot()
        assertEquals(width to height, closed.width to closed.height)
        click(description("Open Drawer"))
        expectVisible("Downloads")
        val panel = settledPanelBounds()
        val open = UiApp.screenshot()
        SystemClock.sleep(300)
        assertEquals("drawer bounds were still moving", panel, drawerPanelBounds())
        assertDrawerOpened(closed, open, UiApp.screenshot(), panel)
    }

    private fun assertDrawerOpened(closed: Bitmap, open: Bitmap, settled: Bitmap, panelOnScreen: Rect) {
        check(closed.width == open.width && closed.height == open.height &&
            open.width == settled.width && open.height == settled.height) { "screenshot size changed while opening the drawer" }
        // Screenshots are cropped to the window; node bounds are in screen coordinates.
        val origin = Rect().also { checkNotNull(UiTest.instrumentation.uiAutomation.rootInActiveWindow).getBoundsInScreen(it) }
        val panel = Rect(panelOnScreen).apply { offset(-origin.left, -origin.top) }
        check(panel.width() > 0 && panel.height() > 0 && panel.left >= 0 && panel.top >= 0 &&
            panel.right <= open.width && panel.bottom <= open.height) { "drawer panel $panel outside ${open.width}x${open.height}" }
        var changed = 0
        var unsettled = 0
        var sampled = 0
        for (y in panel.top until panel.bottom step 4) for (x in panel.left until panel.right step 4) {
            if (colorDelta(closed.getPixel(x, y), open.getPixel(x, y)) > 30) changed++
            if (colorDelta(open.getPixel(x, y), settled.getPixel(x, y)) > 30) unsettled++
            sampled++
        }
        assertTrue("the navigation drawer did not change the composited image", changed.toDouble() / sampled > 0.05)
        assertTrue("the navigation drawer was still moving", unsettled.toDouble() / sampled < 0.01)

        val phoneLayout = open.width < 600
        val searchEnd = if (phoneLayout) open.width * 9 / 10 else open.width / 2
        val boundary = (1 until searchEnd).maxBy { x ->
            var score = 0L
            for (y in open.height / 5 until open.height step 4) score += colorDelta(open.getPixel(x - 1, y), open.getPixel(x, y))
            score
        }
        val expected = if (phoneLayout) (open.width * 3 / 5)..(open.width * 4 / 5) else (open.width / 6)..(open.width / 4)
        assertTrue("the navigation drawer is only partly visible (edge at x=$boundary)", boundary in expected)
    }

    private fun colorDelta(first: Int, second: Int): Int =
        abs((first ushr 16 and 0xff) - (second ushr 16 and 0xff)) +
            abs((first ushr 8 and 0xff) - (second ushr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))
}
