/* artzig stub: app archiving does not exist in a hosted session. */
package com.android.server.pm;

import com.android.server.pm.pkg.PackageUserState;

public final class PackageArchiver {
    private PackageArchiver() {}
    public static boolean isArchived(PackageUserState userState) { return false; }
}
