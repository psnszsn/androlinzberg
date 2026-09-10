package com.android.server.pm

import com.android.server.pm.pkg.ArchiveState

/** PackageSetting.setArchiveState is package-private and accepts null to clear.
 * Call that original operation from its Java package; PackageUserStateImpl's
 * public setter incorrectly annotates the same nullable value as NonNull. */
internal fun PackageSetting.setHostedArchiveState(archiveState: ArchiveState?, userId: Int) {
    setArchiveState(archiveState, userId)
}
