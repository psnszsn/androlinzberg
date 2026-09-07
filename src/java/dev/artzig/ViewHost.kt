/* artzig M2 gate: a bare Activity with one colored View painting through the
 * real Canvas/Drawable/Resources and HWUI display-list stack. */
@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

public final class ViewHost {
    private constructor () {}

    internal final class DemoActivity : Activity() {
        protected override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // theme-resolved window background
            val tv: TypedValue = TypedValue()
            getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
            window.decorView.setBackgroundColor(tv.data)

            val content: FrameLayout = FrameLayout(getBaseContext())
            // one "colored View" that also exercises onDraw through the real Canvas
            val colored: View =
                object : View(getBaseContext()) {
                    private val mPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

                    protected override fun onDraw(canvas: Canvas) {
                        mPaint.setColor(0xff7aa2f7.toInt())
                        canvas.drawRoundRect(RectF(0f, 0f, getWidth().toFloat(), getHeight().toFloat()), 28f, 28f, mPaint)
                        mPaint.setColor(Color.argb(255, 158, 206, 106))
                        canvas.drawCircle(getWidth() * 0.5f, getHeight() * 0.5f, getHeight() * 0.25f, mPaint)
                    }
                }
            val lp: ViewGroup.MarginLayoutParams = ViewGroup.MarginLayoutParams(600, 340)
            lp.leftMargin = 80
            lp.topMargin = 90
            content.addView(colored, lp)

            val text: TextView = TextView(getBaseContext())
            sStatusText = text
            text.setText("Hello from the real TextView — Minikin shapes this.")
            text.setTextSize(24f)
            text.setTextColor(0xffc0caf5.toInt())
            val tlp: ViewGroup.MarginLayoutParams =
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tlp.leftMargin = 80
            tlp.topMargin = 480
            content.addView(text, tlp)

            val button: Button = Button(this@DemoActivity)
            button.setText("Tap me")
            button.setOnClickListener({ v ->
                run {
                    sStatusText.setText("Button clicked! Input dispatch works.")
                    sStatusText.setTextColor(0xff9ece6a.toInt())
                }
            })
            val blp: ViewGroup.MarginLayoutParams =
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.leftMargin = 80
            blp.topMargin = 550
            content.addView(button, blp)

            val inflated: View =
                LayoutInflater
                    .from(this@DemoActivity)
                    .inflate(android.R.layout.simple_list_item_1, null)
            if (inflated is TextView) {
                (inflated as TextView).setText("Inflated simple_list_item_1")
            }
            val ilp: ViewGroup.MarginLayoutParams =
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            ilp.leftMargin = 700
            ilp.topMargin = 480
            content.addView(inflated, ilp)
            setContentView(content)
        }
    }

    companion object {
        private lateinit var sActivity: Activity
        private lateinit var sContext: android.content.Context
        private lateinit var sRoot: android.view.ViewRootImpl
        private lateinit var sStatusText: TextView
        private var sDownTime: Long = 0L
        private var sRenderedFrameTime: Long = Long.MIN_VALUE
        private var sWidth: Int = 0
        private var sHeight: Int = 0
        private var sScale120: Int = 0

/** Pointer event from the platform (Wayland). action: 0=down 1=up 2=move. */
        @JvmStatic public fun motion(
            action: Int,
            x: Float,
            y: Float,
        ) {
            if (!::sActivity.isInitialized) return
            val now: Long = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) sDownTime = now
            val ev: MotionEvent = MotionEvent.obtain(sDownTime, now, action, x, y, 0)
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            sActivity.getWindow().getDecorView().dispatchTouchEvent(ev)
            ev.recycle()
        }

