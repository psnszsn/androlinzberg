package dev.artzig.fixtures.windows

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.LinearLayout
import android.widget.TextView

/** Real APK provider contract, including a malformed virtual self-cycle. */
class AccessibilityFixtureActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply { text = "Virtual provider ready" }
        content.addView(status)
        content.addView(ProviderView(this, status), LinearLayout.LayoutParams(240, 120))
        setContentView(content)
    }

    private class ProviderView(context: Context, val status: TextView) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var clicks = 0
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        private val provider = object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
                if (id != HOST_VIEW_ID && id != 7) return null
                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@ProviderView, id)
                    packageName = context.packageName
                    className = if (id == HOST_VIEW_ID) "android.view.View" else "android.widget.EditText"
                    val screen = Rect()
                    this@ProviderView.getGlobalVisibleRect(screen)
                    setBoundsInScreen(screen)
                    setBoundsInParent(Rect(0, 0, width, height))
                    isVisibleToUser = this@ProviderView.isShown
                    isEnabled = true
                    addChild(this@ProviderView, 7)
                    if (id == 7) {
                        isPassword = true
                        isClickable = true
                        text = "private password"
                        contentDescription = "private description"
                        hintText = "private hint"
                        addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    }
                }
            }
            override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean {
                if (id != 7 || action != AccessibilityNodeInfo.ACTION_CLICK) return false
                status.text = "Virtual action 7 clicks=${++clicks}"
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
                event.setSource(this@ProviderView, 7)
                event.packageName = context.packageName
                event.className = "android.widget.EditText"
                parent?.requestSendAccessibilityEvent(this@ProviderView, event)
                return true
            }
        }
        override fun getAccessibilityNodeProvider() = provider
        override fun onDraw(canvas: Canvas) {
            paint.color = Color.GREEN
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.BLACK
            paint.textSize = 18f
            canvas.drawText("Virtual password control", 8f, 64f, paint)
        }
    }
}
