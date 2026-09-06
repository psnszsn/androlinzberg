package dev.artzig.systemtest

import android.app.BroadcastOptions
import android.app.ActivityThread
import android.app.Application
import android.app.compat.CompatChanges
import android.content.Context
import android.net.Uri

internal object ApplicationBindingEvents {
    val events = ArrayList<String>()
}

open class BindingApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ApplicationBindingEvents.events += "application:attach"
    }

    override fun onCreate() {
        check(ActivityThread.currentApplication() === this)
        check(ActivityThread.currentPackageName() == packageName)
        check(!CompatChanges.isChangeEnabled(BroadcastOptions.CHANGE_ALWAYS_DISABLED)) {
            "disabled compatibility changes must be installed before Application.onCreate"
        }
        check(CompatChanges.isChangeEnabled(BroadcastOptions.CHANGE_ALWAYS_ENABLED))
        ApplicationBindingEvents.events += "application:create"
        super.onCreate()
    }
}

class ProviderBindingApplication : BindingApplication() {
    override fun onCreate() {
        // The application's own provider must already be installed and usable.
        contentResolver.query(Uri.parse("content://dev.artzig.test.b.provider/items"),
            null, null, null, null).use { cursor -> check(cursor != null && cursor.count == 2) }
        super.onCreate()
    }
}