/** Called once from the launcher. */
        @Throws(Exception::class)
        @JvmStatic
        public fun init(
            width: Int,
            height: Int,
            scale120: Int,
        ) {
            sWidth = width
            sHeight = height
            sScale120 = scale120
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            android.graphics.Typeface.loadPreinstalledSystemFontMap()
            val resPath: String = System.getenv("ARTZIG_FRAMEWORK_RES")
            sContext = HostContexts.systemContext(
                resPath,
                width,
                height,
                scale120,
                android.R.style.Theme_DeviceDefault_NoActionBar,
            )
            val thread = android.app.ActivityThread.currentActivityThread()
            val launchIntent = android.content.Intent().setComponent(android.content.ComponentName(
                sContext.packageName, DemoActivity::class.java.name))
            val info = android.content.pm.ActivityInfo().apply {
                packageName = sContext.packageName
                name = DemoActivity::class.java.name
                applicationInfo = sContext.applicationInfo
                theme = android.R.style.Theme_DeviceDefault_NoActionBar
            }
            val record = android.app.ActivityThread.ActivityClientRecord(android.os.Binder(), launchIntent,
                0, info, android.content.res.Configuration(), null, null, null, null, null, null,
                null, false, null, thread, null, null, false, null, null, android.window.ActivityWindowInfo())
            val pending = android.app.servertransaction.PendingTransactionActions()
            sActivity = checkNotNull(thread.handleLaunchActivity(record, pending, 0, null))
            thread.handleStartActivity(record, pending, null)
            thread.handleResumeActivity(record, true, false, false, "demo launch")
            sRoot = checkNotNull(sActivity.window.decorView.viewRootImpl)
            android.view.HostedRootFrames.resize(sRoot, width, height)
            HostedWindowStack.setActivityRoot(sRoot)
            ArtzigAccessibilityBridge.setActivityRoot(sRoot)
        }

        @JvmStatic public fun accessibilitySnapshot(): String = ArtzigAccessibilityBridge.snapshot()

        @JvmStatic public fun notificationsSnapshot(): String = NotificationObservation.snapshot()

        @JvmStatic public fun accessibilityDiagnostics(afterRevision: Long): String =
            ArtzigAccessibilityBridge.diagnostics(
                afterRevision,
            )

        @JvmStatic public fun testDiagnostics(
            afterLogSequence: Long,
            afterIssueSequence: Long,
        ): String = dev.artzig.ArtzigTestDiagnostics.snapshot(afterLogSequence, afterIssueSequence)

        @JvmStatic public fun diagnosticLogSequence(): Long = dev.artzig.ArtzigTestDiagnostics.logSequence()

        @JvmStatic public fun diagnosticIssueSequence(): Long = dev.artzig.ArtzigTestDiagnostics.issueSequence()

        @JvmStatic public fun accessibilityRevision(): Long = ArtzigAccessibilityBridge.revision()

        @JvmStatic public fun accessibilityIdle(): Boolean = ArtzigAccessibilityBridge.isIdle()

        @JvmStatic public fun accessibilityPerform(
            expectedRevision: Long,
            windowId: Long,
            viewId: Int,
            virtualId: Int,
            action: Int,
            text: String?,
        ): Boolean =
            ArtzigAccessibilityBridge.perform(
                expectedRevision,
                windowId,
                viewId,
                virtualId,
                action,
                text,
            )

/** Per-frame: record the view tree; RenderThread presents it directly through EGL. */
        @Throws(Exception::class)
        @JvmStatic
        public fun drawHardware(
            logicalWidth: Int,
            logicalHeight: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            scale120: Int,
        ) {
            if (!::sActivity.isInitialized) init(bufferWidth, bufferHeight, scale120)
            if (bufferWidth != sWidth || bufferHeight != sHeight || scale120 != sScale120) {
                resize(bufferWidth, bufferHeight, scale120)
            }
            // drain main-looper messages posted by views (clicks, invalidations)
            drainLooper()
            sRenderedFrameTime = android.view.Choreographer.getInstance().lastFrameTimeNanos
            dev.artzig.HostedRenderer
                .getInstance()
                .render(android.view.HostedRootFrames.traverse(sRoot), bufferWidth, bufferHeight, sRoot)
        }

        @JvmStatic
        private fun resize(
            width: Int,
            height: Int,
            scale120: Int,
        ) {
            sWidth = width
            sHeight = height
            sScale120 = scale120
            HostContexts.configureDisplay(width, height, scale120)
            val config: android.content.res.Configuration =
                android.content.res.Configuration(sContext.getResources().getConfiguration())
            android.view.HostedRootFrames.resize(sRoot, width, height)
            sActivity.onConfigurationChanged(config)
        }

        @JvmStatic
        public fun pumpMainLooper(): Long {
            // run pending messages on this thread (we own the main looper)
            val looper: android.os.Looper? = android.os.Looper.myLooper()
            if (looper == null) return -1
            val queue = looper.getQueue()
            var guard = 0
            while (guard++ < 500) {
                val msg: android.os.Message? = nextMessageNow(queue)
                if (msg == null) break
                msg.getTarget().dispatchMessage(msg)
                android.os.MessageQueue.`recycleDispatched$artzig`(msg)
            }
            if (guard > 500 && queue.`nextPollDelayMillis$artzig`() == 0L) {
                queue.`wakeHost$artzig`()
                return 0
            }
            queue.`runIdleHandlers$artzig`()
            return queue.`nextPollDelayMillis$artzig`()
        }

        @JvmStatic public fun needsFrame(): Boolean =
            android.view.Choreographer.getInstance().lastFrameTimeNanos > sRenderedFrameTime

        @JvmStatic private fun drainLooper() { pumpMainLooper() }

        @JvmStatic private fun nextMessageNow(q: android.os.MessageQueue): android.os.Message? = q.`pollDueMessage$artzig`()
    }
}
