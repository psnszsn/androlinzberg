package dev.artzig.fixtures.windows

import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Public editor APIs; only the test compositor interprets task descriptions. */
class ImeFixtureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var first: EditText
    private lateinit var password: EditText
    private fun report() {
        if (!::first.isInitialized) return
        if (::password.isInitialized && password.hasFocus()) {
            status.text = "Password length=${password.length()}"
            return
        }
        val text = first.text
        status.text = "IME text=$text select=${first.selectionStart}:${first.selectionEnd} " +
            "composing=${BaseInputConnection.getComposingSpanStart(text)}:${BaseInputConnection.getComposingSpanEnd(text)}" +
            if (first.isCursorVisible) "" else " caret=hidden"
    }
    private inner class Editor : EditText(this@ImeFixtureActivity) {
        override fun onCreateInputConnection(info: EditorInfo): InputConnection? {
            val real = super.onCreateInputConnection(info) ?: return null
            return object : InputConnectionWrapper(real, false) {
                override fun endBatchEdit(): Boolean = super.endBatchEdit().also { report() }
                override fun setImeConsumesInput(value: Boolean): Boolean = super.setImeConsumesInput(value).also {
                    check(this@Editor.isCursorVisible != value)
                }
            }
        }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this)
        first = Editor().apply {
            contentDescription = "IME Unicode editor"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("a😀中z")
            setSelection(3)
        }
        password = Editor().apply {
            contentDescription = "IME password editor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("private-secret")
            setSelection(length())
        }
        content.addView(status); content.addView(first); content.addView(password)
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; isFocusable = false; setOnClickListener { action() } })
        }
        fun command(value: String) {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel("AZK IME:$value").build())
        }
        button("Compose Unicode") { command("preedit") }
        button("Hide preedit caret") { command("hidden") }
        button("Commit Unicode") { command("commit") }
        button("Delete Unicode") { command("delete") }
        button("Focus password") { password.requestFocus(); report() }
        button("Verify password context") { command("password") }
        button("Focus original editor") { first.requestFocus(); report() }
        button("Compose then leave") { command("leave-preedit") }
        setContentView(android.widget.ScrollView(this).apply { addView(content) })
        first.requestFocus()
        report()
    }
}
