package dev.artzig.testing

import android.os.ServiceManager
import android.os.SystemClock
import dev.artzig.window.IWaylandTaskPresenter
import org.json.JSONObject

/** Main-thread adapter for the existing native Varlink server. The controller
 * observes apps over Binder; it has no application ViewRootImpl of its own. */
object SessionTestControl {
    private lateinit var observation: SessionObservation
    private lateinit var registry: IInspectionRegistry
    private lateinit var presenter: IWaylandTaskPresenter
    private var nextPoll = 0L
    private var needFrames = true
    private var pendingInput = 0L
    private data class SelectedInput(val target: SessionObservation.InputTarget, val generation: Long, val configurationGeneration: Long, val geometry: IntArray)
    private var selectedInput: SelectedInput? = null

    fun start() {
        registry = IInspectionRegistry.Stub.asInterface(
            checkNotNull(ServiceManager.checkService(ProcessInspection.SERVICE)))
        observation = SessionObservation(registry)
        presenter = IWaylandTaskPresenter.Stub.asInterface(
            checkNotNull(ServiceManager.checkService("dev.artzig.window.presenter")))
    }

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now < nextPoll) return
        nextPoll = now + 20
        if (pendingInput > 0 && registry.inputIdle(pendingInput)) pendingInput = 0
        observation.refresh()
        val frames = presenter.observeTestFrames(needFrames)
        check(frames.size == 2 && frames[0] >= frames[1] && frames[1] >= 0)
        needFrames = poll(frames[0], frames[1]) || frames[1] == 0L
    }

    @JvmStatic fun selectInputWindow(revision: Long, windowId: Long, viewId: Int, virtualId: Int): Boolean {
        selectedInput = null
        val target = observation.inputTarget(revision, windowId, viewId, virtualId) ?: return false
        val geometry = presenter.testWindow(target.taskId)
        if (geometry.size != 16 || geometry[0] <= 0) return false
        check((1..8).all { geometry[it] in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() })
        selectedInput = SelectedInput(target, geometry[0], geometry[10], IntArray(8) { geometry[it + 1].toInt() })
        return true
    }

    @JvmStatic fun injectInput(kind: Int, x1: Int, y1: Int, x2: Int, y2: Int, text: String): Boolean {
        val selected = selectedInput ?: return false
        if (!observation.validateInputTarget(selected.target)) return false
        val sequence = presenter.injectTestInput(selected.target.taskId, selected.generation, selected.configurationGeneration,
            selected.geometry, kind, intArrayOf(x1, y1, x2, y2), text)
        check(sequence >= 0) { "Compositor input completion is unknown; do not retry this action" }
        if (sequence == 0L) return false
        pendingInput = sequence
        return true
    }

    @JvmStatic fun inputFullscreen(): Boolean {
        val windows = JSONObject(observation.snapshot()).getJSONArray("windows")
        val active = (0 until windows.length()).map { windows.getJSONObject(it) }.singleOrNull { it.getBoolean("active") }
            ?: return false
        if (active.isNull("task_id")) return false
        val geometry = presenter.testWindow(active.getInt("task_id"))
        return geometry.size == 16 && geometry[9] and 1L != 0L
    }

    @JvmStatic private external fun poll(frameSequence: Long, presentedFrameSequence: Long): Boolean
    @JvmStatic fun accessibilitySnapshot(): String = observation.snapshot()
    @JvmStatic fun accessibilityRevision(): Long = observation.revision()
    @JvmStatic fun accessibilityIdle(): Boolean = pendingInput == 0L && observation.idle()
    @JvmStatic fun accessibilityDiagnostics(afterRevision: Long): String = observation.diagnostics(afterRevision)
    @JvmStatic fun accessibilityPerform(revision: Long, windowId: Long, viewId: Int, virtualId: Int,
        action: Int, text: String?): Boolean = observation.perform(revision, windowId, viewId, virtualId, action, text)
    @JvmStatic fun testDiagnostics(afterLog: Long, afterIssue: Long): String = observation.runtimeDiagnostics(afterLog, afterIssue)
    @JvmStatic fun diagnosticLogSequence(): Long = JSONObject(testDiagnostics(Long.MAX_VALUE, Long.MAX_VALUE)).getLong("log_cursor")
    @JvmStatic fun diagnosticIssueSequence(): Long = JSONObject(testDiagnostics(Long.MAX_VALUE, Long.MAX_VALUE)).getLong("issue_cursor")
    @JvmStatic fun notificationsSnapshot(): String = dev.artzig.NotificationObservation.sessionSnapshot()
}
