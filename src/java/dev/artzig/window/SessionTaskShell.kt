package dev.artzig.window

import android.os.Handler
import android.os.ServiceManager

/** The system process owns one organizer independently of APK launchers. */
internal object SessionTaskShell {
    class Controller(private val presenter: RemoteTaskPresenter, private val input: WaylandTaskInput, private val cleanup: () -> Unit) : AutoCloseable {
        fun pointerCapture(token: android.os.IBinder?, sequence: Int) = presenter.pointerCapture(token, sequence)
        fun pointerIcon(token: android.os.IBinder, type: Int): Boolean = presenter.pointerIcon(token, type)
        fun testInputQueued(sequence: Long): Boolean = presenter.testInputQueued(sequence)
        fun keyCodeState(deviceId: Int, sourceMask: Int, code: Int) = input.keyCodeState(deviceId, sourceMask, code)
        fun scanCodeState(deviceId: Int, sourceMask: Int, scan: Int) = input.scanCodeState(deviceId, sourceMask, scan)
        override fun close() = cleanup()
    }
    fun start(handler: Handler, dispatcher: HostInputManagerBridge,
        publishKeyboard: (android.view.KeyCharacterMap, IntArray) -> Unit,
        publishCapabilities: (Int, Int) -> Unit,
        taskForToken: (android.os.IBinder) -> Int?): Controller {
        val binder = checkNotNull(ServiceManager.checkService("dev.artzig.window.presenter")) {
            "Session presenter did not register before system startup"
        }
        val remote = IWaylandTaskPresenter.Stub.asInterface(binder)
        val input = WaylandTaskInput(object : WaylandTaskInput.Android {
            override fun keyboardActive(active: Boolean) = dispatcher.setDesktopKeyboardFocus(active)
            override fun focusTask(taskId: Int) = android.app.ActivityTaskManager.getService().setFocusedTask(taskId)
            override fun keyboardLayout(map: android.view.KeyCharacterMap, supportedKeyCodes: IntArray) = publishKeyboard(map, supportedKeyCodes)
            override fun dispatch(event: android.view.InputEvent, repeatable: Boolean) = dispatcher.dispatchDesktopEvent(event, repeatable)
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) =
                dispatcher.setKeyRepeatConfiguration(timeoutMillis, delayMillis, enabled)
        })
        // Wait for Wayland's repeat policy before allowing stock dispatcher to
        // synthesize repeats. The presenter sends physical key edges only.
        dispatcher.setKeyRepeatConfiguration(500, 50, false)
        dispatcher.setDesktopKeyboardFocus(false)
        val presenter = RemoteTaskPresenter(remote, handler, input, taskForToken, publishCapabilities, dispatcher::notifyPointerCaptureChanged)
        presenter.connect()
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
        return Controller(presenter, input) {
            binder.unlinkToDeath(death, 0)
            shell.close()
        }
    }
}
