package dev.artzig.uitest.host

import android.app.ActivityTaskManager
import android.graphics.Point
import android.os.SystemClock
import android.view.Display
import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.expectVisibleInTask
import dev.artzig.uitest.UiApp.waitFor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Android drag and drop against the desktop's, in a desktop (labwc) session:
 * drags inside a task, from and to a native Wayland peer, between two tasks,
 * the stock drag shadow as the compositor's drag icon, and the local fallback
 * while the data-device protocol is withdrawn. Shadows exist only on the
 * compositor, so those assertions read its captures.
 */
class DesktopDragUiTest {
    @Test
    fun dragsCrossTasksAndTheDesktopInBothDirections() {
        click(By.text(Pattern.compile("show drag and drop", Pattern.CASE_INSENSITIVE)))
        expectVisible("Drag ready")
        val source = tasks().single()

        // Repeated rounds keep descriptor, thread and transfer churn under load.
        repeat(ROUNDS) { round ->
            if (round > 0) click("USE DRAG TEXT")
            dragAcross("Drag received=Android drag 😀 café")
            click("USE DRAG FILE")
            dragAcross("Drag received=Android file 😀 café")
            click("USE SLOW DRAG FILE")
        }
        HostSession.peer("--native-drag-slow-task")
        expectVisible("Drag received=Android file 😀 café")
        click("USE DRAG FILE")

        nativeSource("text", "text/plain;charset=utf-8", "Native drag 😀 café", "Drag received=Native drag 😀 café")
        nativeSource("html", "text/html", "<b>Native rich 😀</b>", "Drag received=Native rich 😀")
        val file = File(HostSession.artifacts, "Native drag café.txt").apply { writeText("Native file 😀 café") }
        nativeSource("file", "text/uri-list", URI("file", "", file.absolutePath, null).toASCIIString() + "\r\n",
            "Drag received=Native file 😀 café")
        click("REJECT DRAG")
        dragAcross("Drag rejected")
        nativeSource("rejected", "text/plain;charset=utf-8", "Rejected native drag", "Drag rejected", outcome = "reject")
        click("USE DRAG TEXT")
        nativeSource("cancelled", "text/plain;charset=utf-8", "Cancelled native drag", "Drag cancelled", outcome = "cancel")

        nativeDestination("text", "Android drag 😀 café")
        nativeDestination("rejected", "Android drag 😀 café", outcome = "reject")
        nativeDestination("cancelled", "Android drag 😀 café", outcome = "cancel")
        for (mode in listOf("Offset", "Empty", "Updating", "Transparent")) {
            click("NEXT DRAG SHADOW")
            expectVisible("Drag shadow=$mode")
            nativeDestination(mode.lowercase(), "Android drag 😀 café", shadow = mode.lowercase())
        }
        click("NEXT DRAG SHADOW")
        expectVisible("Drag shadow=Normal")
        click("USE DRAG FILE")
        nativeDestination("file", "Android file 😀 café")

        click("OPEN DRAG TASK")
        val target = awaitSecondTask(source)
        expectVisibleInTask(target, "Drag ready")
        for ((button, expected) in listOf("USE DRAG TEXT" to "Android drag 😀 café", "USE DRAG FILE" to "Android file 😀 café")) {
            clickInSource(button)
            HostSession.peer("--native-drag-cross-task")
            expectVisibleInTask(target, "Drag received=$expected")
            expectVisibleInTask(source, "Drag exported")
        }

        clickInSource("REMOVE DRAG PROTOCOL")
        HostSession.awaitLog("task clipboard available=false")
        val local = File(HostSession.artifacts, "local-drag-shadow")
        HostSession.peer("--native-drag-local-task", local.path)
        expectVisibleInTask(source, "Drag received=Android file 😀 café")
        assertLocalShadow(local, listOf(130 to 251, 189 to 251, 130 to 294, 189 to 294))
        val localTarget = File(HostSession.artifacts, "local-drag-target-shadow")
        HostSession.peer("--native-drag-local-target", localTarget.path)
        expectVisibleInTask(target, "Drag received=Android drag 😀 café")
        expectVisibleInTask(source, "Drag completed elsewhere")
        assertLocalShadow(localTarget, listOf(770 to 251, 829 to 251, 770 to 294, 829 to 294))
        // Without native offers, raw source coordinates must not accidentally
        // target the other synthetic Android rectangle.
        HostSession.peer("--native-drag-cross-task")
        expectVisibleInTask(source, "Drag cancelled")
        expectVisibleInTask(target, "Drag cancelled")
        clickInSource("RESTORE DRAG PROTOCOL")
        HostSession.peer("--native-drag-cross-task")
        expectVisibleInTask(target, "Drag received=Android file 😀 café")
        expectVisibleInTask(source, "Drag exported")

        click("CLOSE DRAG TASK")
        // The surviving task keeps presenting after its drop target went away.
        repeat(16) {
            val screen = HostSession.capture()
            assertEquals("drag content disappeared", listOf(220, 235, 255), screen.pixel(50, 200))
            assertEquals("drag content disappeared", listOf(210, 250, 220), screen.pixel(400, 200))
            SystemClock.sleep(50)
        }
    }

