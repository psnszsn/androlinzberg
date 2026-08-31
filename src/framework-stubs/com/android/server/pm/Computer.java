/* artzig stub: the real interface is PackageManagerService's snapshot surface
 * (~200 methods). The vendored resolution/info classes call only these two;
 * the hosted package manager service is the live (non-snapshot) Computer. */
package com.android.server.pm;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.snapshot.PackageDataSnapshot;

public interface Computer extends PackageDataSnapshot {
    AndroidPackage getPackage(String packageName);
    PackageStateInternal getPackageStateInternal(String packageName);
    /** Updated-system-app shadowing does not exist here; always null. */
    default PackageStateInternal getDisabledSystemPackage(String packageName) { return null; }
}
