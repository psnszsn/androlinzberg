package dev.artzig

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.graphics.Rect
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import android.view.MotionEvent
import android.window.SplashScreen
import dev.artzig.window.HostedTransitionInput

/** Wire-visible stock launch options and transition input cancellation. */
object ActivityOptionsSmoke {
    class SplashProbe : android.app.Activity()

    private fun appTaskLaunch(context: android.content.Context) {
        val thread = android.app.ActivityThread.currentActivityThread().applicationThread.asBinder()
        val options = ActivityOptions.makeBasic().setLaunchBounds(Rect(2, 3, 42, 33)).toBundle()
        val uri = android.net.Uri.parse("content://test/shared")
        var result = android.app.ActivityManager.START_SUCCESS
        var calls = 0
        var remoteFailure = false
        val task = object : android.app.IAppTask.Stub(),
            android.app.IAppTask by dev.artzig.system.unsupported<android.app.IAppTask>() {
            override fun asBinder(): android.os.IBinder = this
            override fun startActivity(who: android.os.IBinder?, pkg: String?, feature: String?,
                intent: android.content.Intent?, type: String?, receivedOptions: android.os.Bundle?): Int {
                if (remoteFailure) throw android.os.RemoteException("task died")
                calls++
                check(who === thread && pkg == context.opPackageName && feature == context.attributionTag)
                check(type == "text/plain" && receivedOptions === options)
                check(checkNotNull(checkNotNull(intent).clipData).getItemAt(0).uri == uri)
                check(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                return result
            }
        }
        val instrumentation = android.app.Instrumentation()
        fun launch() = instrumentation.execStartActivityFromAppTask(context, thread, task,
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri), options)
        launch()
        check(calls == 1)
        result = android.app.ActivityManager.START_PERMISSION_DENIED
        var denied = false
        try { launch() } catch (_: SecurityException) { denied = true }
        check(denied && calls == 2)
        remoteFailure = true
        var wrapped = false
        try { launch() } catch (error: RuntimeException) { wrapped = error.cause is android.os.RemoteException }
        check(wrapped)
        println("AppTask: prepared URI grants, caller/options and start-result failures passed")
    }

    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val context = HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 640, 480, 120, 0,
        )
        dev.artzig.systemtest.IntentCreatorChecks.local(context)
        dev.artzig.systemtest.ProcessBoundaryChecks.local()
        appTaskLaunch(context)
        var fullscreenError: Throwable? = null
        SplashProbe().requestFullscreenMode(android.app.Activity.FULLSCREEN_MODE_REQUEST_EXIT,
            object : android.os.OutcomeReceiver<Void, Throwable> {
                override fun onResult(result: Void?) { error("An unrequested fullscreen mode was restored") }
                override fun onError(error: Throwable) { fullscreenError = error }
            })
        check(fullscreenError is IllegalStateException)
        val privilege = android.app.BackgroundStartPrivileges.allowBackgroundActivityStarts(Binder())
        check(privilege.allowsBackgroundActivityStarts() && privilege.allowsBackgroundFgsStarts())
        check(privilege.merge(android.app.BackgroundStartPrivileges.NONE) === privilege)
        val merged = privilege.merge(android.app.BackgroundStartPrivileges.ALLOW_FGS)
        check(merged.allowsBackgroundActivityStarts() && merged.allowsBackgroundFgsStarts())
        check(merged.originatingToken == null)
        check(ActivityOptions.fromBundle(null) == null)
        val defaults = ActivityOptions.makeBasic()
        check(defaults.launchDisplayId == -1 && defaults.launchTaskId == -1)
        check(defaults.pendingIntentBackgroundActivityStartMode ==
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED)
        val bounds = Rect(13, 17, 413, 317)
        val cookie = Binder()
        val options = ActivityOptions.makeBasic()
            .setLaunchBounds(bounds)
            .setLaunchDisplayId(0)
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        options.setLaunchTaskId(42)
        options.setPendingIntentLaunchFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        options.setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
        options.setLaunchCookie(cookie)
        bounds.offset(100, 200)
        check(options.launchBounds == Rect(13, 17, 413, 317))
        val parcel = Parcel.obtain()
        val restored: ActivityOptions
        try {
            parcel.writeBundle(options.toBundle())
            parcel.setDataPosition(0)
            restored = checkNotNull(ActivityOptions.fromBundle(parcel.readBundle(ActivityOptions::class.java.classLoader)))
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }
        check(restored.launchBounds == options.launchBounds && restored.launchDisplayId == 0)
        check(restored.launchTaskId == 42 && restored.launchWindowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM)
        check(restored.launchCookie == cookie)
        check(restored.pendingIntentLaunchFlags == android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        check(restored.pendingIntentBackgroundActivityStartMode == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
        check(restored.pendingIntentCreatorBackgroundActivityStartMode == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        check(restored.splashScreenStyle == SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        val animation = ActivityOptions.fromBundle(
            ActivityOptions.makeCustomAnimation(context, 123, 456).toBundle(),
        )
        check(animation.animationType == ActivityOptions.ANIM_CUSTOM)
        check(animation.customEnterResId == 123 && animation.customExitResId == 456)
        val launchCookie = ActivityOptions.LaunchCookie("option-test")
        val cookieParcel = Parcel.obtain()
        try {
            launchCookie.writeToParcel(cookieParcel, 0)
            cookieParcel.setDataPosition(0)
            check(ActivityOptions.LaunchCookie.CREATOR.createFromParcel(cookieParcel).binder == launchCookie.binder)
        } finally { cookieParcel.recycle() }

        val input = HostedTransitionInput()
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        check(!input.shouldDrop(key))
        input.paused = true
        check(input.shouldDrop(key))
        check(!input.shouldDrop(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        val keyUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        check(!input.shouldDrop(keyUp) && keyUp.isCanceled)
        val down = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 3f, 4f, 0)
        val up = MotionEvent.obtain(1, 3, MotionEvent.ACTION_UP, 3f, 4f, 0)
        try {
            check(input.shouldDrop(down))
            check(!input.shouldDrop(up) && up.action == MotionEvent.ACTION_CANCEL)
            input.paused = false
            check(!input.shouldDrop(down))
        } finally { down.recycle(); up.recycle() }
        splashLifetime(context)
        println("ActivityOptions: launch options, input cancellation and splash listener lifetime passed")
    }

    private fun splashLifetime(context: android.content.Context) {
        val thread = android.app.ActivityThread.currentActivityThread()
        val token = Binder()
        val info = android.content.pm.ActivityInfo().apply {
            name = SplashProbe::class.java.name
            packageName = context.packageName
            applicationInfo = context.applicationInfo
        }
        val activity = thread.startActivityNow(
            null, "splash-lifetime", android.content.Intent().setComponent(
                android.content.ComponentName(info.packageName, info.name),
            ), info, token, null, null, Binder(), null,
        )
        val record = checkNotNull(thread.getActivityClient(token))
        try {
            val splash = activity.splashScreen
            check(splash === activity.splashScreen)
            check(!thread.isHandleSplashScreenExit(token))
            splash.setOnExitAnimationListener { error("No starting window was transferred") }
            check(thread.isHandleSplashScreenExit(token))
            splash.clearOnExitAnimationListener()
            check(!thread.isHandleSplashScreenExit(token))
            splash.setOnExitAnimationListener { error("No starting window was transferred") }
            check(thread.isHandleSplashScreenExit(token))
        } finally {
            // A process-local destroy has no server ActivityRecord to report.
            thread.handleDestroyActivity(record, false, false, "splash-lifetime")
        }
        check(!thread.isHandleSplashScreenExit(token))
    }
}