    private fun tasks() = ActivityTaskManager.getService().getTasks(8, false, false, Display.DEFAULT_DISPLAY).map { it.taskId }

    private fun awaitSecondTask(source: Int): Int {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            tasks().firstOrNull { it != source }?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "the second drag task never started" }
            SystemClock.sleep(50)
        }
    }

    /** Both tasks show the same buttons once the second is open; the first task's come first. */
    private fun clickInSource(button: String) {
        val matches = dev.artzig.uitest.UiTest.device.findObjects(By.text(button))
        matches.minBy { it.visibleBounds.left }.click()
    }

    private fun dragAcross(expected: String) {
        val area = waitFor(By.desc("Drag contract area")).visibleBounds
        WaylandSeat.drag(Point(area.left + area.width() / 4, area.centerY()), Point(area.left + area.width() * 3 / 4, area.centerY()))
        expectVisible(expected)
    }

    /** A native client drags `payload` onto the task; `outcome` is what it must observe. */
    private fun nativeSource(name: String, mime: String, payload: String, expected: String, outcome: String = "accept") {
        val report = File(HostSession.artifacts, "native-drag-$name.json").apply { delete() }
        HostSession.peer("--native-drag-source", mime, payload, report.path, outcome)
        expectVisible(expected)
        val state = JSONObject(report.readText())
        assertTrue(state.toString(), state.isNull("failure") && state.getInt("sends") > 0)
        assertEquals(state.toString(), outcome != "cancel", state.getBoolean("dropped"))
        assertEquals(state.toString(), outcome == "accept", state.getBoolean("finished"))
        assertEquals(state.toString(), outcome != "accept", state.getBoolean("cancelled"))
    }

    /** The task drags onto a native client, which captures the compositor while the shadow is up. */
    private fun nativeDestination(name: String, expected: String, outcome: String = "accept", shadow: String = "normal") {
        val report = File(HostSession.artifacts, "android-drag-$name.json").apply { delete() }
        HostSession.peer("--native-drag-destination", report.path, if (shadow == "updating") "accept-update" else outcome)
        expectVisible(if (outcome == "accept") "Drag exported" else "Drag cancelled")
        val state = JSONObject(report.readText())
        assertTrue(state.toString(), state.isNull("failure"))
        assertEquals(state.toString(), outcome == "accept", state.getBoolean("dropped"))
        assertEquals(state.toString(), outcome == "accept", state.getBoolean("finished"))
        if (name == "file") {
            val exported = state.getString("uris").lines().filter(String::isNotBlank).map { File(URI(it.trim())) }
            assertEquals(expected, exported.single().readText())
        } else {
            assertEquals(expected, state.getString("text"))
        }
        assertShadow(HostSession.Capture(File(report.path + ".ppm").readBytes()), shadow, updated = false)
        if (shadow == "updating") assertShadow(HostSession.Capture(File(report.path + ".updated.ppm").readBytes()), shadow, updated = true)
    }

    /** The stock shadow's colour, bounds, hotspot and padding in the peer's 1280x720 capture. */
    private fun assertShadow(capture: HostSession.Capture, shadow: String, updated: Boolean) {
        assertEquals(1280 to 720, capture.width to capture.height)
        val background = listOf(200, 100, 50)
        if (shadow == "empty") {
            for ((x, y) in listOf(799 to 239, 800 to 240, 801 to 241, 770 to 218, 829 to 261))
                assertEquals("empty shadow acquired opaque pixels at $x,$y", background, capture.pixel(x, y))
            return
        }
        val (left, top) = if (shadow == "offset") 720 to 180 else 768 to 216
        val corners = listOf(2 to 2, 61 to 2, 2 to 45, 61 to 45)
        val colored = if (shadow == "transparent") listOf(18 to 14, 45 to 14, 18 to 34, 45 to 34) else corners
        val color = listOf(if (updated) 238 else 58, 29, 195)
        for ((dx, dy) in colored) {
            val actual = capture.pixel(left + dx, top + dy)
            assertTrue("$shadow shadow colour at $dx,$dy: $actual", actual.zip(color).all { (a, b) -> abs(a - b) <= 2 })
        }
        val outside = listOf(-1 to 2, 64 to 2, 32 to -1, 32 to 48) + if (shadow == "transparent") corners else emptyList()
        for ((dx, dy) in outside) {
            val x = left + dx
            assertEquals("$shadow shadow bounds at $dx,$dy", if (x < 720) listOf(0, 0, 0) else background, capture.pixel(x, top + dy))
        }
        val text = (14 until 32).sumOf { y -> (16 until 48).count { x -> capture.pixel(left + x, top + y).all { it > 120 } } }
        assertTrue("the stock DRAG text is absent", text > 20)
    }

    private fun assertLocalShadow(report: File, points: List<Pair<Int, Int>>) {
        val capture = HostSession.Capture(File(report.path + ".ppm").readBytes())
        for ((x, y) in points) {
            val (r, g, b) = capture.pixel(x, y)
            assertTrue("no local drag shadow at $x,$y: $r,$g,$b", r < 100 && g < 100 && b > 200)
        }
    }

    private companion object {
        val ROUNDS = System.getenv("AZK_TEST_DRAG_ROUNDS")?.toInt() ?: 16
    }
}
