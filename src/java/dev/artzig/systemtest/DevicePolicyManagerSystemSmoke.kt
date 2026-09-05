package dev.artzig.systemtest

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.ServiceManager
import java.util.function.Supplier

/** Focused cross-process assertions for the stock DPM client and hosted policy transport. */
internal object DevicePolicyManagerSystemSmoke {
    fun run(context: Context) {
        check(ServiceManager.getService(Context.DEVICE_POLICY_SERVICE) != null) {
            "device_policy service is published"
        }
        val manager = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
        val absentAdmin = ComponentName(context.packageName, "MissingDeviceAdminReceiver")

        check(!manager.isDeviceManaged) { "the hosted desktop starts unmanaged" }
        check(!manager.isAdminActive(absentAdmin)) { "an unprovisioned receiver is not active" }
        check(manager.activeAdmins == null) { "an unmanaged user has no active admins" }
        check(!manager.isProfileOwnerApp(context.packageName)) { "the app is not a profile owner" }
        check(!manager.isDeviceOwnerApp(context.packageName)) { "the app is not a device owner" }
        check(!manager.isSecurityLoggingEnabled(null)) { "security collection is disabled without an owner" }
        check(
            manager.resources.getString("dev.artzig.missing", Supplier { "host-default" }) ==
                "host-default",
        ) { "missing policy resources preserve the stock fallback contract" }
    }
}
