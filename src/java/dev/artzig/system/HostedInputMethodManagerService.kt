@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import com.android.internal.inputmethod.IImeTracker
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
internal class HostedInputMethodManagerService(private val context: android.content.Context) :
    IInputMethodManager.Stub(),
    IInputMethodManager by unsupported<IInputMethodManager>() {
    override fun asBinder(): android.os.IBinder = this

    /**
     * `InputMethodManager.createRealInstance` registers every client before it
     * performs any query. AOSP IMMS retains this tuple in ClientController;
     * with no IME sessions to bind, validating the required endpoints is the
     * only observable work the hosted service needs.
     */
    override fun addClient(
        client: com.android.internal.inputmethod.IInputMethodClient?,
        inputmethod: com.android.internal.inputmethod.IRemoteInputConnection?,
        untrustedDisplayId: Int,
    ) {
        requireNotNull(client) { "client must not be null" }
        requireNotNull(inputmethod) { "fallbackInputConnection must not be null" }
    }

    /** No IME process exists to receive or report visibility transitions. */
    override fun getImeTrackerService(): IImeTracker? = null

    // InputMethodManagerService exposes the system process tracing state to client tracers.
    override fun isImeTraceEnabled(): Boolean =
        com.android.internal.inputmethod.ImeTracing.getInstance().isEnabled

    override fun getInputMethodList(userId: Int, directBootAwareness: Int): InputMethodInfoSafeList =
        InputMethodInfoSafeList.empty()

    override fun getEnabledInputMethodList(userId: Int): InputMethodInfoSafeList =
        InputMethodInfoSafeList.empty()

    /** AOSP IMMS.isStylusHandwritingAvailableAsUser: no selected IME supports either mode. */
    override fun isStylusHandwritingAvailableAsUser(userId: Int, connectionless: Boolean): Boolean {
        if (android.os.UserHandle.getCallingUserId() != userId) {
            context.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null,
            )
        }
        return false
    }

    override fun getInputMethodListLegacy(userId: Int, directBootAwareness: Int): MutableList<InputMethodInfo> =
        mutableListOf()

    override fun getEnabledInputMethodListLegacy(userId: Int): MutableList<InputMethodInfo> = mutableListOf()

    override fun getEnabledInputMethodSubtypeList(
        imiId: String?,
        allowsImplicitlyEnabledSubtypes: Boolean,
        userId: Int,
    ): MutableList<InputMethodSubtype> = mutableListOf()
}
