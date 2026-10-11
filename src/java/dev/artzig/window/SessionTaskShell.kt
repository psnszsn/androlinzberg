package dev.artzig.window

import android.content.Context
import android.os.Handler
import android.os.ServiceManager

/** The system process owns one organizer independently of APK launchers. */
internal object SessionTaskShell {
    class Controller(private val presenter: RemoteTaskPresenter, private val cleanup: () -> Unit) : AutoCloseable {
        fun toggleFullscreen(token: android.os.IBinder) = presenter.toggleFullscreen(token)
        fun pointerCapture(token: android.os.IBinder?, sequence: Int) = presenter.pointerCapture(token, sequence)
        override fun close() = cleanup()
    }
    fun start(handler: Handler, context: Context,
        taskForToken: (android.os.IBinder) -> Int?,
        taskForEditor: (android.os.IBinder, Int, Int) -> Int?): Controller {
        val binder = checkNotNull(ServiceManager.checkService("dev.artzig.window.presenter")) {
            "Session presenter did not register before system startup"
        }
        val remote = IWaylandTaskPresenter.Stub.asInterface(binder)
        val input = WaylandTaskInput(object : WaylandTaskInput.Android {
            // Stock InputDispatcher delivers keys to WM's focused window; the
            // compositor's keyboard focus selects that window's task.
            override fun keyboardFocus(task: TaskGeometry.Task?) {}
            override fun focusTask(taskId: Int) = android.app.ActivityTaskManager.getService().setFocusedTask(taskId)
            /** The desktop's repeat policy, as the stock settings InputSettingsObserver applies. */
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) {
                val resolver = context.contentResolver
                val user = android.os.UserHandle.USER_SYSTEM
                android.provider.Settings.Secure.putIntForUser(resolver,
                    android.provider.Settings.Secure.KEY_REPEAT_ENABLED, if (enabled) 1 else 0, user)
                if (enabled) {
                    android.provider.Settings.Secure.putIntForUser(resolver,
                        android.provider.Settings.Secure.KEY_REPEAT_TIMEOUT_MS, timeoutMillis, user)
                    android.provider.Settings.Secure.putIntForUser(resolver,
                        android.provider.Settings.Secure.KEY_REPEAT_DELAY_MS, delayMillis, user)
                }
            }
            override fun endHover() = android.hardware.input.IInputManager.Stub.asInterface(
                ServiceManager.getService(Context.INPUT_SERVICE)).cancelCurrentTouch()
            override fun displaySize(): Pair<Int, Int> {
                val info = checkNotNull(android.hardware.display.DisplayManagerGlobal.getInstance()
                    .getDisplayInfo(android.view.Display.DEFAULT_DISPLAY)) { "Default display is absent" }
                return info.logicalWidth to info.logicalHeight
            }
        })
        val presenter = RemoteTaskPresenter(remote, handler, input, taskForToken, taskForEditor)
        presenter.connect()
        com.android.server.LocalServices.addService(WaylandInputMethod::class.java, presenter.textInput)
        val shell = WaylandTaskShell(StockTaskOrganizer(handler), presenter)
        val death = android.os.IBinder.DeathRecipient {
            check(handler.post {
                // Surface/Binder identities cannot be reused after presenter
                // death. Terminate this session generation explicitly.
                android.util.Log.e("WaylandTaskShell", "Session presenter died")
                try { shell.close() } finally { Runtime.getRuntime().exit(1) }
            })
        }
        binder.linkToDeath(death, 0)
        try { shell.start() }
        catch (failure: Throwable) { binder.unlinkToDeath(death, 0); throw failure }
        return Controller(presenter) {
            binder.unlinkToDeath(death, 0)
            shell.close()
        }
    }
}
