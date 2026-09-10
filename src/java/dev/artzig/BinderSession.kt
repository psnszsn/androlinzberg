/* artzig host seam: process binder identity and lifecycle.
 *
 * Replaces the hosted HostedBinderRuntime.bootstrap / exitProcessOnDisconnect /
 * assignedUid. The real android.os binder stack connects to the broker lazily
 * on first use; a process calls setIdentity before that first use to name itself
 * and choose the system vs app role (otherwise the AZK_BINDER_* environment
 * decides). The natives are registered by libbinder_ndk (framework_jni.zig).
 */
package dev.artzig

object BinderSession {
    /** Names this process to the broker and selects the system or app role.
     * Must be called before the first binder use. */
    @JvmStatic external fun setIdentity(pkg: String, isSystem: Boolean)

    /** After this call, losing the broker connection exits the process. */
    @JvmStatic external fun setExitOnDisconnect()

    /** The Android uid the broker assigned this process (Process.myUid). */
    @JvmStatic external fun assignedUid(): Int

    /** System-only install boundary: allocate the identity before starting an app.
     * The broker returns the same uid when that package connects later. */
    @JvmStatic external fun reservePackageUid(packageName: String): Int
    /** Supervisor-only one-use exec credential. Contains a secret; never log it. */
    @JvmStatic external fun reserveLaunch(packageName: String, processName: String,
        isolated: Boolean, sequence: Long): String
    @JvmStatic external fun bindLaunch(id: Long, childPid: Int)
    @JvmStatic external fun revokeLaunch(id: Long): Boolean
}
