package com.android.server.pm

import android.content.pm.ApplicationInfo
import com.android.internal.pm.parsing.pkg.PackageImpl
import java.io.File
import java.util.UUID

/** Stock shared-UID flag aggregation and package snapshot ownership. */
object StockSharedUserSmoke {
    fun run() {
        val shared = SharedUserSetting("fixture.shared", 0, 0)
        check(!shared.isSingleUser && !shared.isPrivileged)
        val first = setting("fixture.one", ApplicationInfo.FLAG_SYSTEM,
            ApplicationInfo.PRIVATE_FLAG_PRIVILEGED, 29)
        val second = setting("fixture.two", 0, 0, 35)
        shared.addPackage(first)
        check(shared.isSingleUser && shared.isPrivileged)
        check(shared.seInfoTargetSdkVersion == 29)
        check(shared.packages.single().packageName == "fixture.one")
        val snapshot = shared.snapshot()
        check(snapshot === shared.snapshot())
        shared.addPackage(second)
        check(!shared.isSingleUser && shared.packages.size == 2)
        check(snapshot.isSingleUser && snapshot.packages.size == 1)
        check(shared.snapshot() !== snapshot)
        check(shared.removePackage(first))
        check(!shared.removePackage(first))
        check(shared.isSingleUser && !shared.isPrivileged)
        check(shared.flags and ApplicationInfo.FLAG_SYSTEM == 0)
        val before = shared.snapshot()
        second.setInstalled(false, 0)
        check(shared.snapshot() !== before)
        check(before.packageStates.valueAt(0).getUserStateOrDefault(0).isInstalled)
        check(!shared.packageStates.valueAt(0).getUserStateOrDefault(0).isInstalled)
        check(shared.removePackage(second))
        check(shared.packages.isEmpty() && !shared.isSingleUser)
    }

    private fun setting(name: String, flags: Int, privateFlags: Int, target: Int): PackageSetting {
        val file = File("/data/app/$name/base.apk")
        return PackageSetting(name, null, file, flags, privateFlags, UUID.randomUUID()).apply {
            setPkg(PackageImpl.forTesting(name, file.path).setTargetSdkVersion(target)
                .hideAsParsed().hideAsFinal())
        }
    }
}
