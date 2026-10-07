package dev.artzig.system

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import dev.artzig.ArtzigTestDiagnostics
import org.json.JSONObject

/** Optional WM collaborators preserve negative results and fail visibly on absent backends. */
object StockOptionalWmServicesSmoke {
    private fun unsupported(owner: String, member: String, operation: () -> Unit) {
        val mark = ArtzigTestDiagnostics.issueSequence()
        check(runCatching(operation).exceptionOrNull() is UnsupportedOperationException)
        val issues = JSONObject(ArtzigTestDiagnostics.snapshot(Long.MAX_VALUE, mark)).getJSONArray("issues")
        check(issues.length() == 1)
        check(issues.getJSONObject(0).getString("owner") == owner)
        check(issues.getJSONObject(0).getString("member") == member)
    }

    fun run(context: Context, apk: String) {
        verifyBatteryUsage()
        var clock = 100L
        val pending = HostedPendingTopUids { clock }
        pending.add(10001, 10)
        clock = 200
        pending.add(10001, 10)
        pending.delete(10001, 99)
        check(pending.contains(10001))
        pending.delete(10001, 100)
        check(!pending.contains(10001)) // Duplicate adds preserve the first timestamp.
        pending.add(10001, 20)
        pending.died(10001, 10)
        check(pending.contains(10001))
        pending.died(10001, 20)
        check(!pending.contains(10001))
        verifyConfigurationBroadcasts()
        // Stock AppOps defaults and callbacks are exercised by StockAppOpsSystemSmoke.
        for (animationFirst in listOf(false, true)) {
            val activityManager = HostedActivityManagerService()
            var completions = 0
            if (animationFirst) activityManager.bootAnimationComplete()
            activityManager.finishBooting { completions++ }
            check(completions == if (animationFirst) 1 else 0)
            activityManager.bootAnimationComplete()
            activityManager.bootAnimationComplete()
            activityManager.finishBooting { error("Boot phases must not be repeated") }
            check(completions == 1)
        }
        val arguments = Bundle().apply { putString("fixture", "retained") }
        val token = Binder()
        check(!HostedAssistSessions(null).showFromActivity(arguments, "fixture-attribution", token))
        check(arguments.getString("fixture") == "retained")
        unsupported("AssistUtils", "showSessionForActiveService") {
            HostedAssistSessions(Binder()).showFromActivity(arguments, null, token)
        }

        // The session's PackageManagerService, with the fixture APK preinstalled.
        val packages = android.app.ActivityThread.getPackageManager()
        val packageName = checkNotNull(context.packageManager.getPackageArchiveInfo(apk, 0)).packageName
        check(packages.getApplicationInfo("android", 0L, 0)!!.uid == android.os.Process.SYSTEM_UID)
        val deniedCleanup = HostedActivityManagerService(context).apply { packageService = packages }
        check(runCatching { deniedCleanup.killAllBackgroundProcessesExcept(-1, -1) }.exceptionOrNull()
            is SecurityException)
        val uid = packages.getPackageUid(packageName, 0L, 0)
        check(uid >= android.os.Process.FIRST_APPLICATION_UID)
        verifyProviderReferenceRelease(packages, uid)
        // Stock PackageManagerService property queries over the parsed manifest.
        val applicationProperty = checkNotNull(packages.getPropertyAsUser("dev.artzig.fixture.property", packageName, null, 0))
        check(applicationProperty.isBoolean && applicationProperty.boolean)
        val serviceProperty = checkNotNull(packages.getPropertyAsUser("dev.artzig.fixture.property", packageName,
            "android.inputmethodservice.InputMethodService", 0))
        check(serviceProperty.isBoolean && !serviceProperty.boolean)
        check(packages.getPropertyAsUser("dev.artzig.fixture.integer", packageName, null, 0)!!.integer == 42)
        check(packages.getPropertyAsUser("fixture.absent", packageName, null, 0) == null)
        check(packages.queryProperty("dev.artzig.fixture.property", android.content.pm.PackageManager.TYPE_APPLICATION)
            .list.map { it as android.content.pm.PackageManager.Property }.any { it.packageName == packageName && it.boolean })
        val grammar = HostedGrammaticalInflectionService(HostedActivityManagerService(context),
            HostedPermissions(packages)).localService
        check(grammar.getSystemGrammaticalGender(0) == android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED)
        check(grammar.getSystemGrammaticalGender(10) == android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED)
        val developerGender = android.os.SystemProperties.getInt("persist.sys.grammatical_gender", 0)
        check(grammar.grammaticalGenderFromDeveloperSettings == developerGender)
        check(grammar.mergedFinalSystemGrammaticalGender() ==
            if (developerGender == android.content.res.Configuration.GRAMMATICAL_GENDER_UNDEFINED) 0 else developerGender)
        check(grammar.canGetSystemGrammaticalGender(android.os.Process.SYSTEM_UID))
        check(!grammar.canGetSystemGrammaticalGender(uid))
        unsupported("GrammaticalInflectionManagerInternal", "stageAndApplyRestoredPayload") {
            grammar.stageAndApplyRestoredPayload(byteArrayOf(), 0)
        }
        val launcher = HostedLauncherAppsService(context, packages, HostedPermissions(packages))
        fun shortcut(callerUid: Int, callerPackage: String) = launcher.startShortcut(
            callerUid, android.os.Process.myPid(), callerPackage, packageName, null,
            "fixture-shortcut", null, null, 0)
        val spoof = runCatching { shortcut(uid + 1, packageName) }.exceptionOrNull()
        check(spoof is SecurityException && spoof.message == "Calling package name mismatch")
        val spoofPlatform = runCatching { shortcut(uid, "android") }.exceptionOrNull()
        check(spoofPlatform is SecurityException && spoofPlatform.message == "Calling package name mismatch")
        val denied = runCatching { shortcut(uid, packageName) }.exceptionOrNull()
        check(denied is SecurityException && denied.message!!.contains("not the default launcher"))
    }

