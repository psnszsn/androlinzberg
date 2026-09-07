package dev.artzig

import android.graphics.Canvas
import android.graphics.Color
import android.os.Binder
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewRootImpl
import android.view.WindowManager
import android.view.WindowManagerGlobal

/** Real desktop submission regression for stock frame callbacks and preserved window rebuilds. */
object FrameCommitProbe {
    private lateinit var root: ViewRootImpl
    private lateinit var view: View
    private lateinit var unsubmitted: ViewRootImpl
    private var unsubmittedCallback = false
    private val token = Binder()
    private val events = ArrayList<String>()
    private var draws = 0
    private var registeredDuringDraw = false
    private var rebuilt = false
    private var complete = false
    private var renderedFrameTime = Long.MIN_VALUE

    @JvmStatic fun drawHardware(width: Int, height: Int, bufferWidth: Int, bufferHeight: Int, scale120: Int) {
        if (!::root.isInitialized) {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
                width, height, scale120, 0)
            // A second traversed root is deliberately omitted from presentation.
            // Its callback must stay pending and be canceled when it is destroyed.
            unsubmitted = ViewRootImpl(context)
            val other = View(context)
            unsubmitted.setView(other, 8, 8)
            other.viewTreeObserver.registerFrameCommitCallback { unsubmittedCallback = true }
            checkNotNull(unsubmitted.doTraversal())
            view = object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    draws++
                    if (!registeredDuringDraw) {
                        registeredDuringDraw = true
                        viewTreeObserver.registerFrameCommitCallback {
                            check(Looper.myLooper() === Looper.getMainLooper())
                            check(events == listOf("first", "second")) { events }
                            check(draws >= 2 && rebuilt && !unsubmittedCallback)
                            events += "fromDraw"
                            complete = true
                            setBackgroundColor(Color.BLUE)
                            Log.i("FrameCommitProbe", "PASS: callbacks run once on UI thread in order; onDraw registration waits for next frame; unsubmitted root excluded; stopped root rebuilt")
                        }
                    }
                }
            }.apply { setWillNotDraw(false); setBackgroundColor(Color.RED) }
            val params = WindowManager.LayoutParams().apply { this.token = FrameCommitProbe.token }
            root = HostedWindowRoots.attach(view, width, height, params)
            view.viewTreeObserver.registerFrameCommitCallback {
                check(Looper.myLooper() === Looper.getMainLooper())
                check(events.isEmpty() && draws == 1 && !unsubmittedCallback)
                unsubmitted.destroy()
                events += "first"
                WindowManagerGlobal.getInstance().setStoppedState(token, true)
                val before = draws
                check(root.doTraversal() == null && draws == before)
                view.setBackgroundColor(Color.GREEN)
                root.notifyChildRebuilt()
                rebuilt = true
            }
            view.viewTreeObserver.registerFrameCommitCallback {
                check(Looper.myLooper() === Looper.getMainLooper())
                check(events == listOf("first")) { events }
                events += "second"
            }
        }
        renderedFrameTime = Choreographer.getInstance().lastFrameTimeNanos
        HostedRenderer.getInstance().render(root.doTraversal(), width, height, bufferWidth, bufferHeight, root)
        if (complete) check(events == listOf("first", "second", "fromDraw")) { events }
    }

    @JvmStatic fun pumpMainLooper(): Long = ViewHost.pumpMainLooper()
    @JvmStatic fun needsFrame(): Boolean = Choreographer.getInstance().lastFrameTimeNanos > renderedFrameTime
    @JvmStatic fun shutdown() {
        check(complete && draws >= 3) { "Frame callbacks did not complete: $events, draws=$draws" }
        root.destroy()
        HostedRenderer.destroyInstance()
    }
}
