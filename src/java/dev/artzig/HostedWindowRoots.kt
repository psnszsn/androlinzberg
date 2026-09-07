package dev.artzig

import android.view.ArtzigDisplay
import android.view.View
import android.view.ViewRootImpl
import android.view.Window
import android.view.WindowManager
import android.view.WindowManagerGlobal

/** Desktop root creation enters AOSP's window registry; geometry comes from the host window. */
object HostedWindowRoots {
    @JvmStatic
    fun attachActivity(activity: android.app.Activity, width: Int, height: Int): ViewRootImpl {
        val window = activity.window
        return attach(window.decorView, width, height, window.attributes, window).also {
            HostedWindowStack.setActivityRoot(it)
        }
    }

    @JvmStatic
    fun unsupportedWindowless(): RuntimeException = ArtzigTestDiagnostics.unsupported(
        "android.view.ViewRootImpl", "<init>", "Windowless SurfaceControl presentation is not hosted")

    @JvmStatic
    fun unsupportedWindowlessOperation(member: String): RuntimeException = ArtzigTestDiagnostics.unsupported(
        "android.view.ViewRootImpl", member,
        "Windowless input and buffer-transaction reporting require an embedded-surface backend")

    /** AOSP SurfaceView.setChildSurfacePackage reparents a remote SurfaceControl hierarchy. */
    @JvmStatic
    fun attachEmbeddedSurface(surfacePackage: android.view.SurfaceControlViewHost.SurfacePackage): Unit =
        throw ArtzigTestDiagnostics.unsupported("android.view.SurfaceView", "setChildSurfacePackage",
            "The hosted video surface cannot embed a remote SurfaceControl hierarchy")

    @JvmStatic
    @JvmOverloads
    fun attach(view: View, width: Int, height: Int,
               params: WindowManager.LayoutParams =
                   (view.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(),
               parent: Window? = null): ViewRootImpl {
        val global = WindowManagerGlobal.getInstance()
        // Hosted activity switching can retain a decor while replacing its root.
        // Finish its previous registration before stock addView checks duplicates.
        if (view.parent is ViewRootImpl) global.removeView(view, true)
        global.addView(view, params, ArtzigDisplay.get(), parent, view.context.userId)
        return checkNotNull(view.viewRootImpl).also { it.setSize(width, height) }
    }
}
