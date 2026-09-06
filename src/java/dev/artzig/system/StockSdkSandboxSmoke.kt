package dev.artzig.system

import android.app.ActivityThread
import android.app.sdksandbox.AppOwnedSdkSandboxInterface
import android.app.sdksandbox.LoadSdkException
import android.app.sdksandbox.SandboxedSdk
import android.app.sdksandbox.SdkSandboxManager
import android.app.sdksandbox.SharedPreferencesKey
import android.app.sdksandbox.SharedPreferencesUpdate
import android.app.sdksandbox.sandboxactivity.SdkSandboxActivityAuthority
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import com.android.server.sdksandbox.SdkSandboxManagerLocal
import java.lang.reflect.Proxy

/** Exercise the stock values across Parcel and activity validation without a sandbox daemon. */
object StockSdkSandboxSmoke {
    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel).also { check(parcel.dataAvail() == 0) }
        } finally { parcel.recycle() }
    }

    fun run() {
        val binder = Binder()
        val owned = copy(AppOwnedSdkSandboxInterface("fixture", 73L, binder), AppOwnedSdkSandboxInterface.CREATOR)
        check(owned.name == "fixture" && owned.version == 73L && owned.`interface` === binder)
        check(copy(SandboxedSdk(binder), SandboxedSdk.CREATOR).`interface` === binder)
        val keys = listOf(SharedPreferencesKey("language", SharedPreferencesKey.KEY_TYPE_STRING),
            SharedPreferencesKey("removed", SharedPreferencesKey.KEY_TYPE_BOOLEAN))
        val data = Bundle().apply { putString("language", "日本語") }
        val update = copy(SharedPreferencesUpdate(keys, data), SharedPreferencesUpdate.CREATOR)
        check(update.keysInUpdate == keys && update.data.getString("language") == "日本語")
        check(!update.data.containsKey("removed"))
        val failure = copy(LoadSdkException(103, "fixture failure", null, data), LoadSdkException.CREATOR)
        check(failure.loadSdkErrorCode == 103 && failure.message == "fixture failure")
        check(failure.extraInformation.getString("language") == "日本語")

        val context = ActivityThread.currentActivityThread().systemContext
        val sandbox = Intent(SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY)
        check(!SdkSandboxActivityAuthority.isSdkSandboxActivityIntent(context, null))
        check(SdkSandboxActivityAuthority.isSdkSandboxActivityIntent(context, sandbox))
        check(!HostedSdkSandboxActivities.isIntent(context, null))
        check(!HostedSdkSandboxActivities.isIntent(context, Intent(Intent.ACTION_MAIN)))
        check(!HostedSdkSandboxActivities.isIntent(context,
            Intent().setComponent(ComponentName("fixture", "fixture.Activity"))))
        check(HostedSdkSandboxActivities.isIntent(context, sandbox))
        try {
            HostedSdkSandboxActivities.enforceHosting(sandbox, 10001, "fixture")
            error("Absent sandbox service accepted an activity launch")
        } catch (expected: UnsupportedOperationException) {
            check(expected.message!!.contains("unavailable"))
        }
        var calls = 0
        val authority = Proxy.newProxyInstance(SdkSandboxManagerLocal::class.java.classLoader,
            arrayOf(SdkSandboxManagerLocal::class.java)) { _, method, args ->
            check(method.name == "enforceAllowedToHostSandboxedActivity")
            check(args!![0] === sandbox && args[1] == 10001 && args[2] == "fixture")
            calls++
            throw SecurityException("fixture denial")
        } as SdkSandboxManagerLocal
        check(HostedSdkSandboxActivities.isIntent(context, sandbox, authority))
        try {
            HostedSdkSandboxActivities.enforceHosting(sandbox, 10001, "fixture", authority)
            error("Sandbox authority denial was swallowed")
        } catch (expected: SecurityException) { check(expected.message == "fixture denial") }
        check(calls == 1)
    }
}
