package dev.artzig

import android.app.ActivityThread
import android.content.Context
import android.content.ContextWrapper
import android.content.om.FabricatedOverlay
import android.content.om.OverlayIdentifier
import android.content.om.OverlayManager
import android.content.om.OverlayManagerTransaction
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.UserHandle
import android.util.TypedValue
import com.android.internal.content.om.OverlayManagerImpl
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Public self-targeting transactions, original idmap2 files, and Android resource resolution. */
internal object StockOverlaySmoke {
    fun run() {
        val base: Context = ActivityThread.currentActivityThread().systemContext
        val context = base.createPackageContext("org.example.permissions", 0)
        val manager = checkNotNull(context.getSystemService(OverlayManager::class.java))
        val overlayName = "stock_overlay_smoke"
        val deniedName = "stock_overlay_denied"
        val packageName = context.packageName
        fun unregister(name: String) = manager.commit(OverlayManagerTransaction.newInstance().apply {
            unregisterFabricatedOverlay(OverlayIdentifier(packageName, name))
        })
        fun register(name: String, group: String, resource: String, value: Int) {
            val overlay = FabricatedOverlay.Builder(packageName, name, packageName)
                .setTargetOverlayable(group)
                .setResourceValue("color/$resource", TypedValue.TYPE_INT_COLOR_ARGB8, value)
                .build()
            manager.commit(OverlayManagerTransaction.newInstance().apply { registerFabricatedOverlay(overlay) })
        }
        for (name in listOf("", "../escape", "bad/name")) {
            check(runCatching { OverlayManagerImpl.checkOverlayNameValid(name) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(OverlayManagerImpl.checkOverlayNameValid(overlayName) == overlayName)
        val otherUser = object : ContextWrapper(context) {
            override fun getUser(): UserHandle = UserHandle.of(10)
        }
        check(runCatching { OverlayManagerImpl(otherUser) }.exceptionOrNull() is SecurityException)
        unregister(overlayName)
        try {
            register(overlayName, "StockOverlay", "stock_overlay_color", 0xff345678.toInt())
            val info = manager.getOverlayInfosForTarget(packageName).single { it.overlayName == overlayName }
            check(info.isFabricated && info.isEnabled && info.targetOverlayableName == "StockOverlay")
            val frro = File(info.baseCodePath)
            val idmap = File(frro.parentFile, "$overlayName.idmap")
            check(frro.length() > 0 && idmap.length() > 0)
            val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            check(Files.getPosixFilePermissions(frro.toPath()) == ownerOnly)
            check(Files.getPosixFilePermissions(idmap.toPath()) == ownerOnly)
            val resourceId = context.resources.getIdentifier("stock_overlay_color", "color", packageName)
            check(resourceId != 0 && context.resources.getColor(resourceId, null) == 0xff112233.toInt())
            fun resolvedColor(): Int {
                val target = ApkAssets.loadFromPath(context.applicationInfo.sourceDir)
                try {
                    val overlay = ApkAssets.loadOverlayFromPath(idmap.path, 0)
                    try {
                        AssetManager.Builder().addApkAssets(target).addApkAssets(overlay).build().use { assets ->
                            return Resources(assets, context.resources.displayMetrics,
                                Configuration(context.resources.configuration)).getColor(resourceId, null)
                        }
                    } finally { overlay.close() }
                } finally { target.close() }
            }
            check(resolvedColor() == 0xff345678.toInt()) { "fabricated overlay did not change resource resolution" }
            register(overlayName, "StockOverlay", "stock_overlay_color", 0xffabcdef.toInt())
            check(manager.getOverlayInfosForTarget(packageName).count { it.overlayName == overlayName } == 1)
            check(resolvedColor() == 0xffabcdef.toInt()) { "replacement overlay did not update resource resolution" }
            check(manager.getOverlayInfosForTarget("dev.artzig.no_target").none { it.overlayName == overlayName })
            val failure = runCatching {
                register(deniedName, "StockSystemOnly", "stock_system_color", 0xff000000.toInt())
            }.exceptionOrNull()
            check(failure is RuntimeException && failure.cause is IOException) { "idmap2 accepted an unsatisfied overlayable policy: $failure" }
            check(!File(frro.parentFile, "$deniedName.frro").exists()) { "failed registration left its fabricated overlay" }
            unregister(overlayName)
            check(!frro.exists() && !idmap.exists())
            check(manager.getOverlayInfosForTarget(packageName).none { it.overlayName == overlayName })
        } finally {
            unregister(overlayName)
            unregister(deniedName)
        }
        println("stock self-targeting overlays, idmap policy and resource resolution: PASS")
    }
}
