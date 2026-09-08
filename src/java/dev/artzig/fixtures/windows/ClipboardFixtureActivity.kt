package dev.artzig.fixtures.windows

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Exercises the public clipboard contract; native peers run in the harness. */
class ClipboardFixtureActivity : Activity() {
    private lateinit var clipboard: ClipboardManager
    private lateinit var status: TextView
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { report() }
    private fun report() {
        val clip = clipboard.primaryClip
        status.text = if (clip == null) "Clipboard is empty" else {
            val item = clip.getItemAt(0)
            "Clipboard text=${item.text} html=${item.htmlText ?: "none"}"
        }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        clipboard = getSystemService(ClipboardManager::class.java)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Clipboard fixture ready" }
        content.addView(status)
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Copy Unicode text") { clipboard.setPrimaryClip(ClipData.newPlainText("Fixture text", "Android 😀 café")); report() }
        button("Copy HTML") { clipboard.setPrimaryClip(ClipData.newHtmlText("Fixture HTML", "Android bold 😀", "<b>Android bold 😀</b>")); report() }
        button("Read clipboard") { report() }
        button("Clear clipboard") { clipboard.clearPrimaryClip(); report() }
        var protocolRemoved = false
        val toggle = Button(this).apply { text = "Remove clipboard protocol" }
        toggle.setOnClickListener {
            protocolRemoved = !protocolRemoved
            setTaskDescription(android.app.ActivityManager.TaskDescription.Builder()
                .setLabel("AZK CLIPBOARD:" + if (protocolRemoved) "remove" else "restore").build())
            toggle.text = if (protocolRemoved) "Restore clipboard protocol" else "Remove clipboard protocol"
        }
        content.addView(toggle)
        setContentView(content)
        clipboard.addPrimaryClipChangedListener(listener)
    }
    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }
}
