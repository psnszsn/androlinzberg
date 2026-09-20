package dev.artzig.testing

import android.app.UiAutomation
import android.app.UiAutomationConnection
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Cross-process proof of the stock test-automation path: a host process that
 * joined the session as the broker's inspector role registers a stock
 * [UiAutomation] with the system server's AccessibilityManagerService, reads a
 * real app's window through it and performs a click.
 *
 * Arguments: the text of a node to wait for, optionally followed by the text of
 * a node to click and the text expected afterwards.
 */
object UiAutomationInspectorSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isNotEmpty()) { "usage: UiAutomationInspectorSmoke <visible text> [<click text> <expected text>]" }
        // As under instrumentation: the accessibility client posts to the main
        // looper, which therefore has to exist and run while the test works.
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // The main looper cannot quit; the worker ends the process, as
        // instrumentation does when a run finishes.
        Thread({
            val status = try {
                drive(args)
                0
            } catch (error: Throwable) {
                error.printStackTrace()
                1
            }
            System.exit(status)
        }, "UiAutomationInspectorSmoke").start()
        Looper.loop()
    }

    private fun drive(args: Array<String>) {
        val thread = HandlerThread("UiAutomation").apply { start() }
        val automation = UiAutomation(thread.looper, UiAutomationConnection())
        automation.connect()
        try {
            val first = await(automation, args[0])
            println("UiAutomationInspectorSmoke: found \"${args[0]}\" in ${first.packageName} window ${first.windowId}")
            if (args.size >= 3) {
                val target = await(automation, args[1])
                val clickable = generateSequence(target) { it.parent }.first { it.isClickable }
                check(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "click rejected" }
                await(automation, args[2])
                println("UiAutomationInspectorSmoke: click produced \"${args[2]}\"")
            }
            println("UiAutomationInspectorSmoke: ok")
        } finally {
            automation.disconnect()
            thread.quitSafely()
        }
    }

    private fun await(automation: UiAutomation, text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        error("no node with text \"$text\"; root=${automation.rootInActiveWindow}")
    }
}
