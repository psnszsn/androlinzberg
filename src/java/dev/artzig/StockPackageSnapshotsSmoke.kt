package dev.artzig

import android.os.Process

/** PackageManagerLocal snapshot stability and parent/child lifetime contracts. */
internal object StockPackageSnapshotsSmoke {
    fun run(fixture: String) {
        val packages = dev.artzig.system.HostedPackageManagerService()
        packages.installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        val pkg = checkNotNull(packages.installPackage(fixture))
        val local = dev.artzig.system.HostedPackageManagerLocal(packages)
        val parent = local.withUnfilteredSnapshot()
        val child = parent.filtered(Process.myUid(), android.os.UserHandle.SYSTEM)
        val sibling = parent.filtered(Process.myUid(), android.os.UserHandle.SYSTEM)
        val before = checkNotNull(child.getPackageState(pkg))
        val state = checkNotNull(packages.getPackageStateInternal(pkg)) as dev.artzig.system.HostedPackageState
        val stopped = before.getStateForUser(android.os.UserHandle.SYSTEM).isStopped
        state.userState.setStopped(!stopped)
        check(before.getStateForUser(android.os.UserHandle.SYSTEM).isStopped == stopped)
        local.withUnfilteredSnapshot().use {
            check(it.packageStates.getValue(pkg).getStateForUser(android.os.UserHandle.SYSTEM).isStopped != stopped)
        }
        child.close()
        check(runCatching { child.packageStates }.exceptionOrNull() is IllegalStateException)
        check(sibling.getPackageState(pkg) != null)
        parent.close()
        check(runCatching { sibling.packageStates }.exceptionOrNull() is IllegalStateException)
        check(runCatching { parent.packageStates }.exceptionOrNull() is IllegalStateException)
    }

}
