package dev.artzig.system

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.Flags
import android.content.pm.PackageManagerInternal
import dev.artzig.system.HostedPackageSnapshot
import com.android.server.pm.PackageArchiver
import dev.artzig.ArtzigTestDiagnostics

/**
 * ActivityStarter's missing-class retry through PackageArchiver. The host package
 * store does not archive installed APKs, and Android's installer/unarchive service
 * graph is absent. Preserve the stock flag and archived-component predicate; an
 * unexpected archived record must diagnose that missing backend, not launch a
 * different activity or report that an unarchive request succeeded.
 */
object HostedPackageArchiving {
    @JvmStatic fun resolveMissingActivity(packages: PackageManagerInternal,
        intent: Intent, userId: Int): Int {
        if (!Flags.archiving()) return ActivityManager.START_CLASS_NOT_FOUND
        return resolveMissingActivity(packages.snapshot() as HostedPackageSnapshot, intent, userId)
    }

    internal fun resolveMissingActivity(snapshot: HostedPackageSnapshot, intent: Intent, userId: Int): Int {
        // PackageArchiver.getPackageNameFromIntent / isIntentResolvedToArchivedApp.
        val component = intent.component ?: return ActivityManager.START_CLASS_NOT_FOUND
        val packageName = intent.`package` ?: component.packageName
        val state = snapshot.getPackageStateInternal(packageName)
            ?: return ActivityManager.START_CLASS_NOT_FOUND
        val userState = state.getUserStateOrDefault(userId)
        if (!PackageArchiver.isArchived(userState)) return ActivityManager.START_CLASS_NOT_FOUND
        if (userState.archiveState!!.activityInfos.none { it.originalComponentName == component }) {
            return ActivityManager.START_CLASS_NOT_FOUND
        }
        throw ArtzigTestDiagnostics.unsupported("PackageArchiver", "requestUnarchiveOnActivityStart",
            "An archived activity requires the unavailable hosted package-unarchive backend")
    }
}
