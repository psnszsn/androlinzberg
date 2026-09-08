package dev.artzig.window

import android.Manifest.permission.*
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.RemoteException
import android.os.UserHandle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.*
import android.view.WindowManagerGlobal
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.server.LocalServices
import com.android.server.policy.WindowManagerPolicy
import com.android.server.wm.DisplayPolicy
import com.android.server.wm.DisplayRotation
import com.android.server.wm.WindowManagerInternal

/**
 * Desktop adaptation of PhoneWindowManager.checkAddPermission, screenTurningOn,
 * finishKeyguardDrawn/finishWindowsDrawn and systemBooted. The phone policy's
 * keyguard, lid sensors and SystemUI hardware collaborators are absent. Stock
 * DisplayPolicy still owns screen/draw state and stock WMS validates tokens.
 * The generated interface adapter inherits AOSP's window-layer default methods.
 */
class HostedDesktopWindowPolicy(private val toggleFullscreen: ((android.os.IBinder) -> Unit)? = null) {
    private lateinit var context: Context
    private lateinit var funcs: WindowManagerPolicy.WindowManagerFuncs
    private lateinit var handler: Handler
    private lateinit var displayPolicy: DisplayPolicy
    private lateinit var rotation: DisplayRotation
    @Volatile private var drawnOnce = false
    @Volatile private var goingToSleep = false
    @Volatile private var displayGoingToSleep = false
    @Volatile private var safeMode = false
    private var currentUser = UserHandle.USER_SYSTEM
    private val allowLockscreenWhenOnDisplays = HashSet<Int>()

    fun init(context: Context, windowManagerFuncs: WindowManagerPolicy.WindowManagerFuncs) {
        check(!this::context.isInitialized)
        this.context = context
        funcs = windowManagerFuncs
        handler = Handler(checkNotNull(Looper.myLooper()))
    }

    fun setDefaultDisplay(info: WindowManagerPolicy.DisplayContentInfo) {
        require(info.display.displayId == Display.DEFAULT_DISPLAY)
        rotation = info.displayRotation
        displayPolicy = rotation.displayPolicy
    }

    fun checkAddPermission(type: Int, isRoundedCornerOverlay: Boolean, packageName: String,
        outAppOp: IntArray, displayId: Int): Int {
        fun permitted(permission: String) =
            context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (isRoundedCornerOverlay && !permitted(INTERNAL_SYSTEM_WINDOW))
            return WindowManagerGlobal.ADD_PERMISSION_DENIED
        outAppOp[0] = AppOpsManager.OP_NONE
        if (type !in FIRST_APPLICATION_WINDOW..LAST_APPLICATION_WINDOW &&
            type !in FIRST_SUB_WINDOW..LAST_SUB_WINDOW &&
            type !in FIRST_SYSTEM_WINDOW..LAST_SYSTEM_WINDOW) return WindowManagerGlobal.ADD_INVALID_TYPE
        if (type !in FIRST_SYSTEM_WINDOW..LAST_SYSTEM_WINDOW) return WindowManagerGlobal.ADD_OKAY
        if (!WindowManager.LayoutParams.isSystemAlertWindowType(type)) {
            when (type) {
                TYPE_TOAST -> {
                    outAppOp[0] = AppOpsManager.OP_TOAST_WINDOW
                    return WindowManagerGlobal.ADD_OKAY
                }
                TYPE_ACCESSIBILITY_OVERLAY -> {
                    if (android.view.contentprotection.flags.Flags.createAccessibilityOverlayAppOpEnabled())
                        outAppOp[0] = AppOpsManager.OP_CREATE_ACCESSIBILITY_OVERLAY
                    return WindowManagerGlobal.ADD_OKAY
                }
                TYPE_INPUT_METHOD, TYPE_WALLPAPER, TYPE_PRESENTATION, TYPE_PRIVATE_PRESENTATION,
                TYPE_VOICE_INTERACTION, TYPE_QS_DIALOG, TYPE_NAVIGATION_BAR_PANEL, TYPE_STATUS_BAR,
                TYPE_NOTIFICATION_SHADE, TYPE_NAVIGATION_BAR, TYPE_STATUS_BAR_ADDITIONAL,
                TYPE_STATUS_BAR_SUB_PANEL, TYPE_VOICE_INTERACTION_STARTING -> return WindowManagerGlobal.ADD_OKAY
            }
            return if (permitted(INTERNAL_SYSTEM_WINDOW)) WindowManagerGlobal.ADD_OKAY
                else WindowManagerGlobal.ADD_PERMISSION_DENIED
        }
        outAppOp[0] = AppOpsManager.OP_SYSTEM_ALERT_WINDOW
        val uid = Binder.getCallingUid()
        if (UserHandle.getAppId(uid) == android.os.Process.SYSTEM_UID) return WindowManagerGlobal.ADD_OKAY
        val appInfo = try {
            context.packageManager.getApplicationInfoAsUser(packageName, 0, UserHandle.getUserId(uid))
        } catch (_: PackageManager.NameNotFoundException) { null }
        if (appInfo == null || (type != TYPE_APPLICATION_OVERLAY && appInfo.targetSdkVersion >= 26))
            return if (permitted(INTERNAL_SYSTEM_WINDOW)) WindowManagerGlobal.ADD_OKAY
                else WindowManagerGlobal.ADD_PERMISSION_DENIED
        if (permitted(SYSTEM_APPLICATION_OVERLAY)) return WindowManagerGlobal.ADD_OKAY
        if (funcs.isCallerVirtualDeviceOwner(displayId, uid) && funcs.isDisplayTrusted(displayId) &&
            permitted(CREATE_VIRTUAL_DEVICE)) return WindowManagerGlobal.ADD_OKAY
        val appOps = checkNotNull(context.getSystemService(AppOpsManager::class.java))
        return when (appOps.noteOpNoThrow(outAppOp[0], uid, packageName, null, "check-add")) {
            AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED -> WindowManagerGlobal.ADD_OKAY
            AppOpsManager.MODE_ERRORED -> if (appInfo.targetSdkVersion < 23) WindowManagerGlobal.ADD_OKAY
                else WindowManagerGlobal.ADD_PERMISSION_DENIED
            else -> if (permitted(SYSTEM_ALERT_WINDOW)) WindowManagerGlobal.ADD_OKAY
                else WindowManagerGlobal.ADD_PERMISSION_DENIED
        }
    }

