package dev.artzig.system

import android.app.sdksandbox.SdkSandboxManager
import android.app.sdksandbox.sandboxactivity.SdkSandboxActivityAuthority
import android.content.Context
import android.content.Intent
import com.android.server.LocalManagerRegistry
import com.android.server.sdksandbox.SdkSandboxManagerLocal

/**
 * AOSP ActivityTaskManagerService.startActivityAsUser / SdkSandboxActivityAuthority:
 * preserve sandbox validation before activity resolution. The host has no SDK sandbox
 * package/service, so ordinary launches cannot query its required package name.
 * A published sandbox manager restores the complete stock package/action predicate.
 */
object HostedSdkSandboxActivities {
    private fun manager() = LocalManagerRegistry.getManager(SdkSandboxManagerLocal::class.java)

    @JvmStatic fun isIntent(context: Context, intent: Intent?): Boolean =
        isIntent(context, intent, manager())

    internal fun isIntent(context: Context, intent: Intent?, manager: SdkSandboxManagerLocal?): Boolean {
        if (intent == null) return false
        if (manager == null) return intent.action == SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY
        return SdkSandboxActivityAuthority.isSdkSandboxActivityIntent(context, intent)
    }

    @JvmStatic fun enforceHosting(intent: Intent, uid: Int, packageName: String) =
        enforceHosting(intent, uid, packageName, manager())

    internal fun enforceHosting(intent: Intent, uid: Int, packageName: String, manager: SdkSandboxManagerLocal?) {
        val authority = manager ?: throw UnsupportedOperationException(
            "SDK sandbox activity hosting is unavailable in this session")
        authority.enforceAllowedToHostSandboxedActivity(intent, uid, packageName)
    }
}
