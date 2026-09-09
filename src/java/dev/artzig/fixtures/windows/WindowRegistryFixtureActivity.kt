package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Exercises pinned WindowManagerGlobal registration, lookup and deferred
 * closeAllExceptView semantics with WMS-owned application and panel windows. */
class WindowRegistryFixtureActivity : Activity() {
    private val attached = ArrayList<View>()
    private var listener: Consumer<List<View>>? = null
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Window registry ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(this@WindowRegistryFixtureActivity).apply {
                text = "Check window registry"
                setOnClickListener { isEnabled = false; verifyRegistry() }
            })
        })
    }

    private fun verifyRegistry() {
        val global = WindowManagerGlobal.getInstance()
        val parent = window.decorView
        val parentRoot = checkNotNull(parent.viewRootImpl)
        val token = checkNotNull(parentRoot.mWindowAttributes.token)
        val before = global.windowViews
        val changes = ArrayList<List<View>>()
        val observer = Consumer<List<View>> { changes.add(ArrayList(it)) }
        listener = observer
        global.addWindowViewsListener(Executor { it.run() }, observer)
        global.addWindowViewsListener(Executor { it.run() }, observer)
        check(changes == listOf(before))
        fun add(name: String, panel: Boolean, detached: (() -> Unit)? = null): View {
            val view = object : View(this) {
                override fun onDetachedFromWindow() {
                    detached?.invoke()
                    super.onDetachedFromWindow()
                }
            }.apply { setBackgroundColor(Color.CYAN) }
            val attrs = WindowManager.LayoutParams(60, 60,
                if (panel) WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                else WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.OPAQUE).apply {
                this.token = if (panel) parent.windowToken else token
                title = name
                gravity = Gravity.TOP or Gravity.LEFT
                x = 16
                y = 160
            }
            windowManager.addView(view, attrs)
            attached.add(view)
            return view
        }
        val child = add("Registry panel", true)
        child.post {
            val childRoot = checkNotNull(child.viewRootImpl)
            check(global.getWindowView(parent.windowToken) === parent)
            check(global.getWindowView(child.windowToken) === child)
            check(global.getRootViews(token) == listOf(parentRoot, childRoot))
            check(global.getRootViews(Binder()).isEmpty())
            val name = "${parentRoot.mWindowAttributes.title}/android.view.ViewRootImpl@${Integer.toHexString(parentRoot.hashCode())}"
            check(name in global.viewRootNames && global.getRootView(name) === parent)
            check(global.getRootView("unknown") == null)
            check(changes.last() == before + child)
            global.windowViews.clear()
            check(global.windowViews == before + child)

            // ViewRootImpl.setLayoutParams preserves values owned by the root
            // while applying new caller attributes, without mutating cutout mode.
            val old = childRoot.mWindowAttributes
            old.systemUiVisibility = 0x100
            old.subtreeSystemUiVisibility = 0x200
            old.insetsFlags.appearance = 8
            old.insetsFlags.behavior = 2
            old.surfaceInsets.set(1, 2, 3, 4)
            old.hasManualSurfaceInsets = true
            old.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            val updated = WindowManager.LayoutParams().apply {
                copyFrom(child.layoutParams as WindowManager.LayoutParams)
                // LayoutParams.isFullscreen also requires a zero origin.
                x = 0
                y = 0
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
                privateFlags = privateFlags or WindowManager.LayoutParams.PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                preservePreviousSurfaceInsets = true
            }
            windowManager.updateViewLayout(child, updated)
            check(child.layoutParams === updated && old.width == WindowManager.LayoutParams.MATCH_PARENT)
            check(old.systemUiVisibility == 0x100 && old.subtreeSystemUiVisibility == 0x200)
            check(old.insetsFlags.appearance == 8 && old.insetsFlags.behavior == 2)
            check(old.surfaceInsets == Rect(1, 2, 3, 4) && old.hasManualSurfaceInsets)
            check(old.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            check(old.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
            check(updated.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER)

            val closed = ArrayList<Int>()
            val temporary = (1..2).map { index -> add("Registry temporary $index", false) { closed.add(index) } }
            temporary.last().post {
                check(temporary.all { it.isAttachedToWindow })
                global.closeAllExceptView(token, parent, null, null)
                check(closed.isEmpty()) { "Removal was not deferred" }
                check(temporary.all { it in global.windowViews && it.parent == null })
                parent.post {
                    check(closed == listOf(1, 2)) { "Deferred detach order: $closed" }
                    check(temporary.none { it.isAttachedToWindow || it in global.windowViews })
                    attached.removeAll(temporary.toSet())
                    check(parent in global.windowViews && child in global.windowViews)
                    windowManager.removeViewImmediate(child)
                    attached.remove(child)
                    check(changes.last() == before && global.windowViews == before)
                    global.removeWindowViewsListener(observer)
                    listener = null
                    status.text = "Window registry verified"
                    status.setBackgroundColor(Color.CYAN)
                    Log.i("WindowRegistryFixture", "listener deduplication, snapshots, token and name lookup, layout preservation, deferred close and panel retention verified")
                }
            }
        }
    }

    override fun onDestroy() {
        listener?.let { WindowManagerGlobal.getInstance().removeWindowViewsListener(it) }
        for (view in attached.toList()) if (view in WindowManagerGlobal.getInstance().windowViews) windowManager.removeViewImmediate(view)
        attached.clear()
        super.onDestroy()
    }
}
