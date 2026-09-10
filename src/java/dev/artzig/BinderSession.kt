/* artzig host seam: process binder identity and lifecycle.
 *
 * The launcher authenticates its package and role before creating ART. Java
 * bootstrap code may confirm the same identity, but cannot change it after
 * native connection. These natives are registered by libbinder_ndk.
 */
package dev.artzig

object BinderSession {
    /** Confirms the launcher's immutable package and role. Standalone embeddings
     * may configure them before their first Binder connection. */
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
