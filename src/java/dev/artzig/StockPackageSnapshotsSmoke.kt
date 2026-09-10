package dev.artzig

import android.os.Process
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.os.UserHandle
import dev.artzig.system.HostedPackageSnapshot

/** PackageManagerLocal snapshot stability and parent/child lifetime contracts. */
internal object StockPackageSnapshotsSmoke {
    fun run(fixture: String) {
        verifyUserAuthority()
        val packages = dev.artzig.system.HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle()))
        packages.installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        val empty = packages.snapshotPackages()
        val pkg = checkNotNull(packages.installPackage(fixture))
        check(empty.getPackage(pkg) == null && empty.getPackageStateInternal(pkg) == null)
        val component = ComponentName(pkg, "android.inputmethodservice.InputMethodService")
        check(empty.componentResolver.getService(component) == null)
        check(packages.snapshotPackages().componentResolver.getService(component) != null)
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
        local.withFilteredSnapshot(Process.myUid(), UserHandle.of(123)).use {
            check(it.packageStates.isEmpty() && it.getPackageState(pkg) == null)
        }
        verifyInternalSnapshot(packages, pkg, state)
    }

    private fun verifyUserAuthority() {
        val lifecycle = dev.artzig.system.HostedUserLifecycle()
        val users = object : dev.artzig.system.HostedUserManagerInternal(lifecycle) {
            override fun exists(userId: Int): Boolean = userId == 42
            override fun getUserInfo(userId: Int): android.content.pm.UserInfo? =
                if (userId == 42) android.content.pm.UserInfo(42, "profile", null,
                    android.content.pm.UserInfo.FLAG_PROFILE or android.content.pm.UserInfo.FLAG_MANAGED_PROFILE,
                    android.os.UserManager.USER_TYPE_PROFILE_MANAGED) else null
        }
        val badges = com.android.server.pm.UserNeedsBadgingCache(users)
        check(badges.get(42) && !badges.get(0))
        val resolver = com.android.server.pm.resolution.ComponentResolver(users, badges)
        val snapshot = HostedPackageSnapshot(emptyMap(), resolver.snapshot())
        val intent = Intent("dev.artzig.absent.service")
        // An existing user with no match receives an empty list; a nonexistent
        // user receives null. Both the live and captured resolver must use the
        // supplied authority, including when it differs from the system user.
        check(resolver.queryServices(snapshot, intent, null, 0L, 42)?.isEmpty() == true)
        check(snapshot.componentResolver.queryServices(snapshot, intent, null, 0L, 42)?.isEmpty() == true)
        check(snapshot.componentResolver.queryServices(snapshot, intent, null, 0L, 0) == null)
        val first = checkNotNull(lifecycle.userInfo(0))
        first.name = "changed by caller"
        check(lifecycle.userInfo(0)?.name == "host" && lifecycle.userInfo(42) == null)
    }

    private fun verifyInternalSnapshot(packages: dev.artzig.system.HostedPackageManagerService,
        packageName: String, state: dev.artzig.system.HostedPackageState) {
        // Exercise the same generated local-service entry point used by ATMS.
        val internal = Class.forName("android.content.pm.HostedPackageManagerInternal")
            .getConstructor(dev.artzig.system.HostedPackageManagerService::class.java)
            .newInstance(packages) as PackageManagerInternal
        check(internal.packageArchiver === packages.getPackageArchiver())
        state.userState.setStopped(false)
        state.transientState.setUsesLibraryFiles(listOf("before"))
        val snapshot = internal.snapshot() as HostedPackageSnapshot
        val before = checkNotNull(snapshot.getPackageStateInternal(packageName))
        val beforeUid = before.appId
        val manifestUid = checkNotNull(before.pkg).uid
        val signed = checkNotNull(before.pkg).isSignedWithPlatformKey
        internal.addIsolatedUid(99000, manifestUid)
        val owned = internal.snapshot() as HostedPackageSnapshot
        check(owned.getIsolatedOwner(99000) == manifestUid)
        check(owned.isCallerSameApp(packageName, 99000, true))
        check(!owned.isCallerSameApp(packageName, 99000, false))
        check(packages.getPackagesForUid(99000) == null)
        check(packages.checkUidPermission(android.Manifest.permission.CAMERA, 99000) == PackageManager.PERMISSION_DENIED)
        internal.removeIsolatedUid(99000)
        check(owned.getIsolatedOwner(99000) == manifestUid) // Captured revision remains stable.
        check(runCatching { (internal.snapshot() as HostedPackageSnapshot).getIsolatedOwner(99000) }
            .exceptionOrNull() is IllegalStateException)
        check(runCatching { snapshot.getIsolatedOwner(99000) }.exceptionOrNull() is IllegalStateException)

        val intent = Intent("android.view.InputMethod").setPackage(packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        val flags = (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
        fun matches(view: HostedPackageSnapshot) = view.componentResolver.queryServices(view, intent, null, flags, 0)
        check(matches(snapshot).orEmpty().isNotEmpty())
        check(snapshot.getApplicationInfo(packageName, 0L, 0)?.uid == manifestUid)
        check(snapshot.getApplicationInfo(packageName, 0L, 123) == null)
        check(snapshot.getPackageUid(packageName, 0L, 123) == -1)
        check(snapshot.queryHomeActivities(packageName, 123).isEmpty())
        check(!snapshot.hasUnarchiveReceiver("missing.fixture", 0))

        state.appId = beforeUid + 1
        (state.pkg as com.android.internal.pm.parsing.pkg.PackageImpl).setUid(manifestUid + 1)
        (state.pkg as com.android.internal.pm.parsing.pkg.PackageImpl).setSignedWithPlatformKey(!signed)
        state.userState.setEnabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        state.userState.setInstalled(false)
        state.transientState.setUsesLibraryFiles(listOf("after"))
        val after = internal.snapshot() as HostedPackageSnapshot
        val afterState = checkNotNull(after.getPackageStateInternal(packageName))
        check(before.appId == beforeUid && afterState.appId == beforeUid + 1)
        check(checkNotNull(snapshot.getPackage(packageName)).uid == manifestUid)
        check(checkNotNull(after.getPackage(packageName)).uid == manifestUid + 1)
        check(checkNotNull(before.pkg).isSignedWithPlatformKey == signed)
        check(checkNotNull(afterState.pkg).isSignedWithPlatformKey != signed)
        val platformFlag = android.content.pm.ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
        check((before.privateFlags and platformFlag != 0) == signed)
        check((afterState.privateFlags and platformFlag != 0) != signed)
        check(snapshot.getApplicationInfo(packageName, 0L, 0)?.uid == manifestUid)
        check(after.getApplicationInfo(packageName, 0L, 0) == null)
        check(afterState.getUserStateOrDefault(0).dataExists())
        check(after.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong(), 0)?.uid == manifestUid + 1)
        val ceInode = state.userState.ceDataInode
        val deInode = state.userState.deDataInode
        state.userState.setCeDataInode(0)
        state.userState.setDeDataInode(0)
        check(packages.snapshotPackages().getApplicationInfo(packageName,
            PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong(), 0) == null)
        check(after.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong(), 0)?.uid == manifestUid + 1)
        state.userState.setCeDataInode(ceInode)
        state.userState.setDeDataInode(deInode)
        check(before.getUserStateOrDefault(0).isInstalled)
        check(before.getStateForUser(UserHandle.SYSTEM).isInstalled)
        check(before.userStates.get(0).isInstalled)
        check(!afterState.getUserStateOrDefault(0).isInstalled)
        check(before.transientState.usesLibraryFiles == listOf("before"))
        check(afterState.transientState.usesLibraryFiles == listOf("after"))
        check(matches(snapshot).orEmpty().isNotEmpty())
        check(matches(after).orEmpty().isEmpty())
        verifyCrossUserSnapshots(packages, state)
    }

    private fun verifyCrossUserSnapshots(packages: dev.artzig.system.HostedPackageManagerService,
        state: dev.artzig.system.HostedPackageState) {
        val uid = state.appId
        check(uid >= Process.FIRST_APPLICATION_UID)
        val full = android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
        val partial = android.Manifest.permission.INTERACT_ACROSS_USERS
        state.grantedPermissions.remove(full)
        state.grantedPermissions.remove(partial)
        val denied = packages.snapshotPackages()
        denied.enforceCrossUserPermission(uid, 0, true, true, "same user")
        check(runCatching { denied.enforceCrossUserPermission(uid, 10, false, true, "query") }
            .exceptionOrNull() is SecurityException)
        state.grantedPermissions.add(partial)
        val limited = packages.snapshotPackages()
        limited.enforceCrossUserPermission(uid, 10, false, true, "query")
        check(runCatching { limited.enforceCrossUserPermission(uid, 10, true, true, "archive") }
            .exceptionOrNull() is SecurityException)
        state.grantedPermissions.add(full)
        packages.snapshotPackages().enforceCrossUserPermission(uid, 10, true, true, "archive")
        check(runCatching { denied.enforceCrossUserPermission(uid, 10, false, true, "query") }
            .exceptionOrNull() is SecurityException)
        val restricted = HostedPackageSnapshot(emptyMap(), denied.componentResolver,
            setOf(0), emptyMap(), setOf(0))
        check(runCatching { restricted.enforceCrossUserPermission(Process.SHELL_UID, 0, true, true, "archive") }
            .exceptionOrNull() is SecurityException)
        restricted.enforceCrossUserPermission(Process.SYSTEM_UID, 10, true, true, "archive")
        check(runCatching { restricted.enforceCrossUserPermission(Process.SYSTEM_UID, -1, true, true, "archive") }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
