package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Public Dialog contracts on real APK windows, without synthetic roots. */
class DialogFixtureActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val showing = ArrayList<Dialog>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Dialog contracts ready" }
        content.addView(status, LinearLayout.LayoutParams(-1, 60))
        for ((label, action) in listOf("Check dialog lifecycle" to ::lifecycle, "Show shared Back dialogs" to ::sharedBack)) {
            content.addView(Button(this).apply {
                text = label
                setOnClickListener { isEnabled = false; content.post { action() } }
            })
        }
        setContentView(content)
    }

    private fun verified(message: String) {
        status.text = message
        status.setBackgroundColor(Color.CYAN)
        Log.i("DialogFixture", message)
    }

    private fun lifecycle() {
        val dialog = RecordingDialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        showing.add(dialog)
        val listeners = ArrayList<String>()
        dialog.setOnShowListener { listeners.add("show") }
        dialog.setOnCancelListener { listeners.add("cancel") }
        dialog.setOnDismissListener { listeners.add("dismiss") }
        dialog.show()
        check(dialog.events == listOf("create", "start"))
        check(listeners.isEmpty())
        content.post {
            check(listeners == listOf("show"))
            dialog.dismiss()
            check(!dialog.isShowing && dialog.events == listOf("create", "start", "stop"))
            check(listeners == listOf("show"))
            content.post {
                check(listeners == listOf("show", "dismiss"))
                dialog.show()
                check(dialog.events == listOf("create", "start", "stop", "start"))
                content.post {
                    check(listeners == listOf("show", "dismiss", "show"))
                    dialog.cancel()
                    check(!dialog.isShowing)
                    content.post {
                        check(listeners.takeLast(2) == listOf("cancel", "dismiss"))
                        Log.i("DialogFixture", "create/start/stop; asynchronous show/dismiss; reshow; cancel before dismiss")
                        savedState()
                    }
                }
            }
        }
    }

    private fun savedState() {
        val source = RecordingDialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        showing.add(source)
        source.show()
        source.input.setText("preserved dialog text")
        val saved = source.onSaveInstanceState()
        source.dismiss()
        content.post {
            val restored = RecordingDialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            showing.add(restored)
            restored.onRestoreInstanceState(saved)
            check(restored.isShowing)
            check(restored.input.text.toString() == "preserved dialog text")
            check(restored.events == listOf("create:saved", "start"))
            content.post {
                restored.dismiss()
                val floating = TypedValue()
                val themed = Dialog(this)
                check(themed.context.theme.resolveAttribute(android.R.attr.windowIsFloating, floating, true))
                check(floating.data != 0)
                val actionBar = RecordingDialog(this, android.R.style.Theme_DeviceDefault)
                showing.add(actionBar)
                actionBar.show()
                checkNotNull(actionBar.actionBar)
                content.post {
                    actionBar.dismiss()
                    verified("Dialog lifecycle and saved state verified")
                }
            }
        }
    }

    private fun sharedBack() {
        val lower = Dialog(this).apply {
            setTitle("Lower Back dialog")
            setContentView(TextView(context).apply { text = "Lower Back dialog"; setPadding(24, 24, 24, 24) })
        }
        val upper = Dialog(this).apply {
            setTitle("Upper Back dialog")
            setContentView(TextView(context).apply { text = "Upper Back dialog"; setPadding(24, 24, 24, 24) })
        }
        showing.add(lower)
        showing.add(upper)
        var calls = 0
        val callback = OnBackInvokedCallback {
            calls++
            when (calls) {
                1 -> {
                    check(upper.isShowing && lower.isShowing)
                    upper.dismiss()
                    Log.i("DialogFixture", "shared Back callback dismissed upper window only")
                }
                2 -> {
                    check(!upper.isShowing && lower.isShowing)
                    lower.dismiss()
                    verified("Window-scoped Back callbacks verified")
                }
                else -> error("duplicate Back callback")
            }
        }
        lower.show()
        lower.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        upper.show()
        upper.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
    }

    override fun onDestroy() {
        for (dialog in showing) if (dialog.isShowing) dialog.dismiss()
        super.onDestroy()
    }

    private class RecordingDialog(context: Context, theme: Int) : Dialog(context, theme) {
        val events = ArrayList<String>()
        lateinit var input: EditText
        override fun onCreate(state: Bundle?) {
            events.add(if (state == null) "create" else "create:saved")
            super.onCreate(state)
            input = EditText(context).apply { id = 0x1020f00; isSaveEnabled = true }
            setContentView(input)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        override fun onStart() { events.add("start"); super.onStart() }
        override fun onStop() { events.add("stop"); super.onStop() }
    }
}
