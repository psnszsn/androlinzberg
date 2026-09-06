package com.android.server.pm

import android.app.ActivityThread
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.CreateAppDataArgs
import android.os.CreateAppDataResult
import android.os.UserHandle
import android.util.ArraySet
import com.android.server.utils.Watchable
import com.android.server.utils.Watcher
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletionException

/** Stock package snapshots, user isolation and installer batch results. */
object StockPackageSettingsSmoke {
    fun run() {
        val domain = UUID.randomUUID()
        val setting = PackageSetting("dev.artzig.fixture", null, File("/data/app/fixture.apk"),
            ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED, domain)
        setting.setAppId(10001)
        setting.setMimeGroups(mapOf("images" to setOf("image/png")))
        check(setting.isSystem && setting.isPrivileged && setting.appId == 10001)
        check(setting.domainSetId == domain && setting.packageName == "dev.artzig.fixture")
        setting.setInstalled(false, 10)
        setting.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 10, "android")
        check(setting.getStateForUser(UserHandle.of(0)).isInstalled)
        check(!setting.getStateForUser(UserHandle.of(10)).isInstalled)
        check(setting.getStateForUser(UserHandle.of(10)).enabledState ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        var changes = 0
        val observer = object : Watcher() {
            override fun onChange(what: Watchable?) { check(what === setting); changes++ }
        }
        setting.registerObserver(observer)
        val first = setting.snapshot()
        check(first === setting.snapshot())
        setting.setInstalled(true, 10)
        check(changes > 0)
        val second = setting.snapshot()
        check(second !== first && second === setting.snapshot())
        check(!first.getStateForUser(UserHandle.of(10)).isInstalled)
        check(second.getStateForUser(UserHandle.of(10)).isInstalled)
        val before = changes
        check(!setting.setMimeGroup("images", ArraySet(setOf("image/png"))))
        check(changes == before)
        check(setting.setMimeGroup("images", ArraySet(setOf("image/jpeg"))))
        check(changes > before)
        check(first.mimeGroups!!["images"] == setOf("image/png"))
        check(runCatching { setting.setMimeGroup("absent", ArraySet()) }.exceptionOrNull()
            is IllegalArgumentException)
        setting.unregisterObserver(observer)
        check(!setting.isRegisteredObserver(observer))
        testInstaller()
    }

    private fun testInstaller() {
        val context: android.content.Context = ActivityThread.currentActivityThread().systemContext
        val isolated = Installer(context, true)
        check(isolated.isIsolated)
        isolated.onStart()
        val args = Installer.buildCreateAppDataArgs(null, "dev.artzig.fixture", 10,
            Installer.FLAG_STORAGE_CE, 10001, "default", 35, true)
        check(args.previousAppId == 0 && args.appId == 10001 && args.userId == 10)
        check(args.flags == (Installer.FLAG_STORAGE_CE or Installer.FLAG_STORAGE_SDK))
        val placeholder = isolated.createAppData(args)
        check(placeholder.ceDataInode == -1L && placeholder.deDataInode == -1L)
        check(placeholder.exceptionCode == 0 && placeholder.exceptionMessage == null)
        check(isolated.hashSecondaryDexFile("/data/app/fixture.dex", "dev.artzig.fixture",
            10001, null, 0).isEmpty())
        val calls = ArrayList<Int>()
        val fixture = object : Installer(context, true) {
            override fun createAppDataBatched(args: Array<CreateAppDataArgs>): Array<CreateAppDataResult> {
                check(Thread.holdsLock(this))
                calls.add(args.size)
                return args.map { request ->
                    CreateAppDataResult().apply {
                        ceDataInode = request.appId.toLong()
                        if (request.appId == 7) {
                            exceptionCode = -1
                            exceptionMessage = "fixture failure"
                        }
                    }
                }.toTypedArray()
            }
        }
        val batch = Installer.Batch()
        val results = (0..256).map { appId ->
            batch.createAppData(CreateAppDataArgs().apply { this.appId = appId })
        }
        check(results.none { it.isDone })
        synchronized(fixture) { batch.execute(fixture) }
        check(calls == listOf(256, 1))
        check(results[0].join().ceDataInode == 0L && results[256].join().ceDataInode == 256L)
        val failure = runCatching { results[7].join() }.exceptionOrNull()
        check(failure is CompletionException && failure.cause is Installer.InstallerException)
        check(failure.cause!!.message == "fixture failure")
        check(runCatching { batch.createAppData(args) }.exceptionOrNull() is IllegalStateException)
        check(runCatching { synchronized(fixture) { batch.execute(fixture) } }.exceptionOrNull()
            is IllegalStateException)
    }
}
