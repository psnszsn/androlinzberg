package com.android.server.pm

/** PackageProperty keeps install-time population package-private to PMS. The
 * hosted installer uses this access seam; all indexing and lookup remain stock. */
class HostedPackageProperties : PackageProperty() {
    fun installed(pkg: com.android.server.pm.pkg.AndroidPackage) = addAllProperties(pkg)
}
