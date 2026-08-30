package dev.artzig

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.TimeUnit

/**
 * Desktop adapter for Android implicit intents.
 *
 * AOSP resolves ACTION_CHOOSER through ResolverActivity and installed package intent filters.
 * The hosted runtime has neither PackageManagerService nor other Android packages, and Linux has
 * no generic share portal. This adapter resolves text shares to one host-owned Activity whose
 * choices use the Wayland clipboard and freedesktop desktop commands.
 */
internal object HostedDesktopIntents {
    private const val TAG = "HostedDesktopIntents"

    data class SharePayload(
        val title: String,
        val text: String,
        val subject: String?,
        val url: String?,
        val mimeType: String,
    )

    @JvmStatic
    fun resolve(context: Context, intent: Intent): ComponentName? =
        if (sharePayload(intent) != null) {
            ComponentName(context, HostedShareActivity::class.java)
        } else {
            null
        }

    fun sharePayload(intent: Intent): SharePayload? {
        val target = if (intent.action == Intent.ACTION_CHOOSER) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return null
        } else {
            intent
        }
        if (target.action != Intent.ACTION_SEND && target.action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }

        val text = target.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf(String::isNotBlank)
            ?: target.data?.toString()?.takeIf(String::isNotBlank)
            ?: target.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.toString()?.takeIf(String::isNotBlank)
            ?: return null
        val chooserTitle = intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
        return SharePayload(
            title = chooserTitle?.takeIf(String::isNotBlank) ?: "Share",
            text = text,
            subject = target.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            url = HTTP_URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '}'),
            mimeType = target.type ?: "text/plain",
        )
    }

    fun copy(payload: SharePayload): Boolean = runCommandWithInput(
        listOf("wl-copy", "--type", payload.mimeType),
        payload.text,
    )

    fun open(payload: SharePayload): Boolean =
        payload.url?.let { startCommand(listOf("xdg-open", it)) } ?: false

    fun email(payload: SharePayload): Boolean {
        val command = mutableListOf("xdg-email")
        payload.subject?.takeIf(String::isNotBlank)?.let {
            command += listOf("--subject", it)
        }
        command += listOf("--body", payload.text)
        return startCommand(command)
    }

    private fun runCommandWithInput(command: List<String>, input: String): Boolean = try {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (throwable: Throwable) {
        Log.w(TAG, "desktop command failed: ${command.firstOrNull()}", throwable)
        false
    }

    private fun startCommand(command: List<String>): Boolean = try {
        ProcessBuilder(command).inheritIO().start()
        true
    } catch (throwable: Throwable) {
        Log.w(TAG, "desktop command failed: ${command.firstOrNull()}", throwable)
        false
    }

    private val HTTP_URL = Regex("https?://[^\\s<>\\\"']+")
}

/** Host-owned replacement for AOSP ChooserActivity when resolving text shares on a desktop. */
internal class HostedShareActivity : Activity() {
    private lateinit var payload: HostedDesktopIntents.SharePayload
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        payload = HostedDesktopIntents.sharePayload(intent) ?: run {
            Log.w(TAG, "unsupported share intent: $intent")
            finish()
            return
        }
        title = payload.title
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        root.addView(TextView(this).apply {
            text = payload.title
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = payload.text
            textSize = 16f
            setTextColor(Color.rgb(230, 230, 230))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(38, 38, 38))
        }, matchWidth(topMargin = dp(24)))

        addButton(root, if (payload.url != null) "Copy link" else "Copy") {
            completeOrReport(HostedDesktopIntents.copy(payload), "Copied to clipboard")
        }
        if (payload.url != null) {
            addButton(root, "Open link") {
                completeOrReport(HostedDesktopIntents.open(payload), "Opened link")
            }
        }
        addButton(root, "Email") {
            completeOrReport(HostedDesktopIntents.email(payload), "Opened email composer")
        }
        addButton(root, "Cancel", this::finish)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(255, 160, 160))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(status, matchWidth(topMargin = dp(16)))

        return ScrollView(this).apply { addView(root, matchWidth()) }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            contentDescription = label
            setOnClickListener { action() }
        }, matchWidth(topMargin = dp(12)))
    }

    private fun completeOrReport(success: Boolean, successMessage: String) {
        if (success) {
            Log.i(TAG, successMessage)
            finish()
        } else {
            status.text = "Desktop share command is unavailable"
        }
    }

    private fun matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, topMargin, 0, 0) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "HostedShareActivity"
    }
}
