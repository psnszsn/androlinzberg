/* Host package-state helper. AOSP PackageArchiver.isArchived is reused here;
 * the full service requires PackageManagerService's installer/unarchive stack. */
package com.android.server.pm;

import com.android.server.pm.pkg.PackageUserState;

public final class PackageArchiver {
    private PackageArchiver() {}
    public static boolean isArchived(PackageUserState userState) {
        return userState.getArchiveState() != null && !userState.isInstalled();
    }
}
