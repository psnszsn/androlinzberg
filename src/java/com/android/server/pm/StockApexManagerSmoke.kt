package com.android.server.pm

import android.apex.ApexInfo
import android.apex.ApexSessionInfo
import android.apex.IApexService
import android.os.IBinder
import com.android.internal.pm.parsing.pkg.PackageImpl
import com.android.internal.pm.pkg.component.ParsedApexSystemServiceImpl
import dev.artzig.system.unsupported

/** Exercise stock cache ownership and scan prerequisites through its service seam. */
object StockApexManagerSmoke {
    fun run() {
        var activeCalls = 0
        val info = ApexInfo().apply {
            moduleName = "com.android.fixture"
            modulePath = "/data/apex/active/fixture.apex"
            preinstalledModulePath = "/system/apex/fixture.apex"
            isActive = true
            isFactory = false
            activeApexChanged = true
        }
        val service = object : IApexService.Stub(), IApexService by unsupported<IApexService>() {
            override fun asBinder(): IBinder = this
            override fun getActivePackages(): Array<ApexInfo> { activeCalls++; return arrayOf(info) }
            override fun getAllPackages(): Array<ApexInfo> = arrayOf(info)
            override fun getSessions(): Array<ApexSessionInfo> = arrayOf(
                ApexSessionInfo().apply { sessionId = 17; isStaged = true })
            override fun getStagedSessionInfo(id: Int): ApexSessionInfo =
                ApexSessionInfo().apply { sessionId = id; isUnknown = id != 17; isStaged = id == 17 }
        }
        val manager = Fixture(service)
        check(runCatching { manager.getActivePackageNameForApexModuleName(info.moduleName) }
            .exceptionOrNull() is IllegalStateException)
        val first = manager.activeApexInfos
        val active = first.single()
        check(active.apexModuleName == info.moduleName && !active.isFactory && active.activeApexChanged)
        check(active.apexDirectory.path == "/apex/com.android.fixture")
        check(active.apexFile.path == info.modulePath)
        check(active.preInstalledApexPath.path == info.preinstalledModulePath)
        first.clear()
        check(manager.activeApexInfos.size == 1 && activeCalls == 1)
        check(manager.allApexInfos.single().moduleName == info.moduleName)
        check(manager.sessions[17].isStaged)
        check(manager.getStagedSessionInfo(17)!!.isStaged)
        check(manager.getStagedSessionInfo(99) == null)
        manager.notifyScanResult(emptyList())
        check(manager.getActivePackageNameForApexModuleName(info.moduleName) == null)
        check(manager.getApexModuleNameForPackageName("missing") == null)
        check(manager.getApksInApex("missing").isEmpty())
        check(manager.getActiveApexPackageNameContainingPackage("missing") == null)

        val pkg = PackageImpl.forTesting("com.android.fixture.package")
            .addApexSystemService(ParsedApexSystemServiceImpl(
                "fixture.Last", "/apex/com.android.fixture/last.jar", null, null, 0))
            .addApexSystemService(ParsedApexSystemServiceImpl(
                "fixture.First", "/apex/com.android.fixture/first.jar", null, null, 10))
            .hideAsParsed().hideAsFinal()
        val scan = ApexManager.ScanResult(info, pkg, pkg.packageName)
        manager.notifyScanResult(listOf(scan))
        check(manager.getActivePackageNameForApexModuleName(info.moduleName) == pkg.packageName)
        check(manager.getApexModuleNameForPackageName(pkg.packageName) == info.moduleName)
        check(manager.apexSystemServices.map { it.name } == listOf("fixture.First", "fixture.Last"))
        val contained = PackageImpl.forTesting("fixture.contained", "/apex/com.android.fixture/app/base.apk")
            .hideAsParsed().hideAsFinal()
        manager.registerApkInApex(contained)
        check(manager.getApksInApex(pkg.packageName) == listOf(contained.packageName))
        check(manager.getActiveApexPackageNameContainingPackage(contained.packageName) == pkg.packageName)
        val duplicate = Fixture(service)
        check(runCatching { duplicate.notifyScanResult(listOf(scan, scan)) }
            .exceptionOrNull() is IllegalStateException)
    }

    private class Fixture(private val service: IApexService) : ApexManager.ApexManagerImpl() {
        override fun waitForApexService(): IApexService = service
    }
}
