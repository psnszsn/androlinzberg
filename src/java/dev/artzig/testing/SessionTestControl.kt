package dev.artzig.testing

import android.os.ServiceManager
import android.os.SystemClock
import dev.artzig.window.IWaylandTaskPresenter
import org.json.JSONObject

/** Main-thread adapter for the existing native Varlink server. The controller
 * observes apps over Binder; it has no application ViewRootImpl of its own. */
object SessionTestControl {
    private lateinit var observation: SessionObservation
    private lateinit var presenter: IWaylandTaskPresenter
    private var nextPoll = 0L
    private var needFrames = true

    fun start() {
        observation = SessionObservation(IInspectionRegistry.Stub.asInterface(
            checkNotNull(ServiceManager.checkService(ProcessInspection.SERVICE))))
        presenter = IWaylandTaskPresenter.Stub.asInterface(
            checkNotNull(ServiceManager.checkService("dev.artzig.window.presenter")))
    }

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now < nextPoll) return
        nextPoll = now + 20
        observation.refresh()
        val frames = presenter.observeTestFrames(needFrames)
        check(frames.size == 2 && frames[0] >= frames[1] && frames[1] >= 0)
        needFrames = poll(frames[0], frames[1]) || frames[1] == 0L
    }

    @JvmStatic private external fun poll(frameSequence: Long, presentedFrameSequence: Long): Boolean
    @JvmStatic fun accessibilitySnapshot(): String = observation.snapshot()
    @JvmStatic fun accessibilityRevision(): Long = observation.revision()
    @JvmStatic fun accessibilityIdle(): Boolean = observation.idle()
    @JvmStatic fun accessibilityDiagnostics(afterRevision: Long): String = observation.diagnostics(afterRevision)
    @JvmStatic fun accessibilityPerform(revision: Long, windowId: Long, viewId: Int, virtualId: Int,
        action: Int, text: String?): Boolean = observation.perform(revision, windowId, viewId, virtualId, action, text)
    @JvmStatic fun testDiagnostics(afterLog: Long, afterIssue: Long): String = observation.runtimeDiagnostics(afterLog, afterIssue)
    @JvmStatic fun diagnosticLogSequence(): Long = JSONObject(testDiagnostics(Long.MAX_VALUE, Long.MAX_VALUE)).getLong("log_cursor")
    @JvmStatic fun diagnosticIssueSequence(): Long = JSONObject(testDiagnostics(Long.MAX_VALUE, Long.MAX_VALUE)).getLong("issue_cursor")
    @JvmStatic fun notificationsSnapshot(): String = dev.artzig.NotificationObservation.sessionSnapshot()
}
