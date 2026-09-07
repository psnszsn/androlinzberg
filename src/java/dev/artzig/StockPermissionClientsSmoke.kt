package dev.artzig

import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process
import android.permission.PermissionManager
import com.android.internal.os.SystemServerClassLoaderFactory

/** Public client contracts through the published permission Binder service. */
internal object StockPermissionClientsSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            check(failure is T) { failure }
            return
        }
        error("Expected ${T::class.java.simpleName}")
    }

    fun run(context: Context, fixture: String) {
        val manager = checkNotNull(context.getSystemService(PermissionManager::class.java))
        check(manager === context.getSystemService(Context.PERMISSION_SERVICE))
        val appOps = checkNotNull(context.getSystemService(android.app.AppOpsManager::class.java))
        check(appOps === context.getSystemService(Context.APP_OPS_SERVICE))
        check(android.app.AppOpsManager.permissionToOp(android.Manifest.permission.CAMERA) == android.app.AppOpsManager.OPSTR_CAMERA)
        check(android.app.AppOpsManager.permissionToOp("dev.artzig.missing.permission") == null)
        check(android.app.AppOpsManager.strOpToOp(android.app.AppOpsManager.OPSTR_CAMERA) == android.app.AppOpsManager.OP_CAMERA)
        rejects<IllegalArgumentException> { android.app.AppOpsManager.strOpToOp("dev.artzig.missing.op") }
        appOps.checkPackage(Process.myUid(), context.packageName)
        rejects<SecurityException> { appOps.checkPackage(Process.myUid(), "dev.artzig.missing") }
        rejects<SecurityException> { appOps.checkPackage(98765, "dev.artzig.missing") }
        rejects<SecurityException> { appOps.checkPackage(Process.SYSTEM_UID, context.packageName) }
        val appOpsService = com.android.internal.app.IAppOpsService.Stub.asInterface(
            android.os.ServiceManager.getService(Context.APP_OPS_SERVICE))
        check(appOpsService.permissionToOpCode(android.Manifest.permission.CAMERA) == android.app.AppOpsManager.OP_CAMERA)
        check(appOpsService.permissionToOpCode(null) == android.app.AppOpsManager.OP_NONE)
        val pm = context.packageManager
        val camera = pm.getPermissionInfo(android.Manifest.permission.CAMERA, 0)
        check(camera.packageName == "android" && camera.protection == PermissionInfo.PROTECTION_DANGEROUS)
        check(camera.flags and PermissionInfo.FLAG_INSTALLED != 0)
        check(camera.metaData == null)
        camera.name = "mutated client copy"
        check(pm.getPermissionInfo(android.Manifest.permission.CAMERA, 0).name == android.Manifest.permission.CAMERA)
        rejects<PackageManager.NameNotFoundException> { pm.getPermissionInfo("dev.artzig.missing.permission", 0) }
        rejects<PackageManager.NameNotFoundException> { pm.queryPermissionsByGroup("dev.artzig.missing.group", 0) }
        check(pm.getAllPermissionGroups(0).any { it.name == android.Manifest.permission_group.CAMERA })
        check(pm.getPermissionGroupInfo(android.Manifest.permission_group.CAMERA, 0).packageName == "android")

        val source = AttributionSource.Builder(Process.myUid())
            .setPackageName(context.packageName).setAttributionTag("permission-smoke").build()
        check(!manager.isRegisteredAttributionSource(source))
        val registered = manager.registerAttributionSource(source)
        check(manager.isRegisteredAttributionSource(registered))
        check(!manager.isRegisteredAttributionSource(AttributionSource.Builder(registered)
            .setAttributionTag("changed").build()))
        rejects<SecurityException> {
            manager.registerAttributionSource(AttributionSource.Builder(source).setPackageName("dev.artzig.missing").build())
        }
        rejects<SecurityException> {
            manager.registerAttributionSource(AttributionSource.Builder(Process.SYSTEM_UID).setPackageName(context.packageName).build())
        }
        // A two-link untrusted attribution is permitted. A third link must extend a
        // previously registered chain, and changing a registered value invalidates it.
        val two = AttributionSource.Builder(source).setNext(source).build()
        val three = AttributionSource.Builder(source).setNext(two).build()
        rejects<SecurityException> { manager.registerAttributionSource(three) }
        val registeredTwo = manager.registerAttributionSource(two)
        val registeredThree = manager.registerAttributionSource(
            AttributionSource.Builder(source).setNext(registeredTwo).build())
        check(manager.isRegisteredAttributionSource(registeredThree))
        rejects<SecurityException> { android.app.ActivityThread.getPermissionManager().getRegisteredAttributionSourceCount(Process.myUid()) }

        com.android.internal.net.NetworkUtilsInternal.setAllowNetworkingForProcess(true)
        check(!com.android.internal.net.NetworkUtilsInternal.protectFromVpn(-1))
        val socket = android.system.Os.socket(android.system.OsConstants.AF_INET,
            android.system.OsConstants.SOCK_DGRAM, 0)
        try {
            val expected = !(android.system.Os.geteuid() == 0 && System.getenv("ANDROID_NO_USE_FWMARK_CLIENT") != null)
            check(com.android.internal.net.NetworkUtilsInternal.protectFromVpn(socket) == expected)
            check(com.android.internal.net.NetworkUtilsInternal.protectFromVpn(socket.`getInt$`()) == expected)
            check(android.system.Os.getsockoptInt(socket, android.system.OsConstants.SOL_SOCKET,
                android.system.OsConstants.SO_TYPE) == android.system.OsConstants.SOCK_DGRAM)
        } finally { android.system.Os.close(socket) }
        check(!com.android.internal.net.NetworkUtilsInternal.protectFromVpn(socket))
        val pipe = android.system.Os.pipe2(android.system.OsConstants.O_CLOEXEC)
        try {
            check(!com.android.internal.net.NetworkUtilsInternal.protectFromVpn(pipe[0]))
            check(pipe[0].valid())
        } finally { pipe.forEach(android.system.Os::close) }

        val parent = javaClass.classLoader
        val loader = SystemServerClassLoaderFactory.getOrCreateClassLoader(fixture, parent, false)
        check(loader.parent === parent)
        check(SystemServerClassLoaderFactory.getOrCreateClassLoader(fixture, null, true) === loader)
        // AOSP rejects unprefetched production APEX jars before touching their path.
        val profile = android.os.SystemProperties.getBoolean(
            "persist.device_config.runtime_native_boot.profilesystemserver",
            android.os.SystemProperties.getBoolean("dalvik.vm.profilesystemserver", false))
        if (!profile) {
            rejects<RuntimeException> {
                SystemServerClassLoaderFactory.getOrCreateClassLoader("/apex/dev.artzig.unprefetched.jar", parent, false)
            }
        }
    }
}
