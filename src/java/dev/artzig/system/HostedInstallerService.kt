package dev.artzig.system

import android.content.Context
import android.content.pm.PackageManagerInternal
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import com.android.server.LocalServices
import java.io.File

/**
 * The `hosted_installer` service: see IHostedInstaller. The host's `adb`
 * counterpart: a launcher hands the system server an APK path, installed
 * through a stock PackageInstaller session ([StockPackageServices.installApk]),
 * and asks it to start the package (`am start`). The session commit extracts
 * native libraries itself.
 *
 * Callers are the system, shell and root, and host session clients: peers that
 * joined with the session cookie (readable only by the session's Linux user)
 * under an identity no installed package owns, so the broker allocated their
 * uid. Installed apps, whose processes run with their package's uid, and
 * isolated processes are refused; apps install through PackageInstaller.
 */
class HostedInstallerService(
    private val context: Context,
    private val activityManager: HostedActivityManagerService,
) : IHostedInstaller.Stub() {
    private fun enforceHostClient(operation: String) {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID || uid == Process.ROOT_UID) return
        val installedOwner = Binder.withCleanCallingIdentity<Boolean> {
            android.app.AppGlobals.getPackageManager().getPackagesForUid(uid)?.isNotEmpty() == true
        }
        if (Process.isIsolated(uid) || !Process.isApplicationUid(uid) || installedOwner) {
            throw SecurityException("UID $uid may not $operation through the host launcher")
        }
    }

    override fun installPackage(apkPath: String?, nativeLibraryDir: String?): String {
        enforceHostClient("install packages")
        val apk = File(checkNotNull(apkPath))
        return Binder.withCleanCallingIdentity<String> { StockPackageServices.installApk(context, apk) }
    }

    /** `am start`: the package's launcher intent in a new task, started by the
     * system on behalf of the host client (ActivityTaskManager callingPackage
     * "android"), exactly as the session owner's desktop controller does. */
    override fun launchPackage(packageName: String?) {
        enforceHostClient("start packages")
        val pkg = checkNotNull(packageName)
        Binder.withCleanCallingIdentity {
            val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(pkg)) {
                "No launcher activity in $pkg"
            }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = android.app.ActivityTaskManager.getService().startActivity(null, "android", null,
                intent, null, null, null, -1, 0, null, null)
            check(android.app.ActivityManager.isStartResultSuccessful(result)) { "Activity launch rejected: $result ($intent)" }
        }
    }

    override fun bindProcessPackage(packageName: String?, processName: String?,
        launchConfiguration: android.content.res.Configuration?) {
        val uid = Binder.getCallingUid()
        val pkg = checkNotNull(packageName)
        if (uid != Process.SYSTEM_UID) {
            // The process identity was admitted for this package by PackageManagerService.
            val expected = Binder.withCleanCallingIdentity<Int> {
                LocalServices.getService(PackageManagerInternal::class.java)
                    .getPackageUid(pkg, 0L, UserHandle.getUserId(uid))
            }
            if (expected == -1) throw SecurityException("$pkg is not installed; install it before starting its process")
            if (expected != uid) throw SecurityException("UID $uid is not package $pkg ($expected)")
        }
        activityManager.noteProcessPackage(uid, pkg, processName ?: pkg, launchConfiguration)
    }
}
