package dev.artzig.window

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager.LayoutParams.*
import android.view.WindowManagerGlobal
import com.android.server.policy.WindowManagerPolicy
import java.lang.reflect.Proxy

object HostedDesktopWindowPolicySmoke {
    fun run(base: Context) {
        var permitInternal = false
        val context = object : ContextWrapper(base) {
            override fun checkCallingOrSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.INTERNAL_SYSTEM_WINDOW && permitInternal)
                    PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val host = HostedDesktopWindowPolicy()
        val policy = Class.forName("com.android.server.policy.HostedDesktopWindowManagerPolicy")
            .getConstructor(HostedDesktopWindowPolicy::class.java).newInstance(host) as WindowManagerPolicy
        val funcs = Proxy.newProxyInstance(WindowManagerPolicy.WindowManagerFuncs::class.java.classLoader,
            arrayOf(WindowManagerPolicy.WindowManagerFuncs::class.java)) { _, method, _ ->
            error("Unexpected WM callback ${method.name}")
        } as WindowManagerPolicy.WindowManagerFuncs
        policy.init(context, funcs)
        // These are inherited Java default methods, including the overload guard.
        check(policy.getWindowLayerFromTypeLw(TYPE_APPLICATION) == 2)
        check(policy.getWindowLayerFromTypeLw(TYPE_SYSTEM_ALERT, false) == 9)
        check(policy.getWindowLayerFromTypeLw(TYPE_SYSTEM_ALERT, true) == 12)
        check(policy.getWindowLayerFromTypeLw(TYPE_APPLICATION_OVERLAY, false) == 11)
        check(policy.getWindowLayerFromTypeLw(TYPE_POINTER, true, true) == policy.maxWindowLayer)
        check(policy.getSubWindowLayerFromTypeLw(TYPE_APPLICATION_MEDIA) < 0)
        check(policy.getSubWindowLayerFromTypeLw(TYPE_APPLICATION_PANEL) > 0)
        check(runCatching { policy.getWindowLayerFromTypeLw(TYPE_SYSTEM_ALERT) }
            .exceptionOrNull() is IllegalArgumentException)
        check(policy.javaClass.getMethod("getWindowLayerFromTypeLw", Integer.TYPE).declaringClass ==
            WindowManagerPolicy::class.java)
        val op = intArrayOf(123)
        check(policy.checkAddPermission(TYPE_APPLICATION, true, "android", op, 0) ==
            WindowManagerGlobal.ADD_PERMISSION_DENIED)
        check(op[0] == 123) // Rounded-corner denial precedes the output initialization.
        check(policy.checkAddPermission(0, false, "android", op, 0) == WindowManagerGlobal.ADD_INVALID_TYPE)
        check(op[0] == android.app.AppOpsManager.OP_NONE)
        for (type in intArrayOf(TYPE_APPLICATION, TYPE_APPLICATION_PANEL, TYPE_INPUT_METHOD,
            TYPE_WALLPAPER, TYPE_STATUS_BAR)) {
            check(policy.checkAddPermission(type, false, "android", op, 0) == WindowManagerGlobal.ADD_OKAY)
        }
        check(policy.checkAddPermission(TYPE_TOAST, false, "android", op, 0) == WindowManagerGlobal.ADD_OKAY)
        check(op[0] == android.app.AppOpsManager.OP_TOAST_WINDOW)
        check(policy.checkAddPermission(TYPE_POINTER, false, "android", op, 0) == WindowManagerGlobal.ADD_PERMISSION_DENIED)
        permitInternal = true
        check(policy.checkAddPermission(TYPE_POINTER, true, "android", op, 0) == WindowManagerGlobal.ADD_OKAY)
        val config = Configuration().apply {
            keyboard = Configuration.KEYBOARD_NOKEYS
            keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
            hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            navigation = Configuration.NAVIGATION_NONAV
            navigationHidden = Configuration.NAVIGATIONHIDDEN_NO
        }
        policy.adjustConfigurationLw(config, 0, 0)
        check(config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES)
        check(config.keyboardHidden == Configuration.KEYBOARDHIDDEN_NO)
        check(config.navigationHidden == Configuration.NAVIGATIONHIDDEN_YES)
        check(!policy.isKeyguardLocked && !policy.isKeyguardShowing && !policy.isKeyguardSecure(0))
        check(!policy.isKeyguardTrustedLw && !policy.hasNavigationBar())
        var dismissErrors = 0
        policy.dismissKeyguardLw(object : com.android.internal.policy.IKeyguardDismissCallback.Stub() {
            override fun onDismissError() { dismissErrors++ }
            override fun onDismissSucceeded() { error("No keyguard to dismiss") }
            override fun onDismissCancelled() { error("No keyguard to cancel") }
        }, null)
        check(dismissErrors == 1)
        check(runCatching { policy.init(context, funcs) }.exceptionOrNull() is IllegalStateException)
    }
}
