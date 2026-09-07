package dev.artzig

import android.graphics.Rect
import android.view.View
import android.view.ViewRootImpl
import android.view.WindowManager
import android.view.WindowManagerGlobal
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Presentation and input state for desktop overlays registered by stock WindowManagerGlobal. */
object HostedWindowStack {
    class Entry internal constructor(
        @JvmField val view: View,
        @JvmField var lp: WindowManager.LayoutParams,
        @JvmField val root: ViewRootImpl,
    ) {
        /** Screen coordinates updated by the host compositor each frame. */
        @JvmField val frame = Rect()
        @JvmField var onBack: Runnable? = null
    }

    private val lock = Any()
    private val observing = AtomicBoolean()
    private val windows = ArrayList<Entry>()
    private var activityRoot: ViewRootImpl? = null
    private val listener = Consumer<List<View>> { views ->
        synchronized(lock) {
            val previous = windows.toList()
            windows.clear()
            for (view in views) {
                val root = view.viewRootImpl
                // Deferred removal clears the parent before doDie removes the registration.
                val existing = previous.firstOrNull { it.view === view && (root == null || it.root === root) }
                if (existing != null) windows.add(existing)
                else if (root != null) windows.add(Entry(view, view.layoutParams as WindowManager.LayoutParams, root))
            }
        }
    }

    private fun observe() {
        // Registration delivers a snapshot synchronously under the global registry lock.
        // Never acquire that lock while holding our presentation lock.
        if (observing.compareAndSet(false, true)) {
            WindowManagerGlobal.getInstance().addWindowViewsListener(Executor { it.run() }, listener)
        }
    }

    @JvmStatic
    fun setActivityRoot(root: ViewRootImpl?) {
        observe()
        synchronized(lock) { activityRoot = root }
    }

    @JvmStatic
    fun onRootDestroyed(root: ViewRootImpl) {
        synchronized(lock) {
            windows.removeAll { it.root === root }
            if (activityRoot === root) activityRoot = null
        }
    }

    /** Snapshot, bottom to top. The activity is presented separately by the desktop host. */
    @JvmStatic
    fun entries(): MutableList<Entry> {
        observe()
        return synchronized(lock) {
            ArrayList(windows.filter { it.root !== activityRoot && !it.root.`isWindowStopped$artzig`() && it.view.visibility == View.VISIBLE }.onEach {
                // Global listeners report membership changes; layout updates replace this object.
                it.lp = it.view.layoutParams as WindowManager.LayoutParams
            })
        }
    }

    @JvmStatic fun top(): Entry? = entries().lastOrNull()
    @JvmStatic fun topFocusable(): Entry? = entries().lastOrNull {
        !it.root.`isWindowStopped$artzig`() && it.lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
    }
    @JvmStatic fun topTouchable(): Entry? = entries().lastOrNull {
        !it.root.`isWindowStopped$artzig`() && it.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
    }
    @JvmStatic fun isEmpty(): Boolean = entries().isEmpty()
    @JvmStatic fun clear() { entries().forEach { it.root.destroy() } }
    @JvmStatic fun setOnBack(decor: View, callback: Runnable?) {
        entries().firstOrNull { it.view === decor }?.onBack = callback
    }
}
