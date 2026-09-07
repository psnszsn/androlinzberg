@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.graphics.ColorSpace
import android.graphics.Point
import android.hardware.OverlayProperties
import android.hardware.display.IDisplayManager
import android.hardware.display.IDisplayManagerCallback
import android.os.IBinder
import android.util.Log
import android.view.ArtzigDisplay
import android.view.Display
import android.view.DisplayInfo

/**
 * Per-process display service backing the AOSP `DisplayManagerGlobal` client.
 *
 * Source note: follows `com.android.server.display.DisplayManagerService.BinderService`
 * for the queries `DisplayManagerGlobal` makes; there is exactly one logical
 * display here — this process's Wayland window, whose bounds live in
 * [ArtzigDisplay]. Wifi display status reports an unavailable adapter. HDR, brightness and virtual displays
 * are not hosted; calls to those methods fail through the shared unsupported Binder
 * delegate.
 */
internal class HostedDisplayManagerService :
    IDisplayManager.Stub(HostedBootstrapPermissionEnforcer()),
    IDisplayManager by unsupported<IDisplayManager>() {
    // Interface delegation must not shadow the Binder identity.
    override fun asBinder(): android.os.IBinder = this

    private val callbacks = ArrayList<Registration>()

    private class Registration(val binder: IBinder, val callback: IDisplayManagerCallback, val mask: Long)

    init {
        ArtzigDisplay.setChangeListener { notifyDisplayChanged() }
    }

    override fun getDisplayInfo(displayId: Int): DisplayInfo? =
        if (displayId == Display.DEFAULT_DISPLAY) ArtzigDisplay.displayInfo() else null

    // DisplayManagerService.getWifiDisplayStatusInternal with no WifiDisplayAdapter.
    override fun getWifiDisplayStatus(): android.hardware.display.WifiDisplayStatus =
        android.hardware.display.WifiDisplayStatus()

    override fun getDisplayIds(includeDisabled: Boolean): IntArray = intArrayOf(Display.DEFAULT_DISPLAY)

    override fun isUidPresentOnDisplay(uid: Int, displayId: Int): Boolean = displayId == Display.DEFAULT_DISPLAY

    override fun registerCallback(callback: IDisplayManagerCallback) {
        registerCallbackWithEventMask(callback, 0L)
    }

    override fun registerCallbackWithEventMask(callback: IDisplayManagerCallback, eventsMask: Long) {
        synchronized(callbacks) { callbacks.add(Registration(callback.asBinder(), callback, eventsMask)) }
    }

    override fun getStableDisplaySize(): Point {
        val info = ArtzigDisplay.displayInfo()
        return Point(info.logicalWidth, info.logicalHeight)
    }

    override fun getPreferredWideGamutColorSpaceId(): Int = ColorSpace.Named.DISPLAY_P3.ordinal

    override fun getOverlaySupport(): OverlayProperties = OverlayProperties.getDefault()

    override fun isMinimalPostProcessingRequested(displayId: Int): Boolean = false

    private fun notifyDisplayChanged() {
        val snapshot = synchronized(callbacks) { ArrayList(callbacks) }
        for (registration in snapshot) {
            try {
                registration.callback.onDisplayEvent(
                    Display.DEFAULT_DISPLAY,
                    android.hardware.display.DisplayManagerGlobal.EVENT_DISPLAY_BASIC_CHANGED,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "display callback failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "HostedDisplayManager"
    }
}
