package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Pinned ViewRootImpl.performLayout permits a second pass for requestLayout
 * during layout. Its traversal barrier also orders pending geometry before
 * synchronous View.post callbacks. Exercise both through an actual APK window. */
class LayoutFixtureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var probe: ReentrantLayout
    private var first: PopupWindow? = null
    private var second: PopupWindow? = null
    private var underlyingClicks = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Waiting for layout checks" }
        probe = ReentrantLayout().apply { setWillNotDraw(false); setBackgroundColor(Color.BLUE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(probe, LinearLayout.LayoutParams(-1, 160))
            addView(Button(this@LayoutFixtureActivity).apply {
                text = "Show popup windows"
                setOnClickListener { showPopups() }
            })
            // Leave a region for two separate popup windows above this control.
            addView(View(this@LayoutFixtureActivity), LinearLayout.LayoutParams(-1, 240))
            addView(Button(this@LayoutFixtureActivity).apply {
                text = "Increment underlying"
                setOnClickListener {
                    underlyingClicks++
                    text = "Underlying clicks: $underlyingClicks"
                }
            })
        }
        setContentView(content)
        var posted = false
        probe.post {
            check(probe.width > 0 && probe.height == 160 && probe.draws > 0)
            posted = true
        }
        afterCommit {
            val before = probe.layouts
            probe.invalidate()
            afterCommit {
                check(probe.layouts == before) { "Draw-only invalidation performed layout" }
                var observedTop = -1
                probe.childTop = 100
                probe.requests = 1
                probe.requestLayout()
                probe.child.post { observedTop = probe.child.top }
                afterCommit {
                    check(probe.child.top == 100)
                    check(probe.layouts == before + 2) { "Reentrant request expected two passes: ${probe.layouts - before}" }
                    probe.invalidate()
                    afterCommit {
                        check(probe.layouts == before + 2 && posted) { "Duplicate layout or premature posted callback" }
                        // The commit callback can precede synchronous messages;
                        // observe the posted result from a later queue turn.
                        probe.post {
                            check(observedTop == 100) { "View.post observed stale pending geometry: $observedTop" }
                            status.text = "Layout scheduling verified"
                            Log.i("LayoutFixture", "draw-only invalidation; reentrant second pass; post after layout; no duplicate traversal")
                        }
                    }
                }
            }
        }
    }

    private fun afterCommit(callback: () -> Unit) {
        probe.viewTreeObserver.registerFrameCommitCallback {
            check(Looper.myLooper() === Looper.getMainLooper())
            callback()
        }
    }

    private fun showPopups() {
        check(first == null && second == null)
        val posted = ArrayList<String>()
        fun popup(name: String, width: Int, height: Int, color: Int, x: Int): PopupWindow {
            val view = FrameLayout(this).apply {
                setBackgroundColor(color)
                contentDescription = "Close $name popup"
                isClickable = true
            }
            val popup = PopupWindow(view, width, height, false).apply {
                setBackgroundDrawable(ColorDrawable(color))
                elevation = 0f
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setOnDismissListener {
                    if (name == "first") first = null else second = null
                    if (first == null && second == null) status.text = "Popup windows dismissed"
                }
            }
            view.setOnClickListener { popup.dismiss() }
            popup.showAtLocation(probe, Gravity.TOP or Gravity.LEFT, x, 240)
            view.post {
                check(view.width == width && view.height == height)
                posted += name
                if (posted.size == 2) {
                    check(posted == listOf("first", "second")) { posted }
                    status.text = "Two popup windows laid out"
                    Log.i("LayoutFixture", "shared main queue: first=80x90,second=120x130")
                }
            }
            return popup
        }
        first = popup("first", 80, 90, Color.CYAN, 16)
        second = popup("second", 120, 130, Color.MAGENTA, 160)
    }

    override fun onDestroy() {
        first?.dismiss()
        second?.dismiss()
        super.onDestroy()
    }

    private inner class ReentrantLayout : ViewGroup(this@LayoutFixtureActivity) {
        val child = View(this@LayoutFixtureActivity)
        var childTop = 10
        init { addView(child) }
        var layouts = 0
        var draws = 0
        var requests = 0
        override fun onMeasure(width: Int, height: Int) {
            child.measure(MeasureSpec.makeMeasureSpec(20, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(20, MeasureSpec.EXACTLY))
            setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(height))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            layouts++
            child.layout(0, childTop, 20, childTop + 20)
            // View.layout clears its own FORCE_LAYOUT bit on return. Request
            // from the already-laid-out child, as pinned performLayout expects.
            if (requests > 0) { requests--; child.requestLayout() }
        }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); draws++ }
    }
}
