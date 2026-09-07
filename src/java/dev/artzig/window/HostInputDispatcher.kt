package dev.artzig.window

import android.os.IBinder
import android.view.InputApplicationHandle
import android.view.InputChannel
import android.view.InputEvent
import android.view.KeyEvent

/**
 * The dispatcher half of AOSP NativeInputManager, with Wayland supplying devices.
 * Stock InputDispatcher owns channel delivery, hit testing, ANRs and cancellation;
 * its policy callbacks belong to WM and the desktop input adapter. There is no
 * InputReader because the compositor, rather than evdev, owns physical devices.
 */
class HostInputDispatcher(callbacks: Callbacks) : AutoCloseable {
    interface Callbacks {
        fun notifyNoFocusedWindowAnr(application: InputApplicationHandle)
        fun notifyWindowUnresponsive(token: IBinder, pid: Int, reason: String)
        fun notifyWindowResponsive(token: IBinder, pid: Int)
        fun notifyInputChannelBroken(token: IBinder)
        fun notifyFocusChanged(oldToken: IBinder?, newToken: IBinder?)
        fun notifyFocusedDisplayChanged(displayId: Int)
        /** Takes ownership of the event copy; recycle after filtering, including async use. */
        fun filterInputEvent(event: InputEvent, policyFlags: Int): Boolean
        /** Return the updated native policy flags, including PASS_TO_USER when allowed. */
        fun interceptKeyBeforeQueueing(event: KeyEvent, policyFlags: Int): Int
        fun interceptMotionBeforeQueueing(displayId: Int, source: Int, action: Int,
                                          whenNanos: Long, policyFlags: Int): Int
        /** Stock WM convention: delay in milliseconds, 0 continue, -1 skip, -2 fallback. */
        fun interceptKeyBeforeDispatching(token: IBinder?, event: KeyEvent, policyFlags: Int): Long
        fun dispatchUnhandledKey(token: IBinder?, event: KeyEvent, policyFlags: Int): KeyEvent?
        fun pokeUserActivity(eventTimeNanos: Long, eventType: Int, displayId: Int)
        fun onPointerDownOutsideFocus(token: IBinder)
        /** The Wayland backend must acknowledge this sequence after capture changes. */
        fun setPointerCapture(windowToken: IBinder?, sequence: Int)
        fun notifyDropWindow(token: IBinder, x: Float, y: Float)
        fun notifyDeviceInteraction(deviceId: Int, timestampNanos: Long, uids: IntArray)
        fun notifyUnsupportedHardwareEvent(kind: String)
    }

    private val handle = nativeCreate(callbacks)