    private fun verifyBatteryUsage() {
        var time = 0L
        val usage = HostedActivityBatteryUsage(false) { time }
        usage.activity(10001, true)
        time = 100
        usage.powerChanged(true)
        time = 150
        usage.activity(10001, true)
        time = 200
        usage.activity(10001, false)
        usage.foregroundCpu("fixture", 10001, 7)
        usage.processCpu("fixture", 10001, 4, 2)
        usage.processCpu("fixture:cold", 10001, 3, 1)
        time = 250
        usage.powerChanged(false)
        time = 300
        usage.activity(10001, false)
        usage.activity(10001, false) // An unmatched pause must not make nesting negative.
        usage.foregroundCpu("fixture", 10001, 9)
        usage.processCpu("fixture", 10001, 100, 100) // Plugged-in CPU is excluded.
        val first = usage.snapshot().single()
        check(first.foregroundMs == 150L && first.foregroundCpuMs == mapOf("fixture" to 7L))
        check(first.processCpuMs == mapOf("fixture" to HostedActivityBatteryUsage.CpuTime(4, 2),
            "fixture:cold" to HostedActivityBatteryUsage.CpuTime(3, 1)))
        time = 400
        usage.activity(10001, true)
        time = 500
        usage.powerChanged(true)
        time = 520
        usage.activity(10002, true)
        time = 600
        usage.activity(10001, false)
        time = 650
        usage.activity(10002, false)
        val values = usage.snapshot().associateBy { it.uid }
        check(values.getValue(10001).foregroundMs == 250L)
        check(values.getValue(10002).foregroundMs == 130L)
        check(first.foregroundMs == 150L) // Returned snapshots are independent.
    }

    private fun verifyProviderReferenceRelease(packages: android.content.pm.IPackageManager, uid: Int) {
        val access = HostedUriAccess(packages) { error("No application caller in local lookup") }
        val uri = android.net.Uri.parse("content://fixture/permission")
        var fail = false
        val provider = object : android.content.IContentProvider by
                dev.artzig.system.unsupported<android.content.IContentProvider>() {
            override fun checkUriPermission(source: android.content.AttributionSource,
                requested: android.net.Uri?, requestedUid: Int, mode: Int): Int {
                check(source.uid == uid && requestedUid == uid && requested == uri)
                if (fail) throw android.os.RemoteException("provider died during permission check")
                return android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        val holder = android.app.ContentProviderHolder(android.content.pm.ProviderInfo()).apply {
            this.provider = provider
            connection = Binder()
        }
        var releases = 0
        fun query(callingUid: Int = uid): Int = access.checkProvider(uri, 0, callingUid,
            Intent.FLAG_GRANT_READ_URI_PERMISSION, release = {
                check(it === holder)
                releases++
            }) { _, _ -> holder }
        check(query() == android.content.pm.PackageManager.PERMISSION_GRANTED && releases == 1)
        fail = true
        check(query() == android.content.pm.PackageManager.PERMISSION_DENIED && releases == 2)
        check(query(98765) == android.content.pm.PackageManager.PERMISSION_DENIED && releases == 3)
        fail = false
        holder.noReleaseNeeded = true
        check(query() == android.content.pm.PackageManager.PERMISSION_GRANTED && releases == 3)
        check(access.checkProvider(uri, 0, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            release = { error("Missing provider has no reference") }) { _, _ -> null } ==
            android.content.pm.PackageManager.PERMISSION_DENIED)
    }

    private fun verifyConfigurationBroadcasts() {
        val activities = HostedActivityManagerService()
        val received = ArrayList<Intent>()
        val receiver = object : android.content.IIntentReceiver.Stub() {
            override fun performReceive(intent: Intent, resultCode: Int, data: String?, extras: Bundle?,
                ordered: Boolean, sticky: Boolean, sendingUser: Int) {
                check(!ordered && !sticky && sendingUser == 0)
                received.add(Intent(intent))
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_SPLIT_CONFIGURATION_CHANGED)
        }
        activities.registerReceiverWithFeature(null, "fixture", null, null, receiver, filter, null, 0,
            Context.RECEIVER_NOT_EXPORTED)
        try {
            activities.broadcastGlobalConfigurationChanged(android.content.pm.ActivityInfo.CONFIG_ORIENTATION, false, true)
            check(received.map { it.action } == listOf(Intent.ACTION_CONFIGURATION_CHANGED))
            check(received.single().flags == (Intent.FLAG_RECEIVER_REGISTERED_ONLY or
                Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_FOREGROUND or
                Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS))
            for ((initLocale, ready) in listOf(true to true, false to false, false to true)) {
                received.clear()
                activities.broadcastGlobalConfigurationChanged(android.content.pm.ActivityInfo.CONFIG_LOCALE,
                    initLocale, ready)
                // The fixture lacks INSTALL_PACKAGES, so the split broadcast must not reach it.
                check(received.map { it.action } == listOf(Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_LOCALE_CHANGED))
                check((received.last().flags and Intent.FLAG_RECEIVER_REGISTERED_ONLY != 0) == (initLocale || !ready))
            }
        } finally { activities.unregisterReceiver(receiver) }
    }
}