    fun adjustConfigurationLw(config: Configuration, keyboardPresence: Int, navigationPresence: Int) {
        // No Android lid switch; the desktop seat has a soft input path.
        if (config.keyboard == Configuration.KEYBOARD_NOKEYS)
            config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
        if (config.navigation == Configuration.NAVIGATION_NONAV)
            config.navigationHidden = Configuration.NAVIGATIONHIDDEN_YES
    }

    fun screenTurningOn(displayId: Int, listener: WindowManagerPolicy.ScreenOnListener?) {
        require(displayId == Display.DEFAULT_DISPLAY)
        displayPolicy.screenTurningOn(listener)
        check(handler.post {
            if (displayPolicy.finishKeyguardDrawn()) {
                val wm = checkNotNull(LocalServices.getService(WindowManagerInternal::class.java))
                wm.waitForAllWindowsDrawn(Message.obtain(handler, Runnable { finishWindowsDrawn() }),
                    1000L, Display.INVALID_DISPLAY)
            }
        })
    }

    private fun finishWindowsDrawn() {
        if (!displayPolicy.finishWindowsDrawn()) return
        rotation.updateOrientationListener()
        val listener = displayPolicy.screenOnListener
        if (!displayPolicy.finishScreenTurningOn()) return
        val enable = !drawnOnce && displayPolicy.isAwake
        if (enable) drawnOnce = true
        listener?.onScreenOn()
        if (enable) funcs.enableScreenIfNeeded()
    }

    fun screenTurnedOn(displayId: Int) {
        require(displayId == Display.DEFAULT_DISPLAY)
        displayPolicy.screenTurnedOn()
    }
    fun screenTurningOff(displayId: Int, listener: WindowManagerPolicy.ScreenOffListener?) {
        require(displayId == Display.DEFAULT_DISPLAY)
        funcs.screenTurningOff(displayId, listener)
    }
    fun screenTurnedOff(displayId: Int, isSwappingDisplay: Boolean) {
        require(displayId == Display.DEFAULT_DISPLAY)
        displayPolicy.screenTurnedOff(!isSwappingDisplay || displayGoingToSleep)
        rotation.updateOrientationListener()
    }
    fun startedWakingUp(displayGroupId: Int, reason: Int) {
        require(displayGroupId == Display.DEFAULT_DISPLAY_GROUP)
        displayGoingToSleep = false
        displayPolicy.setAwake(true)
        rotation.updateOrientationListener()
    }
    fun finishedWakingUp(displayGroupId: Int, reason: Int) {
        require(displayGroupId == Display.DEFAULT_DISPLAY_GROUP)
    }
    fun startedWakingUpGlobal(reason: Int) { }
    fun finishedWakingUpGlobal(reason: Int) { }
    fun startedGoingToSleepGlobal(reason: Int) { goingToSleep = true }
    fun finishedGoingToSleepGlobal(reason: Int) { goingToSleep = false }
    fun startedGoingToSleep(displayGroupId: Int, reason: Int) {
        require(displayGroupId == Display.DEFAULT_DISPLAY_GROUP)
        displayGoingToSleep = true
    }
    fun finishedGoingToSleep(displayGroupId: Int, reason: Int) {
        require(displayGroupId == Display.DEFAULT_DISPLAY_GROUP)
        displayGoingToSleep = false
        displayPolicy.setAwake(false)
        rotation.updateOrientationListener()
    }
    fun systemReady() { rotation.updateOrientationListener() }
    fun systemBooted() {
        startedWakingUp(Display.DEFAULT_DISPLAY_GROUP, android.os.PowerManager.WAKE_REASON_UNKNOWN)
        finishedWakingUp(Display.DEFAULT_DISPLAY_GROUP, android.os.PowerManager.WAKE_REASON_UNKNOWN)
        screenTurningOn(Display.DEFAULT_DISPLAY, displayPolicy.screenOnListener)
        screenTurnedOn(Display.DEFAULT_DISPLAY)
    }
    fun enableScreenAfterBoot() {
        displayPolicy.setLidState(WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT)
        funcs.updateRotation(true, false)
    }
    fun isScreenOn(): Boolean = displayPolicy.isScreenOnEarly
    fun okToAnimate(ignoreScreenOn: Boolean): Boolean = (ignoreScreenOn || isScreenOn()) && !goingToSleep
    fun canDismissBootAnimation(): Boolean = displayPolicy.isKeyguardDrawComplete
    fun isKeyguardDrawnLw(): Boolean = drawnOnce

