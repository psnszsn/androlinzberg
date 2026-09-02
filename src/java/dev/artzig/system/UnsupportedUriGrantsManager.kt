package dev.artzig.system

/** Every IUriGrantsManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedUriGrantsManager : android.app.IUriGrantsManager.Stub() {
    override fun takePersistableUriPermission(uri: android.net.Uri?, modeFlags: Int, toPackage: String?, userId: Int) { throw UnsupportedOperationException("IUriGrantsManager.takePersistableUriPermission is not hosted") }
    override fun releasePersistableUriPermission(uri: android.net.Uri?, modeFlags: Int, toPackage: String?, userId: Int) { throw UnsupportedOperationException("IUriGrantsManager.releasePersistableUriPermission is not hosted") }
    override fun grantUriPermissionFromOwner(owner: android.os.IBinder?, fromUid: Int, targetPkg: String?, uri: android.net.Uri?, mode: Int, sourceUserId: Int, targetUserId: Int) { throw UnsupportedOperationException("IUriGrantsManager.grantUriPermissionFromOwner is not hosted") }
    override fun getGrantedUriPermissions(packageName: String?, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUriGrantsManager.getGrantedUriPermissions is not hosted")
    override fun clearGrantedUriPermissions(packageName: String?, userId: Int) { throw UnsupportedOperationException("IUriGrantsManager.clearGrantedUriPermissions is not hosted") }
    override fun getUriPermissions(packageName: String?, incoming: Boolean, persistedOnly: Boolean): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUriGrantsManager.getUriPermissions is not hosted")
    override fun checkGrantUriPermission_ignoreNonSystem(sourceUid: Int, targetPkg: String?, uri: android.net.Uri?, modeFlags: Int, userId: Int): Int = throw UnsupportedOperationException("IUriGrantsManager.checkGrantUriPermission_ignoreNonSystem is not hosted")
}
