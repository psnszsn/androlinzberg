package dev.artzig.fixtures.windows

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Frame submission and stopped-window callbacks through an actual APK window.
 * Pinned ViewRootImpl.performDraw captures commit callbacks before onDraw and
 * posts their completion back to main after the renderer commits the frame. */
class FrameFixtureActivity : Activity() {
    private val events = ArrayList<String>()
    private lateinit var probe: View
    private lateinit var status: TextView
    private var draws = 0
    private var complete = false
    private var stopped = false
    private var stopProbeArmed = false
    private var resumed = false
    private var unsubmittedCallback = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Waiting for frame callbacks" }
        val unsubmitted = View(this)
        val pending = Runnable { unsubmittedCallback = true }
        unsubmitted.viewTreeObserver.registerFrameCommitCallback(pending)
        probe = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                draws++
                if (draws == 1) {
                    viewTreeObserver.registerFrameCommitCallback {
                        onMain()
                        check(events == listOf("first", "second")) { events }
                        check(draws >= 2 && !unsubmittedCallback)
                        check(unsubmitted.viewTreeObserver.unregisterFrameCommitCallback(pending))
                        check(!unsubmitted.viewTreeObserver.unregisterFrameCommitCallback(pending))
                        events += "fromDraw"
                        complete = true
                        setBackgroundColor(Color.BLUE)
                        status.text = "Frame callbacks verified"
                        Log.i("FrameFixture", "commit order=first,second,fromDraw; unattached callback not submitted")
                    }
                }
            }
        }.apply { setWillNotDraw(false); setBackgroundColor(Color.RED) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(probe, LinearLayout.LayoutParams(-1, 160))
            addView(Button(this@FrameFixtureActivity).apply {
                text = "Cover frame window"
                setOnClickListener {
                    check(complete && events == listOf("first", "second", "fromDraw"))
                    startActivity(Intent(this@FrameFixtureActivity, FrameCoverActivity::class.java))
                }
            })
        }
        setContentView(content)
        probe.viewTreeObserver.registerFrameCommitCallback {
            onMain()
            check(events.isEmpty() && draws >= 1 && !unsubmittedCallback)
            // Pinned View.getRootWindowInsets queries the root snapshot. Decor
            // may consume insets before dispatch to content, so a child callback
            // is not required for this root-insets contract.
            val insets = checkNotNull(probe.rootWindowInsets)
            check(!insets.isConsumed) { "Empty root insets lost their unconsumed state" }
            check(insets.getInsets(android.view.WindowInsets.Type.systemBars()) == android.graphics.Insets.NONE)
            events += "first"
            probe.setBackgroundColor(Color.GREEN)
        }
        probe.viewTreeObserver.registerFrameCommitCallback {
            onMain()
            check(events == listOf("first")) { events }
            events += "second"
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (complete) stopProbeArmed = true
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        if (stopProbeArmed && !resumed) {
            resumed = true
            probe.viewTreeObserver.registerFrameCommitCallback {
                onMain()
                check(!stopped && resumed)
                check(events == listOf("first", "second", "fromDraw")) { events }
                events += "resumed"
                probe.setBackgroundColor(Color.CYAN)
                status.text = "Resumed frame committed"
                Log.i("FrameFixture", "stop,resume,commit; callbacks completed once on main")
            }
            probe.invalidate()
        }
    }

    private fun onMain() {
        check(Looper.myLooper() === Looper.getMainLooper())
    }
}

class FrameCoverActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(Button(this).apply {
            text = "Return to frame window"
            setOnClickListener { finish() }
        })
    }
}