    // PhoneWindowManager's absent-keyguard branches. This never unlocks the host desktop.
    fun isKeyguardHostWindow(attrs: WindowManager.LayoutParams): Boolean = attrs.type == TYPE_NOTIFICATION_SHADE
    fun isKeyguardLocked(): Boolean = false
    fun isKeyguardSecure(userId: Int): Boolean = false
    fun isKeyguardOccluded(): Boolean = false
    fun isKeyguardShowing(): Boolean = false
    fun isKeyguardShowingAndNotOccluded(): Boolean = false
    fun isKeyguardTrustedLw(): Boolean = false
    fun inKeyguardRestrictedKeyInputMode(): Boolean = false
    fun enableKeyguard(enabled: Boolean) { }
    fun setAllowLockscreenWhenOn(displayId: Int, allow: Boolean) {
        if (allow) allowLockscreenWhenOnDisplays.add(displayId)
        else allowLockscreenWhenOnDisplays.remove(displayId)
        // PhoneWindowManager.updateLockScreenTimeout cannot enable its timer
        // without a secure KeyguardServiceDelegate. This desktop has none.
    }
    fun exitKeyguardSecurely(callback: WindowManagerPolicy.OnKeyguardExitResult?) { }
    fun startKeyguardExitAnimation(startTime: Long) { }
    fun onKeyguardOccludedChangedLw(occluded: Boolean) { }
    fun applyKeyguardOcclusionChange(): Int = 0
    fun dismissKeyguardLw(callback: IKeyguardDismissCallback?, message: CharSequence?) {
        try { callback?.onDismissError() } catch (_: RemoteException) { }
    }

    fun interceptKeyBeforeQueueing(event: KeyEvent, policyFlags: Int): Int = WindowManagerPolicy.ACTION_PASS_TO_USER
    fun interceptKeyBeforeDispatching(focusedToken: android.os.IBinder?, event: KeyEvent, policyFlags: Int): Long {
        // Desktop-owned shortcut, following PhoneWindowManager's consumed-key
        // policy: one request on the initial down, consume repeats and key-up.
        val toggle = toggleFullscreen ?: return 0L
        if (focusedToken == null || event.keyCode != KeyEvent.KEYCODE_F11 || !event.hasNoModifiers()) return 0L
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled)
            toggle(focusedToken)
        return -1L
    }
    fun interceptUnhandledKey(event: KeyEvent, focusedToken: android.os.IBinder?): Boolean = false
    fun isGlobalKey(keyCode: Int): Boolean = false
    fun setTopFocusedDisplay(displayId: Int) { require(displayId == Display.DEFAULT_DISPLAY) }
    fun hasNavigationBar(): Boolean = false
    fun getUiMode(): Int = context.resources.configuration.uiMode
    fun isUserSetupComplete(): Boolean = android.provider.Settings.Secure.getIntForUser(
        context.contentResolver, android.provider.Settings.Secure.USER_SETUP_COMPLETE, 0, currentUser) != 0
    fun setCurrentUserLw(newUserId: Int) { currentUser = newUserId }
    fun setSafeMode(safeMode: Boolean) { this.safeMode = safeMode }
    fun dump(prefix: String, writer: java.io.PrintWriter, args: Array<String>?) {
        writer.println("${prefix}Desktop window policy: user=$currentUser safeMode=$safeMode drawnOnce=$drawnOnce")
        writer.println("${prefix}Allow lockscreen while on: $allowLockscreenWhenOnDisplays")
    }
}
