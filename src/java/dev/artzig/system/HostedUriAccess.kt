package dev.artzig.system

import android.app.IApplicationThread
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import com.android.server.LocalServices
import com.android.server.uri.GrantUri
import com.android.server.uri.UriGrantsManagerInternal

/**
 * AOSP ActivityManagerService.checkUriPermission/grantUriPermission/revokeUriPermission.
 * The stock grant service owns permission state; the host supplies authenticated
 * process attachment instead of AMS's ProcessRecord/zygote process machinery.
 */
internal class HostedUriAccess(
    private val packages: android.content.pm.IPackageManager,
    private val callerUid: (IApplicationThread?) -> Int,
) {
    private val grants: UriGrantsManagerInternal
        get() = checkNotNull(LocalServices.getService(UriGrantsManagerInternal::class.java))

    private fun enforceCaller(operation: String) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw SecurityException("Isolated process not allowed to call $operation")
        }
    }

    fun check(uri: Uri?, pid: Int, uid: Int, mode: Int, userId: Int, full: Boolean): Int {
        enforceCaller(if (full) "checkContentUriPermissionFull" else "checkUriPermission")
        if (pid == Process.myPid()) return PackageManager.PERMISSION_GRANTED
        return if (grants.checkUriPermission(GrantUri(userId, uri, mode), uid, mode, full)) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    fun checkMany(uris: List<Uri?>, pid: Int, uid: Int, mode: Int, userId: Int): IntArray =
        IntArray(uris.size) { index ->
            val uri = uris[index]
            check(ContentProvider.getUriWithoutUserId(uri), pid, uid, mode,
                ContentProvider.getUserIdFromUri(uri, userId), false)
        }

    fun grant(caller: IApplicationThread?, target: String?, uri: Uri?, mode: Int, userId: Int) {
        enforceCaller("grantUriPermission")
        val uid = callerUid(caller)
        requireNotNull(target) { "null target" }
        com.android.internal.util.Preconditions.checkFlagsArgument(mode,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val intent = Intent().setData(ContentProvider.maybeAddUserId(uri, userId)).setFlags(mode)
        val needed = grants.checkGrantUriPermissionFromIntent(intent, uid, target,
            UserHandle.getUserId(uid))
        grants.grantUriPermissionUncheckedFromIntent(needed, null)
    }

    fun revoke(caller: IApplicationThread?, target: String?, uri: Uri?, mode: Int, userId: Int) {
        enforceCaller("revokeUriPermission")
        val uid = callerUid(caller)
        if (uri == null || !Intent.isAccessUriMode(mode)) return
        if (packages.resolveContentProvider(uri.authority,
                (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong(),
                userId) == null) return
        grants.revokeUriPermission(target, uid, GrantUri(userId, uri, mode), mode)
    }

    /**
     * ContentProviderHelper.checkContentProviderUriPermission, using the host's
     * provider acquisition instead of concrete AMS provider/process records.
     * Invoke outside the activity/window lock: a provider can call back into
     * the system while evaluating its dynamic URI permission.
     */
    fun checkProvider(
        uri: Uri, userId: Int, uid: Int, mode: Int,
        release: (android.app.ContentProviderHolder) -> Unit,
        acquire: (String, Int) -> android.app.ContentProviderHolder?,
    ): Int {
        val identity = Binder.clearCallingIdentity()
        try {
            val holder = acquire(uri.authority ?: return PackageManager.PERMISSION_DENIED, userId)
                ?: return PackageManager.PERMISSION_DENIED
            try {
                val packageName = packages.getPackagesForUid(uid)?.firstOrNull()
                    ?: return PackageManager.PERMISSION_DENIED
                return holder.provider.checkUriPermission(
                    android.content.AttributionSource(uid, packageName, null), uri, uid, mode)
            } finally {
                if (!holder.noReleaseNeeded) release(holder)
            }
        } catch (error: Exception) {
            android.util.Log.w("HostedUriAccess", "Provider URI permission check failed", error)
            return PackageManager.PERMISSION_DENIED
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
