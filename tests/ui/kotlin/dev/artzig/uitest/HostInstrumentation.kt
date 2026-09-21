package dev.artzig.uitest

import android.app.ActivityThread
import android.app.Instrumentation
import android.app.UiAutomation
import android.app.UiAutomationConnection
import android.content.Context
import android.os.HandlerThread

/**
 * The Instrumentation androidx UiAutomator asks for, in a host process that was
 * not started by ActivityManagerService. It supplies the two things UiDevice
 * uses: a stock [UiAutomation] registered with the session's real
 * AccessibilityManagerService under the broker's inspector (shell) identity,
 * and this process's system context.
 */
class HostInstrumentation : Instrumentation() {
    private val thread = HandlerThread("UiAutomation").apply { start() }
    private val automation = UiAutomation(thread.looper, UiAutomationConnection()).apply { connect() }

    override fun getUiAutomation(): UiAutomation = automation
    override fun getUiAutomation(flags: Int): UiAutomation = automation
    override fun getContext(): Context = ActivityThread.currentActivityThread().systemContext
    override fun getTargetContext(): Context = context

    fun release() {
        automation.disconnect()
        thread.quitSafely()
    }
}
