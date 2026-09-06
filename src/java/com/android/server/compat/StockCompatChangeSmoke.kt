package com.android.server.compat

import android.app.compat.PackageOverride
import android.content.pm.ApplicationInfo
import android.content.AttributionSource
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.Handler
import android.os.PermissionEnforcer
import com.android.internal.compat.AndroidBuildClassifier
import com.android.internal.compat.ChangeReporter
import com.android.internal.compat.OverrideAllowedState
import com.android.server.compat.overrides.Overrides
import com.android.server.compat.overrides.XmlWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Generated compatibility schemas feeding stock target-SDK and override policy. */
object StockCompatChangeSmoke {
    fun run() {
        val config = com.android.server.compat.config.XmlParser.read(input("""
            <config><compat-change id="17" name="TARGET_GATE" enableSinceTargetSdk="35"/>
            <compat-change id="18" name="DISABLED" disabled="true"/>
            <future-extension><ignored/></future-extension></config>
        """))!!
        check(config.compatChange.size == 2)
        val change = CompatChange(config.compatChange[0])
        val disabled = CompatChange(config.compatChange[1])
        val build = object : AndroidBuildClassifier() {
            override fun platformTargetSdk(): Int = 36
        }
        val app = ApplicationInfo().apply { packageName = "fixture.compat"; targetSdkVersion = 34 }
        check(!change.isEnabled(app, build))
        app.targetSdkVersion = 35
        check(change.isEnabled(app, build))
        check(!disabled.isEnabled(app, build))
        val future = CompatChange(19, "FUTURE", -1, 37, false, false, null, false)
        app.targetSdkVersion = 10000
        check(!future.isEnabled(app, build))

        val allowed = OverrideAllowedState(OverrideAllowedState.ALLOWED, 35, 35)
        var notifications = 0
        change.registerListener { name -> check(name == app.packageName); notifications++ }
        change.addPackageOverride(app.packageName, PackageOverride.Builder()
            .setMinVersionCode(10).setMaxVersionCode(20).setEnabled(false).build(), allowed, 15L)
        check(!change.isEnabled(app, build) && notifications == 1)
        val saved = Overrides().apply { changeOverrides.add(change.saveOverrides()!!) }
        val text = StringWriter()
        XmlWriter(PrintWriter(text)).use { XmlWriter.write(it, saved) }
        val loaded = com.android.server.compat.overrides.XmlParser.read(input(text.toString()))!!
        val restored = CompatChange(config.compatChange[0])
        restored.loadOverrides(loaded.changeOverrides.single())
        check(!restored.isEnabled(app, build))
        restored.recheckOverride(app.packageName, allowed, 21L)
        check(restored.isEnabled(app, build))
        check(change.removePackageOverride(app.packageName, allowed, 15L))
        check(change.isEnabled(app, build) && notifications == 2)
        check(!change.removePackageOverride(app.packageName, allowed, 15L))
        check(com.android.server.compat.config.XmlParser.read(input("<unknown/>")) == null)
        check(runCatching { com.android.server.compat.config.XmlParser.read(
            input("<config><compat-change id=\"invalid\"/></config>")) }.exceptionOrNull()
            is NumberFormatException)
        testService()
    }

    private fun testService() {
        var allowed = true
        var receivers = 0
        val enforcer = object : PermissionEnforcer() {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (allowed) 0 else -1
            override fun checkPermission(permission: String, source: AttributionSource): Int = if (allowed) 0 else -1
        }
        val context = object : ContextWrapper(android.app.ActivityThread.currentActivityThread().systemContext) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.PERMISSION_ENFORCER_SERVICE) enforcer else super.getSystemService(name)
            override fun registerReceiverForAllUsers(receiver: BroadcastReceiver?, filter: IntentFilter,
                permission: String?, scheduler: Handler?): Intent? {
                check(filter.hasAction(Intent.ACTION_PACKAGE_ADDED) && filter.hasDataScheme("package"))
                receivers++
                return null
            }
        }
        val build = object : AndroidBuildClassifier() {
            override fun platformTargetSdk(): Int = 36
        }
        val config = CompatConfig(build, context)
        config.initConfigFromLib(File(Environment.getRootDirectory(), "etc/compatconfig"))
        val service = PlatformCompat(context, config, build,
            ChangeReporter(ChangeReporter.SOURCE_SYSTEM_SERVER))
        check(receivers == 1)
        val inventory = service.listAllChanges()
        val gate = inventory.single { it.name == "ENFORCE_EDGE_TO_EDGE" }
        val disabled = inventory.single { it.name == "CHANGE_ALWAYS_DISABLED" }
        check(gate.enableSinceTargetSdk == 35 && disabled.disabled)
        val app = ApplicationInfo().apply {
            packageName = "fixture.compat"; uid = android.os.Process.myUid(); targetSdkVersion = 34
        }
        check(!service.isChangeEnabled(gate.id, app))
        check(!service.isChangeEnabled(disabled.id, app))
        check(gate.id in service.getAppConfig(app).disabledChanges())
        app.targetSdkVersion = 35
        check(service.isChangeEnabled(gate.id, app))
        check(gate.id in service.getAppConfig(app).enabledChanges())
        check(service.isChangeEnabled(Long.MAX_VALUE, app))
        val native = PlatformCompatNative(service)
        native.reportChangeByUid(gate.id, app.uid)
        allowed = false
        check(runCatching { service.isChangeEnabled(gate.id, app) }.exceptionOrNull() is SecurityException)
        check(runCatching { service.listAllChanges() }.exceptionOrNull() is SecurityException)
        check(runCatching { native.reportChangeByUid(gate.id, app.uid) }.exceptionOrNull() is SecurityException)
    }

    private fun input(xml: String) = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
}
