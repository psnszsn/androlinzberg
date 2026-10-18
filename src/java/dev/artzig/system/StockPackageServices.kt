package dev.artzig.system

import android.content.Context
import android.content.pm.IPackageManager
import android.content.pm.PackageManagerInternal
import android.os.ArtModuleServiceManager
import android.os.ServiceManager
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.SystemConfig
import com.android.server.SystemServiceManager
import com.android.server.art.ArtModuleServiceInitializer
import com.android.server.art.DexUseManagerLocal
import com.android.server.compat.PlatformCompat
import com.android.server.pinner.PinnerService
import com.android.server.pm.DexOptHelper
import com.android.server.pm.Installer
import com.android.server.pm.PackageManagerService
import com.android.server.pm.UserManagerService
import com.android.server.pm.verify.domain.DomainVerificationService

/**
 * The stock package stack in the hosted system server, started in the order of
 * pinned `SystemServer.startBootstrapServices` (ArtModuleServiceInitializer,
 * Installer, DomainVerificationService, PackageManagerService.main,
 * DexUseManagerLocal, UserManagerService.LifeCycle) and
 * `startOtherServices` (ArtManagerLocal, updatePackagesIfNeeded,
 * systemReady). PackageManagerService builds its own injector, Settings,
 * UserManagerService, PermissionManagerService, AppsFilter and
 * PackageInstallerService. The host supplies only the daemons Android runs
 * outside system_server: installd ([HostedInstalld]) and artd ([HostedArtd]).
 */
object StockPackageServices {
    class Started(val packageManager: PackageManagerService) {
        val packages: IPackageManager get() = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        val internal: PackageManagerInternal get() = LocalServices.getService(PackageManagerInternal::class.java)
    }

    /**
     * SystemServer.startBootstrapServices "StartAccessCheckingService", before
     * the activity manager creates AppOpsService: the permission and app-op
     * state behind PermissionManagerServiceInterface/AppOpsCheckingServiceInterface.
     */
    @JvmStatic
    fun startAccessChecking(manager: SystemServiceManager) {
        LocalServices.addService(com.android.server.pm.permission.PermissionMigrationHelper::class.java,
            com.android.server.pm.permission.PermissionMigrationHelperImpl())
        LocalServices.addService(com.android.server.appop.AppOpMigrationHelper::class.java,
            com.android.server.appop.AppOpMigrationHelperImpl())
        manager.startService(com.android.server.permission.access.AccessCheckingService::class.java)
    }

    @JvmStatic
    fun startBootstrap(context: Context, manager: SystemServiceManager, platformCompat: PlatformCompat): Started {
        // SystemServer: ART Service reaches its daemons through this registry.
        ArtModuleServiceInitializer.setArtModuleServiceManager(ArtModuleServiceManager())
        ServiceManager.addService("installd", HostedInstalld())
        ServiceManager.addService("artd", HostedArtd())
        LocalServices.addService(PinnerService::class.java, PinnerService())
        val installer = manager.startService(Installer::class.java)
        val domainVerification = DomainVerificationService(context, SystemConfig.getInstance(), platformCompat)
        manager.startService(domainVerification)
        val packageManager = PackageManagerService.main(context, installer, domainVerification, false)
        // DexUseManagerLocal is registered after PackageManagerLocal and before
        // PackageManagerService serves notifyDexLoad.
        LocalManagerRegistry.addManager(DexUseManagerLocal::class.java, DexUseManagerLocal.createInstance(context))
        manager.startService(UserManagerService.LifeCycle::class.java)
        manager.startService(com.android.server.SystemConfigService::class.java)
        registerApplicationIds(LocalServices.getService(PackageManagerInternal::class.java))
        return Started(packageManager)
    }

    /**
     * Zygote specializes a package's processes with the application uid
     * PackageManagerService assigned (Settings.registerAppIdLPw); on the host
     * the broker authenticates that identity. Record every installed
     * application package, and each later install, with the broker.
     */
    private fun registerApplicationIds(packages: PackageManagerInternal) {
        fun register(packageName: String, uid: Int) {
            if (android.os.UserHandle.getUserId(uid) == android.os.UserHandle.USER_SYSTEM && android.os.Process.isApplicationUid(uid))
                dev.artzig.BinderSession.registerPackageUid(packageName, uid)
        }
        val installed = packages.getPackageList(object : PackageManagerInternal.PackageListObserver {
            override fun onPackageAdded(packageName: String, uid: Int) = register(packageName, uid)
            override fun onPackageChanged(packageName: String, uid: Int) = register(packageName, uid)
        })
        for (packageName in installed.packageNames)
            register(packageName, packages.getPackageUid(packageName, 0L, android.os.UserHandle.USER_SYSTEM))
    }

    /** SystemServer.startOtherServices: ART Service, then package updates. */
    @JvmStatic
    fun startOther(context: Context, started: Started) {
        LocalServices.getService(SystemServiceManager::class.java)
            .startService(com.android.server.apphibernation.AppHibernationService::class.java)
        DexOptHelper.initializeArtManagerLocal(context, started.packageManager)
        started.packageManager.updatePackagesIfNeeded()
        started.packageManager.updateMetricsIfNeeded()
    }

    /**
     * Pinned PackageManagerShellCommand.runInstall/doCommitSession for a
     * single APK or split APK directory: a PackageInstaller session written with all APKs,
     * committed, and awaited through a local IntentSender. Returns the
     * installed package name; the caller holds INSTALL_PACKAGES (system).
     */
    @JvmStatic
    fun installApk(context: Context, apk: java.io.File): String {
        val files = dev.artzig.ApkHost.parsePackage(apk.absolutePath).allApkPaths.map { java.io.File(it) }
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        params.setSize(files.sumOf { it.length() })
        val sessionId = installer.createSession(params)
        val receiver = LocalIntentReceiver()
        installer.openSession(sessionId).use { session ->
            for ((index, file) in files.withIndex()) {
                session.openWrite(if (index == 0) "base.apk" else "split_$index.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            session.commit(receiver.intentSender)
        }
        val result = receiver.await()
        val status = result.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
            android.content.pm.PackageInstaller.STATUS_FAILURE)
        check(status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            "Installing $apk failed: ${result.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)}"
        }
        return checkNotNull(result.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME))
    }

    /** PackageManagerShellCommand.LocalIntentReceiver: the session's status Intent. */
    private class LocalIntentReceiver {
        private val results = java.util.concurrent.LinkedBlockingQueue<android.content.Intent>()
        private val sender = object : android.content.IIntentSender.Stub() {
            override fun send(code: Int, intent: android.content.Intent, resolvedType: String?, whitelistToken: android.os.IBinder?,
                finishedReceiver: android.content.IIntentReceiver?, requiredPermission: String?, options: android.os.Bundle?) {
                results.offer(intent)
            }
        }
        val intentSender = android.content.IntentSender(sender as android.content.IIntentSender)
        fun await(): android.content.Intent =
            checkNotNull(results.poll(5, java.util.concurrent.TimeUnit.MINUTES)) { "Package installation timed out" }
    }

    @JvmStatic
    fun systemReady(started: Started) {
        started.packageManager.systemReady()
    }
}
