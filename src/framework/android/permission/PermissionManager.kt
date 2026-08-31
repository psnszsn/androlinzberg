package android.permission

/**
 * Hosted adaptation of AOSP android.permission.PermissionManager (reduced —
 * the android.permission package is not vendored). Phase 5a needs only the
 * split-permission surface the parsing stack consumes; the client grows into
 * the ApplicationPackageManager delegate in the client-swap phase.
 */
class PermissionManager {
    /** AOSP PermissionManager.SplitPermissionInfo. */
    class SplitPermissionInfo(
        private val splitPermission: String,
        private val newPermissions: List<String>,
        private val targetSdk: Int,
    ) {
        fun getSplitPermission(): String = splitPermission
        fun getNewPermissions(): List<String> = newPermissions
        fun getTargetSdk(): Int = targetSdk
    }

    companion object {
        /** No split-permission table on this host. */
        @JvmStatic
        fun getSplitPermissions(): List<SplitPermissionInfo> = emptyList()
    }
}
