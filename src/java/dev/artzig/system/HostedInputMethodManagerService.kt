@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import com.android.internal.inputmethod.IImeTracker
import com.android.internal.inputmethod.InputMethodInfoSafeList
import com.android.internal.view.IInputMethodManager
import android.os.Binder
import android.os.IBinder
import com.android.internal.inputmethod.IInputMethodClient
import com.android.internal.inputmethod.IRemoteInputConnection
import com.android.internal.inputmethod.InputBindResult

/**
 * Hosted input-method service with an optional compositor input session.
 *
 * Source note: follows the client-visible empty-device behavior of AOSP
 * `InputMethodManagerService`. Android IME processes are absent; the desktop
 * adapter binds the stock client session to Wayland text-input when advertised.
 * Without it, the empty-IME branch retains physical keyboard input.
 */
internal class HostedInputMethodManagerService(private val context: android.content.Context) :
    IInputMethodManager.Stub(),
    IInputMethodManager by unsupported<IInputMethodManager>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private val clients = linkedMapOf<IBinder, Client>()
    private var focusedWindow: IBinder? = null
    private var focusedClient: Client? = null
    private var bindSequence = 0
    private fun desktop() = com.android.server.LocalServices.getService(dev.artzig.window.WaylandInputMethod::class.java)
    private inner class Client(val endpoint: IInputMethodClient, val fallback: IRemoteInputConnection,
        val uid: Int, val pid: Int, val displayId: Int) : IBinder.DeathRecipient {
        override fun binderDied() = synchronized(lock) {
            if (clients[endpoint.asBinder()] === this) clients.remove(endpoint.asBinder())
            if (focusedClient === this) { desktop()?.finish(focusedWindow); focusedClient = null; focusedWindow = null }
        }
    }

    /**
     * `InputMethodManager.createRealInstance` registers every client before it
     * performs any query. AOSP IMMS retains this tuple in ClientController;
     * ClientController owns duplicate registration, calling identity and death.
     * Its session types depend on the full IMMS, so retain that tuple here for
     * the no-selected-IME branch rather than importing Android IME processes.
     */
    override fun addClient(
        client: com.android.internal.inputmethod.IInputMethodClient?,
        inputmethod: com.android.internal.inputmethod.IRemoteInputConnection?,
        untrustedDisplayId: Int,
    ) {
        client ?: throw NullPointerException("client must not be null")
        inputmethod ?: throw NullPointerException("fallbackInputConnection must not be null")
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        synchronized(lock) {
            if (clients.values.any { it.uid == uid && it.pid == pid && it.displayId == untrustedDisplayId })
                throw SecurityException("uid=$uid/pid=$pid/displayId=$untrustedDisplayId is already registered")
            val record = Client(client, inputmethod, uid, pid, untrustedDisplayId)
            try { client.asBinder().linkToDeath(record, 0) }
            catch (failure: android.os.RemoteException) { throw IllegalStateException(failure) }
            clients[client.asBinder()] = record
        }
    }

    /** Pinned IMMS.startInputOrWindowGainedFocus validates user, registration,
     * focused window and editor package before startInputUncheckedLocked returns
     * NO_IME. A focus report without an editor has its distinct stock result. */
    override fun startInputOrWindowGainedFocus(startInputReason: Int, client: IInputMethodClient,
        windowToken: IBinder?, startInputFlags: Int, softInputMode: Int, windowFlags: Int,
        editorInfo: android.view.inputmethod.EditorInfo?, inputConnection: IRemoteInputConnection?,
        remoteAccessibilityInputConnection: com.android.internal.inputmethod.IRemoteAccessibilityInputConnection?,
        unverifiedTargetSdkVersion: Int, userId: Int,
        imeDispatcher: android.window.ImeOnBackInvokedDispatcher?, imeRequestedVisible: Boolean): InputBindResult {
        if (android.os.UserHandle.getCallingUserId() != userId) {
            context.enforceCallingOrSelfPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null)
            if (editorInfo?.targetInputMethodUser?.identifier != userId)
                throw java.security.InvalidParameterException("EditorInfo#targetInputMethodUser must also be specified for cross-user startInputOrWindowGainedFocus()")
        }
        if (windowToken == null) return InputBindResult.NULL
        val users = com.android.server.LocalServices.getService(com.android.server.pm.UserManagerInternal::class.java)
        if (users?.isUserRunning(userId) == false || userId != android.os.UserHandle.USER_SYSTEM)
            return InputBindResult.INVALID_USER
        synchronized(lock) {
            val registered = clients[client.asBinder()] ?: throw IllegalArgumentException("Unknown client ${client.asBinder()}")
            if (Binder.getCallingUid() != registered.uid || Binder.getCallingPid() != registered.pid)
                throw SecurityException("Input client belongs to another process")
            val identity = Binder.clearCallingIdentity()
            try {
                val windows = com.android.server.LocalServices.getService(com.android.server.wm.WindowManagerInternal::class.java)
                if (windows != null) when (windows.hasInputMethodClientFocus(windowToken,
                        registered.uid, registered.pid, registered.displayId)) {
                    com.android.server.wm.WindowManagerInternal.ImeClientFocusResult.DISPLAY_ID_MISMATCH ->
                        return InputBindResult.DISPLAY_ID_MISMATCH
                    com.android.server.wm.WindowManagerInternal.ImeClientFocusResult.NOT_IME_TARGET_WINDOW ->
                        return InputBindResult.NOT_IME_TARGET_WINDOW
                    com.android.server.wm.WindowManagerInternal.ImeClientFocusResult.INVALID_DISPLAY_ID ->
                        return InputBindResult.INVALID_DISPLAY_ID
                }
                if (editorInfo != null && editorInfo.packageName !in
                    context.packageManager.getPackagesForUid(registered.uid).orEmpty())
                    return InputBindResult.INVALID_PACKAGE_NAME
                if (focusedWindow == windowToken && editorInfo == null &&
                    startInputFlags and com.android.internal.inputmethod.StartInputFlags.IS_TEXT_EDITOR != 0) {
                    return InputBindResult(
                        InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                        null, null, null, null, -1, false)
                }
                if (focusedClient !== registered && desktop()?.available == true) {
                    focusedClient?.endpoint?.setActive(false, false)
                    registered.endpoint.setActive(true, false)
                }
                focusedWindow = windowToken
                focusedClient = registered
                if (editorInfo != null && inputConnection != null) {
                    val session = desktop()?.start(windowToken, registered.uid, registered.pid, editorInfo, inputConnection)
                    if (session != null) {
                        bindSequence = if (bindSequence == Int.MAX_VALUE) 1 else bindSequence + 1
                        return InputBindResult(InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                            session, null, null, "dev.artzig/.WaylandInputMethod", bindSequence, false)
                    }
                }
                desktop()?.finish(null)
                return if (editorInfo == null) InputBindResult.NULL_EDITOR_INFO else InputBindResult.NO_IME
            } finally { Binder.restoreCallingIdentity(identity) }
        }
    }

    /** Pinned IMMS.show/hideSoftInput, canInteractWithImeLocked and the
     * show/hideCurrentInputLocked branches with no bound method: neither request
     * changes visibility or succeeds. Only the ZeroJank proxy failure path sends
     * RESULT_UNCHANGED_HIDDEN. There is no Android IME process to bind here. */
    override fun showSoftInput(client: IInputMethodClient, windowToken: IBinder?,
        statsToken: android.view.inputmethod.ImeTracker.Token, flags: Int,
        lastClickToolType: Int, resultReceiver: android.os.ResultReceiver?,
        reason: Int, async: Boolean): Boolean = unavailableVisibility(client, resultReceiver)

    override fun hideSoftInput(client: IInputMethodClient, windowToken: IBinder?,
        statsToken: android.view.inputmethod.ImeTracker.Token, flags: Int,
        resultReceiver: android.os.ResultReceiver?, reason: Int, async: Boolean): Boolean =
        unavailableVisibility(client, resultReceiver)

    private fun unavailableVisibility(client: IInputMethodClient,
        resultReceiver: android.os.ResultReceiver?): Boolean {
        synchronized(lock) {
            // Unknown clients remain a programmer error even without a selected IME.
            clients[client.asBinder()] ?: throw IllegalArgumentException("unknown client ${client.asBinder()}")
        }
        if (android.view.inputmethod.Flags.useZeroJankProxy()) {
            resultReceiver?.send(android.view.inputmethod.InputMethodManager.RESULT_UNCHANGED_HIDDEN, null)
        }
        return false
    }

    /** No IME process exists to receive or report visibility transitions. */
    override fun getImeTrackerService(): IImeTracker? = null

    // InputMethodManagerService exposes the system process tracing state to client tracers.
    override fun isImeTraceEnabled(): Boolean =
        com.android.internal.inputmethod.ImeTracing.getInstance().isEnabled

    /** Pinned IMMS's selected-method/subtype lookup against an empty Android
     * IME inventory. The compositor session is not an Android IME service. */
    private fun enforceUser(userId: Int) {
        if (android.os.UserHandle.getCallingUserId() != userId)
            context.enforceCallingOrSelfPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null)
    }
    override fun getCurrentInputMethodInfoAsUser(userId: Int): InputMethodInfo? {
        enforceUser(userId)
        return null
    }
    override fun getLastInputMethodSubtype(userId: Int): InputMethodSubtype? {
        enforceUser(userId)
        return null
    }

    /** IMMS.getInputMethodWindowVisibleHeight checks client registration before
     * querying WMS. No Android IME window exists; compositor panel geometry is
     * not exposed by text-input-v3 and must not become a fabricated inset. */
    override fun getInputMethodWindowVisibleHeight(client: IInputMethodClient): Int {
        synchronized(lock) {
            clients[client.asBinder()] ?: throw IllegalArgumentException("unknown client ${client.asBinder()}")
        }
        return 0
    }

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
