package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** APK coverage of pinned SurfaceView.updateSurface's attachment/visibility
 * lifecycle, including real BLAST buffers and compositor-visible recreation. */
class SurfaceLifecycleFixtureActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this)
        content.addView(status)
        val events = ArrayList<String>()
        var live = false
        var color = Color.BLUE
        var changed = 0
        val surface = SurfaceView(this).apply {
            contentDescription = "Lifecycle surface"
            visibility = View.INVISIBLE
            setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            holder.addCallback(object : SurfaceHolder.Callback2 {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    check(!live && holder.surface.isValid)
                    live = true
                    events.add("created")
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    check(live && holder.surface.isValid && width == 240 && height == 120)
                    changed++
                    paint(holder)
                }
                override fun surfaceRedrawNeeded(holder: SurfaceHolder) = paint(holder)
                private fun paint(holder: SurfaceHolder) {
                    check(live)
                    val canvas = checkNotNull(holder.lockCanvas())
                    try { canvas.drawColor(color) } finally { holder.unlockCanvasAndPost(canvas) }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    check(live)
                    live = false
                    events.add("destroyed")
                }
            })
        }
        content.addView(surface, LinearLayout.LayoutParams(240, 120))
        fun expect(expected: List<String>, label: String) {
            check(events == expected) { "Surface lifecycle: $events, expected $expected" }
            check(live == (expected.last() == "created"))
            status.text = label
            Log.i("SurfaceLifecycleFixture", "$label: ${events.joinToString(",")}")
        }
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Show attached surface") {
            surface.visibility = View.VISIBLE
            surface.post { expect(listOf("created"), "Attached surface blue") }
        }
        button("Hide attached surface") {
            surface.visibility = View.INVISIBLE
            surface.post { expect(listOf("created"), "Hidden attached surface retained") }
        }
        button("Follow visibility") {
            check(surface.visibility == View.INVISIBLE)
            surface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY)
            surface.post { expect(listOf("created", "destroyed"), "Hidden surface destroyed") }
        }
        button("Recreate visible surface") {
            color = Color.MAGENTA
            surface.visibility = View.VISIBLE
            surface.post {
                check(changed >= 2)
                expect(listOf("created", "destroyed", "created"), "Visible surface recreated magenta")
            }
        }
        button("Detach surface") {
            content.removeView(surface)
            content.post {
                expect(listOf("created", "destroyed", "created", "destroyed"), "Detached surface destroyed")
            }
        }
        setContentView(content)
        surface.post {
            // SurfaceView.updateSurface resolves a requested lifecycle strategy
            // on its first pre-draw. Observe creation after the next traversal,
            // when requiresSurfaceControlCreation sees that resolved strategy.
            check(surface.width == 240 && surface.height == 120)
            content.invalidate()
            content.post {
                check(changed > 0) { "No attached surface after layout: $events" }
                expect(listOf("created"), "Invisible attached surface created")
            }
        }
    }
}
