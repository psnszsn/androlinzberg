package dev.artzig.uitest.host

import android.app.ActivityTaskManager
import android.graphics.Point
import android.os.ServiceManager
import android.os.SystemClock
import android.view.Display
import dev.artzig.window.IWaylandTaskPresenter

/**
 * Host-path input for the tests in this package: real Wayland virtual-pointer
 * and virtual-keyboard events produced by the task presenter's inspection
 * methods, so they cross the compositor, the presenter's seat translation and
 * the stock InputDispatcher before reaching the app. Ordinary UI tests inject
 * through UiAutomation instead; only these tests exist to cover the host seam.
 * Assertions still go through the Android APIs.
 *
 * Points are Android screen coordinates, as `UiObject2.visibleCenter` reports.
 */
object WaylandSeat {
    private val presenter: IWaylandTaskPresenter by lazy {
        IWaylandTaskPresenter.Stub.asInterface(ServiceManager.waitForService("dev.artzig.window.presenter"))
    }

    /** The presenter's view of the focused task: see IWaylandTaskPresenter.testWindow. */
    class Window(val taskId: Int, private val values: LongArray) {
        val generation get() = values[0]
        val geometry get() = IntArray(8) { values[it + 1].toInt() }
        val fullscreen get() = values[9] and 1L != 0L
        val configurationGeneration get() = values[10]
    }

    fun window(): Window {
        val task = ActivityTaskManager.getService().getTasks(1, false, false, Display.DEFAULT_DISPLAY).single()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            val values = presenter.testWindow(task.taskId)
            if (values.size == 16 && values[0] > 0) return Window(task.taskId, values)
            check(SystemClock.uptimeMillis() < deadline) { "task ${task.taskId} has no presented window" }
            Thread.sleep(50)
        }
    }

    fun tap(at: Point) = inject(TAP, at, at, "")
    fun drag(from: Point, to: Point) = inject(DRAG, from, to, "")
    /** Taps `at` for keyboard focus, then presses the named key (e.g. "F11"). */
    fun tapThenKey(at: Point, key: String) = inject(TAP_THEN_KEY, at, at, key)

    /** Retries while the presenter reports geometry in transition (sequence 0). */
    private fun inject(kind: Int, from: Point, to: Point, text: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            val target = window()
            val sequence = presenter.injectTestInput(target.taskId, target.generation, target.configurationGeneration,
                target.geometry, kind, intArrayOf(from.x, from.y, to.x, to.y), text)
            check(sequence >= 0) { "compositor input completion is unknown; not retrying" }
            if (sequence > 0) return
            check(SystemClock.uptimeMillis() < deadline) { "the presenter kept rejecting input for task ${target.taskId}" }
            Thread.sleep(100)
        }
    }

    private const val TAP = 0
    private const val DRAG = 1
    private const val TAP_THEN_KEY = 5
    private const val TIMEOUT = 30_000L
}
