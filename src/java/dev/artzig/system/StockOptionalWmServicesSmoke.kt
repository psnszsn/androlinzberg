package dev.artzig.system

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.android.server.pm.PackageArchiver
import com.android.server.pm.pkg.ArchiveState
import dev.artzig.ArtzigTestDiagnostics
import org.json.JSONObject

/** Optional WM collaborators preserve negative results and fail visibly on absent backends. */
object StockOptionalWmServicesSmoke {
    private fun unsupported(owner: String, member: String, operation: () -> Unit) {
        val mark = ArtzigTestDiagnostics.issueSequence()
        check(runCatching(operation).exceptionOrNull() is UnsupportedOperationException)
        val issues = JSONObject(ArtzigTestDiagnostics.snapshot(Long.MAX_VALUE, mark)).getJSONArray("issues")
        check(issues.length() == 1)
        check(issues.getJSONObject(0).getString("owner") == owner)
        check(issues.getJSONObject(0).getString("member") == member)
    }

    fun run(context: Context, apk: String) {
        val arguments = Bundle().apply { putString("fixture", "retained") }
        val token = Binder()
        check(!HostedAssistSessions(null).showFromActivity(arguments, "fixture-attribution", token))
        check(arguments.getString("fixture") == "retained")
        unsupported("AssistUtils", "showSessionForActiveService") {
            HostedAssistSessions(Binder()).showFromActivity(arguments, null, token)
        }

        // As in the other in-process package fixtures, this anonymous test peer
        // does not own the system_server cache-property namespace.
        android.app.PropertyInvalidatedCache.disableForTestMode()
        val packages = HostedPackageManagerService().apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val packageName = verifyPackageObservers(packages, apk)
        val uid = packages.getPackageUid(packageName, 0L, 0)
        check(uid >= android.os.Process.FIRST_APPLICATION_UID)
        val state = packages.getPackageStateInternal(packageName) as HostedPackageState
        val component = ComponentName(packageName, "$packageName.ArchivedActivity")
        val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
        fun result(value: Intent = intent, userId: Int = 0) =
            HostedPackageArchiving.resolveMissingActivity(packages, value, userId)
        check(!PackageArchiver.isArchived(state.userState))
        check(result() == ActivityManager.START_CLASS_NOT_FOUND)
        state.userState.setInstalled(false)
        check(!PackageArchiver.isArchived(state.userState))
        state.userState.setArchiveState(ArchiveState(
            listOf(ArchiveState.ArchiveActivityInfo("Fixture", component, null, null)), "Fixture installer", 1L))
        check(PackageArchiver.isArchived(state.userState))
        check(result(Intent(Intent.ACTION_MAIN)) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(Intent(intent).setClassName(packageName, "$packageName.OtherActivity")) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(Intent(intent).setPackage("missing.fixture")) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(userId = 10) == ActivityManager.START_CLASS_NOT_FOUND)
        unsupported("PackageArchiver", "requestUnarchiveOnActivityStart") { result() }
        state.userState.setInstalled(true)
        check(!PackageArchiver.isArchived(state.userState))
        check(result() == ActivityManager.START_CLASS_NOT_FOUND)

        val launcher = HostedLauncherAppsService(context, packages, HostedPermissions(packages))
        fun shortcut(callerUid: Int, callerPackage: String) = launcher.startShortcut(
            callerUid, android.os.Process.myPid(), callerPackage, packageName, null,
            "fixture-shortcut", null, null, 0)
        val spoof = runCatching { shortcut(uid + 1, packageName) }.exceptionOrNull()
        check(spoof is SecurityException && spoof.message == "Calling package name mismatch")
        val spoofPlatform = runCatching { shortcut(uid, "android") }.exceptionOrNull()
        check(spoofPlatform is SecurityException && spoofPlatform.message == "Calling package name mismatch")
        val denied = runCatching { shortcut(uid, packageName) }.exceptionOrNull()
        check(denied is SecurityException && denied.message!!.contains("not the default launcher"))
    }

    private fun verifyPackageObservers(packages: HostedPackageManagerService, apk: String): String {
        val serviceClass = android.content.pm.PackageManagerInternal::class.java
        val previous = com.android.server.LocalServices.getService(serviceClass)
        val internal = Class.forName("android.content.pm.HostedPackageManagerInternal")
            .getConstructor(HostedPackageManagerService::class.java).newInstance(packages)
            as android.content.pm.PackageManagerInternal
        com.android.server.LocalServices.removeServiceForTest(serviceClass)
        com.android.server.LocalServices.addService(serviceClass, internal)
        val events = ArrayList<String>()
        val snapshot = internal.getPackageList(object : android.content.pm.PackageManagerInternal.PackageListObserver {
            override fun onPackageAdded(name: String, uid: Int) {
                check(packages.getPackageUid(name, 0L, 0) == uid)
                events.add("added:$name")
            }
            override fun onPackageChanged(name: String, uid: Int) { events.add("changed:$name") }
            override fun onPackageRemoved(name: String, uid: Int) { error("Unexpected removal") }
        })
        try {
            check(snapshot.packageNames == listOf("android"))
            val name = packages.installPackage(apk)
            check(events == listOf("added:$name"))
            check(snapshot.packageNames == listOf("android"))
            internal.getPackageList(null).use { check(it.packageNames.toSet() == setOf("android", name)) }
            check(packages.installPackage(apk) == name)
            check(events.size == 1)
            packages.setApplicationEnabledSetting(name, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP, 0, name)
            check(events == listOf("added:$name", "changed:$name"))
            snapshot.close()
            snapshot.close()
            packages.setApplicationEnabledSetting(name, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP, 0, name)
            check(events.size == 2)
            return name
        } finally {
            snapshot.close()
            com.android.server.LocalServices.removeServiceForTest(serviceClass)
            if (previous != null) com.android.server.LocalServices.addService(serviceClass, previous)
        }
    }
}
