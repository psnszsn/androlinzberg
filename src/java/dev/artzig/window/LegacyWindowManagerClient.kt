package dev.artzig.window

import android.os.IBinder
import android.view.DisplayInfo
import android.view.ICrossWindowBlurEnabledListener
import android.view.IOnKeyguardExitResult
import android.view.IRotationWatcher
import android.window.IScreenRecordingCallback
import android.view.IWindowManager
import android.view.InsetsState
import android.view.displayhash.DisplayHash
import android.view.displayhash.VerifiedDisplayHash
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.internal.policy.IKeyguardLockedStateListener
import dev.artzig.system.unsupported

/**
 * Existing host-window capabilities during the stock WMS client cutover. These
 * operations previously lived in the reduced IWindowManager declaration. The
 * interface is now generated from pinned AIDL; WindowManagerGlobal chooses the
 * real service once it is registered. This adapter is not a Binder service and
 * cannot accept windows or acknowledge WMS startup. Unserved operations fail
 * through the same structured diagnostic delegate as other host adapters.
 *
 * Source note: WMS keyguard methods delegate to PhoneWindowManager/KeyguardService
 * on Android. This legacy path has no keyguard, navigation bar, screen recording,
 * display hashing or blur compositor; preserve its existing capability answers.
 */
class LegacyWindowManagerClient : IWindowManager by unsupported<IWindowManager>() {
    override fun asBinder(): IBinder? = null
    override fun isKeyguardLocked(): Boolean = false
    override fun isKeyguardSecure(userId: Int): Boolean = false
    override fun disableKeyguard(token: IBinder?, tag: String?, userId: Int) {}
    override fun reenableKeyguard(token: IBinder?, userId: Int) {}
    override fun exitKeyguardSecurely(callback: IOnKeyguardExitResult?) {
        callback?.onKeyguardExitResult(true)
    }
    override fun dismissKeyguard(callback: IKeyguardDismissCallback?, message: CharSequence?) {
        callback?.onDismissSucceeded()
    }
    override fun addKeyguardLockedStateListener(listener: IKeyguardLockedStateListener?) {
        listener?.onKeyguardLockedStateChanged(false)
    }
    override fun removeKeyguardLockedStateListener(listener: IKeyguardLockedStateListener?) {}
    override fun showStrictModeViolation(on: Boolean) {}
    override fun getInitialDisplayDensity(displayId: Int): Int = 160
    override fun getBaseDisplayDensity(displayId: Int): Int = 160
    override fun hasNavigationBar(displayId: Int): Boolean = false
    override fun getPreferredOptionsPanelGravity(displayId: Int): Int = 81
    override fun watchRotation(watcher: IRotationWatcher?, displayId: Int): Int = 0
    override fun removeRotationWatcher(watcher: IRotationWatcher?) {}
    override fun getRemoveContentMode(displayId: Int): Int = 0
    override fun thawRotation(caller: String?) {}
    override fun freezeRotation(rotation: Int, caller: String?) {}
    override fun registerCrossWindowBlurEnabledListener(listener: ICrossWindowBlurEnabledListener?): Boolean = false
    override fun unregisterCrossWindowBlurEnabledListener(listener: ICrossWindowBlurEnabledListener?) {}
    override fun setDisplayHashThrottlingEnabled(enable: Boolean) {}
    override fun getSupportedDisplayHashAlgorithms(): Array<String> = emptyArray()
    override fun verifyDisplayHash(displayHash: DisplayHash?): VerifiedDisplayHash? = null
    override fun registerScreenRecordingCallback(callback: IScreenRecordingCallback?): Boolean = false
    override fun unregisterScreenRecordingCallback(callback: IScreenRecordingCallback?) {}
    override fun getWindowInsets(displayId: Int, token: IBinder?, outInsetsState: InsetsState?): Boolean = false
    override fun getPossibleDisplayInfo(displayId: Int): MutableList<DisplayInfo> = arrayListOf()
}