    fun createInputChannel(name: String): InputChannel = nativeCreateInputChannel(handle, name)
    fun createInputMonitor(name: String, displayId: Int, ownerPid: Int): InputChannel {
        require(displayId >= 0) { "displayId must >= 0" }
        return nativeCreateInputMonitor(handle, name, displayId, ownerPid)
    }
    fun displayRemoved(displayId: Int) = nativeDisplayRemoved(handle, displayId)
    fun removeInputChannel(token: IBinder) = nativeRemoveInputChannel(handle, token)
    fun setFocusedApplication(displayId: Int, application: InputApplicationHandle?) =
        nativeSetFocusedApplication(handle, displayId, application)
    fun setFocusedDisplay(displayId: Int) = nativeSetFocusedDisplay(handle, displayId)
    fun setInputDispatchMode(enabled: Boolean, frozen: Boolean) = nativeSetInputDispatchMode(handle, enabled, frozen)
    fun setKeyRepeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) {
        require(timeoutMillis >= 0 && delayMillis > 0)
        nativeSetKeyRepeatConfiguration(handle, timeoutMillis, delayMillis, enabled)
    }
    fun setInputFilterEnabled(enabled: Boolean) = nativeSetInputFilterEnabled(handle, enabled)
    fun setInTouchMode(inTouchMode: Boolean, pid: Int, uid: Int, hasPermission: Boolean, displayId: Int): Boolean =
        nativeSetInTouchMode(handle, inTouchMode, pid, uid, hasPermission, displayId)
    fun transferTouch(from: IBinder, to: IBinder, isDragDrop: Boolean, entireGesture: Boolean): Boolean =
        nativeTransferTouch(handle, from, to, isDragDrop, entireGesture)
    fun transferTouchOnDisplay(to: IBinder, displayId: Int): Boolean = nativeTransferTouchOnDisplay(handle, to, displayId)
    fun pilferPointers(token: IBinder) = nativePilferPointers(handle, token)
    fun requestPointerCapture(token: IBinder, enabled: Boolean) = nativeRequestPointerCapture(handle, token, enabled)
    fun notifyPointerCaptureChanged(windowToken: IBinder?, sequence: Int) =
        nativeNotifyPointerCaptureChanged(handle, windowToken, sequence)
    fun setDisplayEligibilityForPointerCapture(displayId: Int, eligible: Boolean) =
        nativeSetDisplayEligibilityForPointerCapture(handle, displayId, eligible)
    fun injectInputEvent(event: InputEvent, targetUid: Int, syncMode: Int, timeoutMillis: Int, policyFlags: Int): Int {
        require(timeoutMillis >= 0 && (syncMode == 0 || timeoutMillis > 0)) {
            "Synchronous injection needs a positive timeout; asynchronous injection allows zero"
        }
        require(syncMode in 0..2) { "Unknown input injection synchronization mode" }
        return nativeInjectInputEvent(handle, event, targetUid, syncMode, timeoutMillis, policyFlags)
    }
    /** Device events run through stock input filtering, unlike injected events. */
    fun notifyInputEvent(event: InputEvent) = nativeNotifyInputEvent(handle, event)
    /** Publish inputflinger and subscribe to stock SF window metadata before SF bootFinished. */
    fun connectSurfaceFlinger() = nativeConnectSurfaceFlinger(handle)
    override fun close() = nativeClose(handle)

    companion object {
        const val POLICY_FLAG_PASS_TO_USER = 0x40000000
        const val POLICY_FLAG_TRUSTED = 0x02000000
        @JvmStatic private external fun nativeCreate(callbacks: Callbacks): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeCreateInputChannel(handle: Long, name: String): InputChannel
        @JvmStatic private external fun nativeCreateInputMonitor(handle: Long, name: String, displayId: Int, ownerPid: Int): InputChannel
        @JvmStatic private external fun nativeDisplayRemoved(handle: Long, displayId: Int)
        @JvmStatic private external fun nativeRemoveInputChannel(handle: Long, token: IBinder)
        @JvmStatic private external fun nativeSetFocusedApplication(handle: Long, displayId: Int, application: InputApplicationHandle?)
        @JvmStatic private external fun nativeSetFocusedDisplay(handle: Long, displayId: Int)
        @JvmStatic private external fun nativeSetInputDispatchMode(handle: Long, enabled: Boolean, frozen: Boolean)
        @JvmStatic private external fun nativeSetKeyRepeatConfiguration(handle: Long, timeoutMillis: Int, delayMillis: Int, enabled: Boolean)
        @JvmStatic private external fun nativeSetInputFilterEnabled(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetInTouchMode(handle: Long, inTouchMode: Boolean, pid: Int, uid: Int, hasPermission: Boolean, displayId: Int): Boolean
        @JvmStatic private external fun nativeTransferTouch(handle: Long, from: IBinder, to: IBinder, isDragDrop: Boolean, entireGesture: Boolean): Boolean
        @JvmStatic private external fun nativeTransferTouchOnDisplay(handle: Long, to: IBinder, displayId: Int): Boolean
        @JvmStatic private external fun nativePilferPointers(handle: Long, token: IBinder)
        @JvmStatic private external fun nativeRequestPointerCapture(handle: Long, token: IBinder, enabled: Boolean)
        @JvmStatic private external fun nativeNotifyPointerCaptureChanged(handle: Long, windowToken: IBinder?, sequence: Int)
        @JvmStatic private external fun nativeSetDisplayEligibilityForPointerCapture(handle: Long, displayId: Int, eligible: Boolean)
        @JvmStatic private external fun nativeInjectInputEvent(handle: Long, event: InputEvent, targetUid: Int, syncMode: Int, timeoutMillis: Int, policyFlags: Int): Int
        @JvmStatic private external fun nativeNotifyInputEvent(handle: Long, event: InputEvent)
        @JvmStatic private external fun nativeConnectSurfaceFlinger(handle: Long)
    }
}
