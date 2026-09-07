package dev.artzig.window

import android.os.Handler
import android.os.ServiceManager

/** The system process owns one organizer independently of APK launchers. */
internal object SessionTaskShell {
    fun start(handler: Handler, dispatcher: HostInputManagerBridge,
        publishKeyboard: (android.view.KeyCharacterMap) -> Unit): AutoCloseable {
        val binder = checkNotNull(ServiceManager.checkService("dev.artzig.window.presenter")) {
            "Session presenter did not register before system startup"
        }
        val remote = IWaylandTaskPresenter.Stub.asInterface(binder)
        val input = WaylandTaskInput(object : WaylandTaskInput.Android {
            override fun focusTask(taskId: Int) = android.app.ActivityTaskManager.getService().setFocusedTask(taskId)
            override fun keyboardLayout(map: android.view.KeyCharacterMap) = publishKeyboard(map)
            override fun dispatch(event: android.view.InputEvent, repeatable: Boolean) = dispatcher.dispatchDesktopEvent(event, repeatable)
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) =
                dispatcher.setKeyRepeatConfiguration(timeoutMillis, delayMillis, enabled)
        })
        // Wait for Wayland's repeat policy before allowing stock dispatcher to
        // synthesize repeats. The presenter sends physical key edges only.
        dispatcher.setKeyRepeatConfiguration(500, 50, false)
        val shell = WaylandTaskShell(StockTaskOrganizer(handler), RemoteTaskPresenter(remote, handler, input))
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
        return AutoCloseable {
            binder.unlinkToDeath(death, 0)
            shell.close()
        }
    }
}
