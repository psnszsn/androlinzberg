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
    private var lastUri: android.net.Uri? = null
    private var heldFile: java.io.InputStream? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { report() }
    private fun report() {
        val clip = clipboard.primaryClip
        status.text = if (clip == null) "Clipboard is empty" else {
            val item = clip.getItemAt(0)
            if (item.uri != null) {
                lastUri = item.uri
                val values = (0 until clip.itemCount).map { index ->
                    contentResolver.openInputStream(clip.getItemAt(index).uri!!)!!.bufferedReader().use { it.readText() }
                }
                "Clipboard files=" + values.joinToString("|")
            } else "Clipboard text=${item.text} html=${item.htmlText ?: "none"}"
        }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        clipboard = getSystemService(ClipboardManager::class.java)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Clipboard fixture ready" }
        content.addView(status)
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setPadding(0, 0, 0, 0); setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, 32))
        }
        button("Copy Unicode text") { clipboard.setPrimaryClip(ClipData.newPlainText("Fixture text", "Android 😀 café")); report() }
        button("Copy HTML") { clipboard.setPrimaryClip(ClipData.newHtmlText("Fixture HTML", "Android bold 😀", "<b>Android bold 😀</b>")); report() }
        button("Read clipboard") { report() }
        button("Clear clipboard") { clipboard.clearPrimaryClip(); report() }
        button("Copy file") {
            clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Fixture file", ClipboardFileProvider.URI))
            report()
        }
        button("Check file permissions") {
            val uri = requireNotNull(lastUri)
            check(uri.authority == "dev.artzig.desktop.transfers")
            check(runCatching { contentResolver.openFileDescriptor(uri, "w")?.close() }.exceptionOrNull() is SecurityException)
            check(runCatching { contentResolver.call(uri, "release", uri.pathSegments[0], null) }.exceptionOrNull() is SecurityException)
            heldFile?.close()
            heldFile = contentResolver.openInputStream(uri)
            status.text = "Clipboard read grant only; control denied"
        }
        button("Check revoked file") {
            val uri = requireNotNull(lastUri)
            check(runCatching { contentResolver.openInputStream(uri)?.close() }.exceptionOrNull() is SecurityException)
            check(requireNotNull(heldFile).bufferedReader().use { it.readText() } == "Native file 😀 café")
            heldFile = null
            status.text = "Clipboard file grant revoked"
        }
        var protocolRemoved = false
        val toggle = Button(this).apply { text = "Remove clipboard protocol" }
        toggle.setOnClickListener {
            protocolRemoved = !protocolRemoved
            setTaskDescription(android.app.ActivityManager.TaskDescription.Builder()
                .setLabel("AZK CLIPBOARD:" + if (protocolRemoved) "remove" else "restore").build())
            toggle.text = if (protocolRemoved) "Restore clipboard protocol" else "Remove clipboard protocol"
        }
        toggle.setPadding(0, 0, 0, 0)
        content.addView(toggle, LinearLayout.LayoutParams(-1, 32))
        setContentView(content)
        clipboard.addPrimaryClipChangedListener(listener)
    }
    override fun onDestroy() {
        heldFile?.close()
        clipboard.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }
}
