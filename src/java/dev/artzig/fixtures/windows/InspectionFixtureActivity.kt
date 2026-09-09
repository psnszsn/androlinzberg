package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.artzig.ArtzigAccessibilityBridge
import org.json.JSONObject

/** Test-only client of the hosted inspection API, using an actual APK window.
 * It observes the same stock roots as desktop inspection without attaching a
 * synthetic root or modifying the framework's window lifecycle. */
class InspectionFixtureActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var target: Button
    private lateinit var sibling: TextView
    private var clicks = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Inspection contracts ready" }
        target = Button(this).apply {
            setAllCaps(false)
            text = "target"
            contentDescription = "Inspection target"
            setOnClickListener { clicks++ }
        }
        sibling = TextView(this).apply { text = "before" }
        content.addView(status, LinearLayout.LayoutParams(-1, 60))
        content.addView(target, LinearLayout.LayoutParams(-1, 80))
        content.addView(sibling, LinearLayout.LayoutParams(-1, 80))
        for ((label, action) in listOf("Check physical selection" to ::physical, "Check semantic revisions" to ::semantic)) {
            content.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    isEnabled = false
                    // Let this button's own click accessibility event finish
                    // before observing the revision used by the contract checks.
                    content.post { action() }
                }
            })
        }
        setContentView(content)
    }

    private fun snapshot() = JSONObject(ArtzigAccessibilityBridge.snapshot())
    private fun selected(tree: JSONObject): JSONObject {
        val nodes = tree.getJSONArray("nodes")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }
            .single { it.getInt("view_id") == target.accessibilityViewId }
    }
    private fun verified(message: String) {
        status.text = message
        status.setBackgroundColor(Color.CYAN)
        Log.i("InspectionFixture", message)
    }

    private fun physical() {
        val tree = snapshot()
        val node = selected(tree)
        val expected = JSONObject().put("node", node).put("windows", tree.getJSONArray("windows")).toString()
        val revision = tree.getLong("revision")
        val window = node.getLong("window_id")
        val token = checkNotNull(target.windowToken)
        check(ArtzigAccessibilityBridge.inputWindow(revision, window, expected) == token)
        sibling.text = "after"
        sibling.post {
            check(ArtzigAccessibilityBridge.inputWindow(revision, window, expected) == token) {
                "An unrelated label invalidated an unchanged physical target"
            }
            check(ArtzigAccessibilityBridge.inputWindow(Long.MAX_VALUE, window, expected) == null)
            check(ArtzigAccessibilityBridge.inputWindow(revision, Long.MAX_VALUE, expected) == null)
            target.text = "changed target"
            check(ArtzigAccessibilityBridge.inputWindow(revision, window, expected) == null)
            target.text = "target"
            target.isEnabled = false
            check(ArtzigAccessibilityBridge.inputWindow(revision, window, expected) == null)
            target.isEnabled = true
            target.translationX = 10f
            check(ArtzigAccessibilityBridge.inputWindow(revision, window, expected) == null)
            target.translationX = 0f
            val altered = JSONObject(expected)
            val windows = altered.getJSONArray("windows")
            val selectedWindow = (0 until windows.length()).map { windows.getJSONObject(it) }
                .single { it.getLong("id") == window }
            selectedWindow.put("active", !selectedWindow.getBoolean("active"))
            check(ArtzigAccessibilityBridge.inputWindow(revision, window, altered.toString()) == null)
            verified("Physical selection freshness verified")
        }
    }

    private fun semantic() {
        clicks = 0
        target.text = "first"
        val tree = snapshot()
        val window = selected(tree).getLong("window_id")
        fun perform(revision: Long) = ArtzigAccessibilityBridge.perform(revision, window,
            target.accessibilityViewId, AccessibilityNodeProvider.HOST_VIEW_ID,
            AccessibilityNodeInfo.ACTION_CLICK, null)
        target.text = "second"
        check(!perform(tree.getLong("revision")) && clicks == 0)
        check(perform(ArtzigAccessibilityBridge.revision()) && clicks == 1)
        val root = checkNotNull(target.viewRootImpl)
        val eventMark = ArtzigAccessibilityBridge.revision()
        // Pinned ViewRootImpl.requestSendAccessibilityEvent flushes pending
        // content-change notifications before sending the click event.
        root.notifySubtreeAccessibilityStateChanged(target, target, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
        target.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        val journal = JSONObject(ArtzigAccessibilityBridge.diagnostics(eventMark)).getJSONArray("events")
        val reasons = (0 until journal.length()).map { journal.getJSONObject(it).getString("reason") }
        check(reasons.indexOf("event:2048") in 0 until reasons.indexOf("event:1")) { reasons }
        content.post {
            val drawMark = ArtzigAccessibilityBridge.revision()
            val before = snapshot().getJSONArray("nodes").toString()
            target.viewTreeObserver.registerFrameCommitCallback {
                content.post {
                    check(ArtzigAccessibilityBridge.revision() > drawMark)
                    check(snapshot().getJSONArray("nodes").toString() == before)
                    check(perform(drawMark) && clicks == 2) { "Paint-only redraw invalidated the semantic target" }
                    check(!perform(drawMark)) { "A saved semantic action was applied twice" }
                    repeat(140) { ArtzigAccessibilityBridge.markChanged("inspection-contract:$it") }
                    val bounded = JSONObject(ArtzigAccessibilityBridge.diagnostics(0))
                    check(bounded.getJSONArray("events").length() == 128)
                    check(bounded.getLong("events_dropped_through") > 0)
                    verified("Semantic revisions, event order and bounded journal verified")
                }
            }
            target.invalidate()
        }
    }
}
