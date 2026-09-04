package com.android.server.pm

/** Package-private AOSP defaults exposed to the host-owned package-state adapter. */
object HostedPackageStateDefaults {
    @JvmStatic
    fun emptyInstallSource(): InstallSource = InstallSource.EMPTY

    @JvmStatic
    fun emptyKeySetData(): PackageKeySetData = PackageKeySetData()
}
