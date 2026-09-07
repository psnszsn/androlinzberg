package dev.artzig.system

import android.content.pm.SigningDetails
import android.os.Binder
import android.os.Build
import android.os.UserHandle
import android.util.apk.ApkSignatureVerifier
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.SharedUserApi

/**
 * PackageManagerLocalImpl snapshot lifetime and signing-test contracts over the
 * hosted package registry. This single-user session has no shared UIDs, disabled
 * system packages, instant apps, or package-visibility filtering.
 */
class HostedPackageManagerLocal(private val packages: HostedPackageManagerService) : PackageManagerLocal {
    override fun withUnfilteredSnapshot(): PackageManagerLocal.UnfilteredSnapshot = Snapshot(packages.packageStateSnapshot())
    override fun withFilteredSnapshot(): PackageManagerLocal.FilteredSnapshot =
        withFilteredSnapshot(Binder.getCallingUid(), Binder.getCallingUserHandle())
    override fun withFilteredSnapshot(callingUid: Int, user: UserHandle): PackageManagerLocal.FilteredSnapshot =
        Snapshot(packages.packageStateSnapshot()).filtered(callingUid, user)

    private class Snapshot(private val states: Map<String, PackageState>) : PackageManagerLocal.UnfilteredSnapshot {
        private var closed = false
        fun checkOpen() { check(!closed) { "Snapshot already closed" } }
        override fun close() { closed = true }
        override fun getPackageStates(): Map<String, PackageState> { checkOpen(); return states }
        override fun getDisabledSystemPackageStates(): Map<String, PackageState> { checkOpen(); return emptyMap() }
        override fun getSharedUsers(): Map<String, SharedUserApi> { checkOpen(); return emptyMap() }
        override fun filtered(callingUid: Int, user: UserHandle): PackageManagerLocal.FilteredSnapshot {
            checkOpen()
            return object : PackageManagerLocal.FilteredSnapshot {
                private var childClosed = false
                private fun checkUsable() { checkOpen(); check(!childClosed) { "Snapshot already closed" } }
                override fun close() { childClosed = true }
                override fun getPackageStates(): Map<String, PackageState> {
                    checkUsable()
                    return if (user.identifier == UserHandle.USER_SYSTEM) states else emptyMap()
                }
                override fun getPackageState(packageName: String): PackageState? = getPackageStates()[packageName]
            }
        }
    }
    override fun reconcileSdkData(volumeUuid: String?, packageName: String, subDirNames: List<String>,
        userId: Int, appId: Int, previousAppId: Int, seInfo: String, flags: Int) {
        throw dev.artzig.ArtzigTestDiagnostics.unsupported("PackageManagerLocal", "reconcileSdkData",
            "SDK sandbox storage is not hosted")
    }
    private fun enforceDebuggable() {
        if (!Build.isDebuggable()) throw SecurityException("This test API is only available on debuggable builds")
    }
    override fun addOverrideSigningDetails(oldSigningDetails: SigningDetails, newSigningDetails: SigningDetails) {
        enforceDebuggable(); ApkSignatureVerifier.addOverrideSigningDetails(oldSigningDetails, newSigningDetails)
    }
    override fun removeOverrideSigningDetails(oldSigningDetails: SigningDetails) {
        enforceDebuggable(); ApkSignatureVerifier.removeOverrideSigningDetails(oldSigningDetails)
    }
    override fun clearOverrideSigningDetails() { enforceDebuggable(); ApkSignatureVerifier.clearOverrideSigningDetails() }
}
