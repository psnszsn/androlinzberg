package dev.artzig.fixtures.windows

import android.app.Activity
import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Real View/WMS drag contracts. The handle starts on DOWN, so the existing
 * Wayland drag action supplies the actual pressed gesture and seat serial. */
class DragFixtureActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val targetTask = intent.getBooleanExtra("drag-target", false)
        if (targetTask) setTaskDescription(android.app.ActivityManager.TaskDescription.Builder().setLabel("Drag target").build())
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply { text = "Drag ready" }
        content.addView(status)
        var files = false
        var slow = false
        var accept = true
        val events = ArrayList<String>()
        var received: String? = null
        var sourceGesture = false
        var gestureGeneration = 0
        var shadowMode = 0
        val shadowModes = listOf("Normal", "Offset", "Empty", "Updating", "Transparent")
        fun button(label: String, height: Int = 36, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setPadding(0, 0, 0, 0); setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, height))
        }
        button("Use drag text") { files = false; slow = false; accept = true; status.text = "Drag text ready" }
        button("Use drag file") { files = true; slow = false; status.text = "Drag file ready" }
        button("Reject drag") { accept = false; status.text = "Drag rejection ready" }
        button("Use slow drag file") { files = true; slow = true; accept = true; status.text = "Slow drag file ready" }
        val area = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                paint.color = Color.rgb(220, 235, 255)
                canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
                paint.color = Color.rgb(210, 250, 220)
                canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.color = Color.BLACK; paint.textSize = 24f
                canvas.drawText("Drag handle", 16f, height / 2f, paint)
                canvas.drawText("Drop here", width / 2f + 16f, height / 2f, paint)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && event.x < width / 2f) {
                    events.clear(); received = null; status.text = "Drag started"
                    ClipboardFileProvider.nextOpenDelayMillis.set(if (slow) 800 else 0)
                    val data = if (files) ClipData.newUri(contentResolver, "Drag file", ClipboardFileProvider.URI)
                        else ClipData.newPlainText("Drag text", "Android drag 😀 café")
                    val mode = shadowMode
                    val generation = ++gestureGeneration
                    fun shadow(updated: Boolean = false) = object : DragShadowBuilder(this) {
                        override fun onProvideShadowMetrics(size: Point, touch: Point) {
                            size.set(if (mode == 2) 1 else 64, if (mode == 2) 1 else 48)
                            touch.set(if (mode == 1) 80 else if (mode == 2) 0 else 32,
                                if (mode == 1) 60 else if (mode == 2) 0 else 24)
                        }
                        override fun onDrawShadow(canvas: Canvas) {
                            if (mode == 2) return // View clears the stock producer buffer first.
                            val save = canvas.save()
                            if (mode == 4) canvas.clipRect(16f, 12f, 48f, 36f)
                            val p = Paint().apply { color = if (updated) Color.MAGENTA else Color.BLUE }
                            canvas.drawRect(0f, 0f, 64f, 48f, p)
                            p.color = Color.WHITE; p.textSize = 16f; canvas.drawText("DRAG", 8f, 30f, p)
                            canvas.restoreToCount(save)
                        }
                    }
                    if (mode == 2) {
                        // Pinned View.startDragAndDrop only accepts zero size for
                        // target SDK < P. Modern apps use an empty 1x1 buffer.
                        check(applicationInfo.targetSdkVersion >= 28)
                        val invalid = object : DragShadowBuilder(this) {
                            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                                size.set(0, 0); touch.set(0, 0)
                            }
                        }
                        check(runCatching { startDragAndDrop(data, invalid, null, View.DRAG_FLAG_GLOBAL) }
                            .exceptionOrNull() is IllegalStateException)
                    }
                    sourceGesture = true
                    val started = startDragAndDrop(data, shadow(), null,
                        View.DRAG_FLAG_GLOBAL or if (files) View.DRAG_FLAG_GLOBAL_URI_READ else 0)
                    check(started) { "Stock WMS refused the active drag gesture" }
                    if (mode == 3) postDelayed({
                        if (sourceGesture && gestureGeneration == generation) updateDragShadow(shadow(true))
                    }, 1200)
                    return true
                }
                return event.actionMasked != MotionEvent.ACTION_DOWN
            }
        }.apply {
            contentDescription = "Drag contract area"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> { events.clear(); received = null; events.add("start"); true }
                    DragEvent.ACTION_DRAG_ENTERED -> { events.add("enter"); true }
                    DragEvent.ACTION_DRAG_LOCATION -> { if ("location" !in events) events.add("location"); true }
                    DragEvent.ACTION_DRAG_EXITED -> { events.add("exit"); true }
                    DragEvent.ACTION_DROP -> {
                        events.add("drop")
                        check(event.x >= width / 2f) { "Drop coordinates did not reach the target half" }
                        if (!accept) return@setOnDragListener false
                        if (slow && sourceGesture) check(events.count { it == "enter" } >= 2 && "exit" in events) {
                            "Native takeover did not retire the previous local drag focus: $events"
                        }
                        val item = event.clipData.getItemAt(0)
                        if (event.clipDescription.label == "Drag file")
                            check(item.uri == ClipboardFileProvider.URI) { "Original Android drag URI was replaced by an export" }
                        if (event.clipDescription.hasMimeType("text/html"))
                            check(item.htmlText == "<b>Native rich 😀</b>") { "Drag HTML representation was lost" }
                        val permissions = if (item.uri != null) requestDragAndDropPermissions(event) else null
                        if (item.uri != null) checkNotNull(permissions) { "Missing stock drag URI permission object" }
                        try {
                            received = if (item.uri != null) contentResolver.openInputStream(item.uri!!)!!.bufferedReader().use { it.readText() }
                                else item.text.toString()
                        } finally { permissions?.release() }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        events.add("end")
                        check(events.first() == "start" && events.last() == "end")
                        if ("drop" !in events) {
                            check(received == null) { "A view without DROP received data: $events" }
                            status.text = if (!event.result) "Drag cancelled" else if (sourceGesture) "Drag exported" else "Drag completed elsewhere"
                        } else if (accept) {
                            check(events.indexOf("location") in 1 until events.indexOf("drop")) { events.toString() }
                            check(event.result && received != null) { "Drop was not consumed: $events" }
                            status.text = "Drag received=$received"
                        } else {
                            check("drop" in events && !event.result) { "Rejected drag was consumed: $events" }
                            status.text = "Drag rejected"
                        }
                        sourceGesture = false
                        true
                    }
                    else -> false
                }
            }
        }
        content.addView(area, LinearLayout.LayoutParams(-1, 220))
        if (targetTask) button("Close drag task") { finish() }
        else button("Open drag task") {
            startActivity(android.content.Intent(this, DragFixtureActivity::class.java)
                .putExtra("drag-target", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
        if (!targetTask) {
            var removed = false
            val toggle = Button(this).apply { text = "Remove drag protocol"; setPadding(0, 0, 0, 0) }
            toggle.setOnClickListener {
                removed = !removed
                setTaskDescription(android.app.ActivityManager.TaskDescription.Builder()
                    .setLabel("AZK CLIPBOARD:" + if (removed) "remove" else "restore").build())
                toggle.text = if (removed) "Restore drag protocol" else "Remove drag protocol"
            }
            content.addView(toggle, LinearLayout.LayoutParams(-1, 24))
        }
        button("Next drag shadow", 24) {
            shadowMode = (shadowMode + 1) % shadowModes.size
            status.text = "Drag shadow=" + shadowModes[shadowMode]
        }
        setContentView(content)
    }
}
