@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import com.android.internal.inputmethod.InputMethodInfoSafeList
import com.android.internal.view.IInputMethodManager

/**
 * Hosted input-method service for a device with no software IMEs.
 *
 * Source note: follows the client-visible empty-device behavior of AOSP
 * `InputMethodManagerService`. The real service cannot run without the system-server
 * window/input stack; physical text is injected by `HostedInputDispatcher`, while
 * stock `InputMethodManager` still performs its normal AIDL registration and queries.
 */
internal class HostedInputMethodManagerService :
    IInputMethodManager.Stub(),
    IInputMethodManager by IInputMethodManager.Default() {
    override fun asBinder(): android.os.IBinder = this

    override fun getInputMethodList(userId: Int, directBootAwareness: Int): InputMethodInfoSafeList =
        InputMethodInfoSafeList.empty()

    override fun getEnabledInputMethodList(userId: Int): InputMethodInfoSafeList =
        InputMethodInfoSafeList.empty()

    override fun getInputMethodListLegacy(userId: Int, directBootAwareness: Int): MutableList<InputMethodInfo> =
        mutableListOf()

    override fun getEnabledInputMethodListLegacy(userId: Int): MutableList<InputMethodInfo> = mutableListOf()

    override fun getEnabledInputMethodSubtypeList(
        imiId: String?,
        allowsImplicitlyEnabledSubtypes: Boolean,
        userId: Int,
    ): MutableList<InputMethodSubtype> = mutableListOf()
}
