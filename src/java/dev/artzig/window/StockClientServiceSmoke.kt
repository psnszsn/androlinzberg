package dev.artzig.window

import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.view.IWindow
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.IAccessibilityInteractionConnection
import android.view.accessibility.IAccessibilityManager
import com.android.internal.inputmethod.IInputMethodClient
import com.android.internal.inputmethod.IRemoteInputConnection
import com.android.internal.inputmethod.InputBindResult
import com.android.internal.view.IInputMethodManager

/** Public Binder contracts used during stock ViewRootImpl registration and focus. */
internal object StockClientServiceSmoke {
    fun run() {
        val accessibility = IAccessibilityManager.Stub.asInterface(ServiceManager.getService("accessibility"))
        fun window() = object : IWindow.Stub(), IWindow by IWindow.Default() {
            override fun asBinder(): IBinder = this
        }
        fun connection() = object : IAccessibilityInteractionConnection.Stub(),
            IAccessibilityInteractionConnection by IAccessibilityInteractionConnection.Default() {
            override fun asBinder(): IBinder = this
        }
        val first = window()
        val second = window()
        val a = accessibility.addAccessibilityInteractionConnection(first, Binder(), connection(), "android", 0)
        val b = accessibility.addAccessibilityInteractionConnection(second, Binder(), connection(), "android", 0)
        try {
            check(a >= 0 && b > a)
            check(accessibility.getAccessibilityWindowId(first.asBinder()) == a)
            check(accessibility.getAccessibilityWindowId(second.asBinder()) == b)
            accessibility.removeAccessibilityInteractionConnection(first)
            check(accessibility.getAccessibilityWindowId(first.asBinder()) == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
            accessibility.removeAccessibilityInteractionConnection(first)
            check(accessibility.getAccessibilityWindowId(second.asBinder()) == b)
        } finally {
            accessibility.removeAccessibilityInteractionConnection(first)
            accessibility.removeAccessibilityInteractionConnection(second)
        }
        val ime = IInputMethodManager.Stub.asInterface(ServiceManager.getService("input_method"))
        fun client() = object : IInputMethodClient.Stub(), IInputMethodClient by IInputMethodClient.Default() {
            override fun asBinder(): IBinder = this
        }
        val fallback = object : IRemoteInputConnection.Stub(), IRemoteInputConnection by IRemoteInputConnection.Default() {
            override fun asBinder(): IBinder = this
        }
        val registered = client()
        fun focus(client: IInputMethodClient, token: IBinder?) = ime.startInputOrWindowGainedFocus(
            0, client, token, 0, 0, 0, null, fallback, null, 36, 0, null, false).result
        // Null-window rejection precedes the client lookup in pinned IMMS.
        check(focus(client(), null) == InputBindResult.NULL.result)
        ime.addClient(registered, fallback, 1024)
        check(ime.getCurrentInputMethodInfoAsUser(0) == null)
        check(ime.getLastInputMethodSubtype(0) == null)
        check(ime.getInputMethodWindowVisibleHeight(registered) == 0)
        var unknownHeightRejected = false
        try { ime.getInputMethodWindowVisibleHeight(client()) }
        catch (_: IllegalArgumentException) { unknownHeightRejected = true }
        check(unknownHeightRejected) { "Unknown IME client queried native window geometry" }
        var duplicateRejected = false
        try { ime.addClient(client(), fallback, 1024) }
        catch (_: SecurityException) { duplicateRejected = true }
        check(duplicateRejected) { "Duplicate IME process/display registration accepted" }
        // WMS rejects an unknown window before comparing its display to 1024.
        check(focus(registered, Binder()) == InputBindResult.NOT_IME_TARGET_WINDOW.result)
        val invalidDisplay = client()
        ime.addClient(invalidDisplay, fallback, android.view.Display.INVALID_DISPLAY)
        check(focus(invalidDisplay, Binder()) == InputBindResult.INVALID_DISPLAY_ID.result)
        println("Stock client services: accessibility IDs/removal and IME registration/focus rejection passed")
    }
}
