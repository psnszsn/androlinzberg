package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Real pointer streams through the task's native input channel and pinned
 * ViewGroup.dispatchTouchEvent/cancelTouchTarget and AbsSeekBar.onTouchEvent. */
class InputRoutingFixtureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var region: android.widget.FrameLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Input routing ready" }
        region = android.widget.FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(region, LinearLayout.LayoutParams(-1, 180))
            for (mode in listOf("siblings", "intercept down", "intercept move", "detach", "seek")) {
                addView(Button(this@InputRoutingFixtureActivity).apply {
                    text = "Route $mode"
                    setOnClickListener { select(mode) }
                })
            }
        })
    }

    private fun verified(message: String) {
        status.text = message
        status.setBackgroundColor(Color.CYAN)
        Log.i("InputRoutingFixture", message)
    }

    private fun select(mode: String) {
        region.removeAllViews()
        status.text = "Ready: $mode"
        status.setBackgroundColor(Color.TRANSPARENT)
        if (mode != "seek") {
            region.addView(RoutedGroup(mode), android.widget.FrameLayout.LayoutParams(-1, -1))
            return
        }
        val events = ArrayList<String>()
        region.addView(SeekBar(this).apply {
            contentDescription = "Seek pointer control"
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { events.add("start") }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    check(fromUser && events.firstOrNull() == "start")
                    events.add("change")
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    events.add("stop")
                    check(events.first() == "start" && events.last() == "stop")
                    check(events.count { it == "start" } == 1 && events.count { it == "stop" } == 1)
                    check("change" in events && bar.progress >= 70)
                    verified("Seek drag start, change and stop verified")
                }
            })
        }, android.widget.FrameLayout.LayoutParams(-1, 100))
    }

    private inner class RoutedGroup(private val mode: String) : ViewGroup(this@InputRoutingFixtureActivity) {
        private val events = ArrayList<String>()
        private var removed = false
        init {
            contentDescription = "Pointer routing region"
            setBackgroundColor(Color.LTGRAY)
            addView(child("lower", Color.BLUE))
            if (mode == "siblings") addView(child("upper", Color.MAGENTA).apply {
                // The visible target lies outside its untransformed bounds.
                translationX = 180f
                scaleX = .75f
                scaleY = .8f
            })
        }

        private fun source(event: MotionEvent) {
            // ViewGroup.cancelTouchTarget creates its own cancellation event
            // when a view is removed; it does not carry a device source.
            if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                check(event.isFromSource(InputDevice.SOURCE_MOUSE))
                check(event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
            }
        }

        private fun child(name: String, color: Int) = object : View(this@InputRoutingFixtureActivity) {
            init { contentDescription = "Pointer $name child"; setBackgroundColor(color) }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                source(event)
                if (name == "upper" && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    check(event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat())
                    val matrix = android.graphics.Matrix()
                    transformMatrixToGlobal(matrix)
                    val point = floatArrayOf(event.x, event.y)
                    matrix.mapPoints(point)
                    check(kotlin.math.abs(point[0] - event.rawX) <= 1f &&
                        kotlin.math.abs(point[1] - event.rawY) <= 1f) {
                        "Transformed local/raw mismatch: ${point.toList()} vs ${event.rawX},${event.rawY}"
                    }
                }
                events.add("$name:${event.actionMasked}")
                if (mode == "detach" && event.actionMasked == MotionEvent.ACTION_MOVE && !removed) {
                    removed = true
                    removeView(this)
                }
                if (mode == "siblings" && event.actionMasked == MotionEvent.ACTION_UP) {
                    check(events.filterNot { it.endsWith(":${MotionEvent.ACTION_MOVE}") } == listOf("upper:0", "upper:1")) { events }
                    verified("Overlapping sibling routing verified")
                }
                return true
            }
            override fun onDetachedFromWindow() {
                if (mode == "detach" && removed) {
                    check(events.last() == "lower:${MotionEvent.ACTION_CANCEL}") { events }
                    events.add("detach")
                }
                super.onDetachedFromWindow()
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val height = MeasureSpec.getSize(heightSpec)
            for (i in 0 until childCount) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(if (mode == "siblings" && i == 1) 120 else width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(if (mode == "siblings" && i == 1) 100 else height, MeasureSpec.EXACTLY))
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
            mode == "intercept down" || (mode == "intercept move" && event.actionMasked == MotionEvent.ACTION_MOVE)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            source(event)
            events.add("parent:${event.actionMasked}")
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                when (mode) {
                    "intercept down" -> {
                        check(events.filterNot { it.endsWith(":${MotionEvent.ACTION_MOVE}") } == listOf("parent:0", "parent:1")) { events }
                        verified("Down interception bypasses child verified")
                    }
                    "intercept move" -> {
                        check(events.filter { it.startsWith("lower:") } == listOf("lower:0", "lower:3")) { events }
                        check(events.indexOf("lower:3") < events.indexOf("parent:2") && events.last() == "parent:1") { events }
                        verified("Move interception cancels child verified")
                    }
                    "detach" -> {
                        check(removed && childCount == 0)
                        check(events.count { it == "lower:3" } == 1 && events.indexOf("lower:3") < events.indexOf("detach")) { events }
                        check(events.drop(events.indexOf("detach") + 1).all { it.startsWith("parent:") }) { events }
                        verified("Removed target canceled before detach verified")
                    }
                    else -> error("Unexpected parent input: $mode $events")
                }
            }
            return true
        }
    }
}
