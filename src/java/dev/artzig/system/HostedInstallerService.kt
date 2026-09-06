package dev.artzig.system

import android.os.Binder

/** The `hosted_installer` service: see IHostedInstaller. */
class HostedInstallerService(
    private val packages: HostedPackageManagerService,
    private val activityManager: HostedActivityManagerService,
) : IHostedInstaller.Stub() {
    override fun installPackage(apkPath: String?, nativeLibraryDir: String?): String =
        packages.installPackage(checkNotNull(apkPath), nativeLibraryDir)

    override fun bindProcessPackage(packageName: String?, processName: String?,
        launchConfiguration: android.content.res.Configuration?) {
        val uid = Binder.getCallingUid()
        val pkg = checkNotNull(packageName)
        if (uid != android.os.Process.SYSTEM_UID) packages.bindPackageUid(pkg, uid)
        activityManager.noteProcessPackage(uid, pkg, processName ?: pkg, launchConfiguration)
    }
}
