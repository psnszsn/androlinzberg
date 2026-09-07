package dev.artzig.system

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.android.server.pm.PackageArchiver
import com.android.server.pm.pkg.ArchiveState
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
        val appOps = HostedAppOpsService()
        for (op in listOf(android.app.AppOpsManager.OP_CAMERA, android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                android.app.AppOpsManager.OP_WRITE_SETTINGS, android.app.AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION)) {
            check(appOps.checkOperation(op, 10001, "fixture") == android.app.AppOpsManager.opToDefaultMode(op))
        }
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

        // As in the other in-process package fixtures, this anonymous test peer
        // does not own the system_server cache-property namespace.
        android.app.PropertyInvalidatedCache.disableForTestMode()
        val packageBroadcasts = java.util.Collections.synchronizedList(ArrayList<Intent>())
        val packages = HostedPackageManagerService(packageBroadcast = { intent, user ->
            check(user == 0)
            packageBroadcasts.add(Intent(intent))
        }).apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val packageName = verifyPackageObservers(packages, apk)
        val permissionPolicy = HostedPermissionPolicy(context, packages)
        check(!permissionPolicy.isInitialized(0))
        var initialized = 0
        permissionPolicy.setOnInitializedCallback { user ->
            check(user == 0 && permissionPolicy.isInitialized(user))
            initialized++
        }
        permissionPolicy.startUser(0)
        permissionPolicy.startUser(0)
        check(initialized == 1 && !permissionPolicy.isInitialized(10))
        permissionPolicy.setOnInitializedCallback { error("Initialization is not replayed") }
        check(permissionPolicy.checkStartActivity(Intent(Intent.ACTION_MAIN), 10001, packageName))
        val removed = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
        check(!permissionPolicy.checkStartActivity(removed, 10001, packageName))
        check(!removed.hasExtra(Intent.EXTRA_CALLING_PACKAGE))
        val unknown = Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        check(permissionPolicy.checkStartActivity(unknown, 10001, "fixture.absent"))
        check(unknown.getStringExtra(Intent.EXTRA_CALLING_PACKAGE) == "fixture.absent")
        val other = Intent(android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER)
        check(!permissionPolicy.checkStartActivity(other, 10001, packageName))
        check(!permissionPolicy.checkStartActivity(other, android.os.Process.SYSTEM_UID, packageName))
        check(permissionPolicy.checkStartActivity(other, android.os.Process.SYSTEM_UID, "android"))
        check(!permissionPolicy.isIntentToPermissionDialog(other))
        check(permissionPolicy.isIntentToPermissionDialog(other.setPackage(packages.permissionControllerPackageName)))
        permissionPolicy.showNotificationPromptIfNeeded(packageName, 0, -1)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val taskInfo = android.app.ActivityManager.RunningTaskInfo().apply {
            isFocused = true; isVisible = true; isRunning = true
            baseIntent = launch
            baseActivity = ComponentName(packageName, "FixtureActivity")
            topActivityInfo = android.content.pm.ActivityInfo().apply { name = "FixtureActivity" }
            numActivities = 1
        }
        check(permissionPolicy.shouldShowNotificationDialogForTask(taskInfo, packageName, packageName, launch, "FixtureActivity"))
        taskInfo.isVisible = false
        check(!permissionPolicy.shouldShowNotificationDialogForTask(taskInfo, packageName, packageName, launch, "FixtureActivity"))
        taskInfo.isVisible = true
        taskInfo.baseIntent = Intent("fixture.trampoline")
        check(permissionPolicy.shouldShowNotificationDialogForTask(taskInfo, packageName, packageName, taskInfo.baseIntent, "FixtureActivity"))
        check(!permissionPolicy.shouldShowNotificationDialogForTask(taskInfo, packageName, "other", taskInfo.baseIntent, "FixtureActivity"))
        val deniedCleanup = HostedActivityManagerService(context).apply { packageService = packages }
        check(runCatching { deniedCleanup.killAllBackgroundProcessesExcept(-1, -1) }.exceptionOrNull()
            is SecurityException)
        val uid = packages.getPackageUid(packageName, 0L, 0)
        check(uid >= android.os.Process.FIRST_APPLICATION_UID)
        verifyProviderReferenceRelease(packages, uid)
        val state = packages.getPackageStateInternal(packageName) as HostedPackageState
        packages.setPackageStoppedState(packageName, true, 0)
        state.userState.setNotLaunched(true)
        check(packages.getApplicationInfo(packageName, 0L, 0)!!.flags and
            android.content.pm.ApplicationInfo.FLAG_STOPPED != 0)
        packages.notifyComponentUsed(packageName, 0, "fixture.caller", "resume")
        check(!state.userState.isStopped && !state.userState.isNotLaunched)
        packages.notifyComponentUsed(packageName, 0, "fixture.caller", "repeat")
        packages.notifyComponentUsed("fixture.absent", 0, null, "absent")
        check(runCatching { packages.setPackageStoppedState("android", true, 0) }.exceptionOrNull() is SecurityException)
        packages.setPackageStoppedState(packageName, true, 10)
        check(!state.userState.isStopped)
        val broadcastsDone = java.util.concurrent.CountDownLatch(1)
        com.android.internal.os.BackgroundThread.getHandler().post { broadcastsDone.countDown() }
        check(broadcastsDone.await(2, java.util.concurrent.TimeUnit.SECONDS))
        check(packageBroadcasts.size == if (android.content.pm.Flags.stayStopped()) 1 else 0)
        packageBroadcasts.firstOrNull()?.let {
            check(it.action == Intent.ACTION_PACKAGE_UNSTOPPED)
            check(it.data?.schemeSpecificPart == packageName)
            check(it.getIntExtra(Intent.EXTRA_UID, -1) == uid)
            check(it.getIntExtra(Intent.EXTRA_USER_HANDLE, -1) == 0)
            check(it.flags and Intent.FLAG_RECEIVER_REGISTERED_ONLY != 0)
        }
        val applicationProperty = checkNotNull(packages.getPropertyAsUser("dev.artzig.fixture.property", packageName, null, 0))
        check(applicationProperty.isBoolean && applicationProperty.boolean)
        val serviceProperty = checkNotNull(packages.getPropertyAsUser("dev.artzig.fixture.property", packageName,
            "android.inputmethodservice.InputMethodService", 0))
        check(serviceProperty.isBoolean && !serviceProperty.boolean)
        check(packages.getPropertyAsUser("dev.artzig.fixture.integer", packageName, null, 0)!!.integer == 42)
        check(packages.getPropertyAsUser("dev.artzig.fixture.resource", packageName,
            "android.inputmethodservice.InputMethodService", 0)!!.resourceId != 0)
        check(packages.getPropertyAsUser("fixture.absent", packageName, null, 0) == null)
        check(packages.getPropertyAsUser("dev.artzig.fixture.integer", packageName,
            "android.inputmethodservice.InputMethodService", 0) == null)
        check(packages.queryProperty("dev.artzig.fixture.property", android.content.pm.PackageManager.TYPE_APPLICATION).list.single().boolean)
        check(!packages.queryProperty("dev.artzig.fixture.property", android.content.pm.PackageManager.TYPE_SERVICE).list.single().boolean)
        check(packages.queryProperty("dev.artzig.fixture.property", -1).list.isEmpty())
        check(runCatching { packages.getPropertyAsUser(null, packageName, null, 0) }.exceptionOrNull() is NullPointerException)
        check(runCatching { packages.getPropertyAsUser("fixture", packageName, null, 10) }.exceptionOrNull() is SecurityException)
        state.userState.setInstalled(false)
        check(packages.getPropertyAsUser("dev.artzig.fixture.property", packageName, null, 0) == null)
        check(packages.queryProperty("dev.artzig.fixture.property", android.content.pm.PackageManager.TYPE_APPLICATION).list.isEmpty())
        state.userState.setInstalled(true)
        check(packages.getUserMinAspectRatio(packageName, 0) == android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET)
        check(packages.getUserMinAspectRatio("fixture.absent", 0) == android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET)
        check(packages.getUserMinAspectRatio(packageName, 10) == android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET)
        check(runCatching { packages.setUserMinAspectRatio(packageName, 0, android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9) }
            .exceptionOrNull() is SecurityException)
        // In-memory package authority supplies a caller granted INSTALL_PACKAGES,
        // exercising the setter through its Android permission/ownership contract.
        check(state.grantedPermissions.add(android.Manifest.permission.INSTALL_PACKAGES))
        try {
            packages.setUserMinAspectRatio(packageName, 0, android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9)
            check(packages.getUserMinAspectRatio(packageName, 0) == android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9)
            packages.setUserMinAspectRatio(packageName, 0, android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET)
            check(runCatching { packages.setUserMinAspectRatio("android", 0, 0) }.exceptionOrNull() is SecurityException)
            check(runCatching { packages.setUserMinAspectRatio(packageName, 10, 0) }.exceptionOrNull() is SecurityException)
        } finally { state.grantedPermissions.remove(android.Manifest.permission.INSTALL_PACKAGES) }
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
        val component = ComponentName(packageName, "$packageName.ArchivedActivity")
        val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
        fun result(value: Intent = intent, userId: Int = 0) =
            HostedPackageArchiving.resolveMissingActivity(packages, value, userId)
        check(!PackageArchiver.isArchived(state.userState))
        check(result() == ActivityManager.START_CLASS_NOT_FOUND)
        state.userState.setInstalled(false)
        check(!PackageArchiver.isArchived(state.userState))
        state.userState.setArchiveState(ArchiveState(
            listOf(ArchiveState.ArchiveActivityInfo("Fixture", component, null, null)), "Fixture installer", 1L))
        check(PackageArchiver.isArchived(state.userState))
        check(result(Intent(Intent.ACTION_MAIN)) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(Intent(intent).setClassName(packageName, "$packageName.OtherActivity")) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(Intent(intent).setPackage("missing.fixture")) == ActivityManager.START_CLASS_NOT_FOUND)
        check(result(userId = 10) == ActivityManager.START_CLASS_NOT_FOUND)
        unsupported("PackageArchiver", "requestUnarchiveOnActivityStart") { result() }
        state.userState.setInstalled(true)
        check(!PackageArchiver.isArchived(state.userState))
        check(result() == ActivityManager.START_CLASS_NOT_FOUND)

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
        time = 250
        usage.powerChanged(false)
        time = 300
        usage.activity(10001, false)
        usage.activity(10001, false) // An unmatched pause must not make nesting negative.
        usage.foregroundCpu("fixture", 10001, 9)
        val first = usage.snapshot().single()
        check(first.foregroundMs == 150L && first.foregroundCpuMs == mapOf("fixture" to 7L))
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

    private fun verifyProviderReferenceRelease(packages: HostedPackageManagerService, uid: Int) {
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

    private fun verifyPackageObservers(packages: HostedPackageManagerService, apk: String): String {
        val serviceClass = android.content.pm.PackageManagerInternal::class.java
        val previous = com.android.server.LocalServices.getService(serviceClass)
        val internal = Class.forName("android.content.pm.HostedPackageManagerInternal")
            .getConstructor(HostedPackageManagerService::class.java).newInstance(packages)
            as android.content.pm.PackageManagerInternal
        com.android.server.LocalServices.removeServiceForTest(serviceClass)
        com.android.server.LocalServices.addService(serviceClass, internal)
        val events = ArrayList<String>()
        val snapshot = internal.getPackageList(object : android.content.pm.PackageManagerInternal.PackageListObserver {
            override fun onPackageAdded(name: String, uid: Int) {
                check(packages.getPackageUid(name, 0L, 0) == uid)
                events.add("added:$name")
            }
            override fun onPackageChanged(name: String, uid: Int) { events.add("changed:$name") }
            override fun onPackageRemoved(name: String, uid: Int) { error("Unexpected removal") }
        })
        try {
            check(snapshot.packageNames == listOf("android"))
            val name = packages.installPackage(apk)
            check(!internal.isInstantApp(name, 0))
            check(!internal.isInstantAppInstallerComponent(HostedPermissionController.component))
            check(!internal.isPermissionsReviewRequired(name, 0))
            check(internal.getDefaultHomeActivity(0) == null)
            check(internal.getDefaultHomeActivity(10) == null)
            val state = checkNotNull(packages.getPackageStateInternal(name))
            check(state.lastPackageUsageTime.all { it == 0L })
            val beforeUse = System.currentTimeMillis()
            internal.notifyPackageUse(name, android.content.pm.PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY)
            check(state.lastPackageUsageTime[android.content.pm.PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY] >= beforeUse)
            val used = state.lastPackageUsageTime.clone()
            internal.notifyPackageUse(name, -1)
            internal.notifyPackageUse(name, android.content.pm.PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT)
            internal.notifyPackageUse("fixture.missing", 0)
            check(state.lastPackageUsageTime.contentEquals(used))
            val explicit = Intent().setComponent(HostedPermissionController.component)
            check(internal.resolveIntent(explicit, null, 0L, 0L, 0, true,
                android.os.Binder.getCallingUid(), android.os.Process.myPid())?.activityInfo?.name ==
                HostedPermissionController.component.className)
            check(internal.resolveIntent(Intent("fixture.absent.action"), null, 0L, 0L, 0, true,
                android.os.Process.SYSTEM_UID, android.os.Process.myPid()) == null)
            check(internal.resolveIntent(explicit, null, 0L, 0L, 10, true,
                android.os.Process.SYSTEM_UID, android.os.Process.myPid()) == null)
            check(events == listOf("added:$name"))
            check(snapshot.packageNames == listOf("android"))
            internal.getPackageList(null).use { check(it.packageNames.toSet() == setOf("android", name)) }
            check(packages.installPackage(apk) == name)
            check(events.size == 1)
            packages.setApplicationEnabledSetting(name, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP, 0, name)
            check(events == listOf("added:$name", "changed:$name"))
            snapshot.close()
            snapshot.close()
            packages.setApplicationEnabledSetting(name, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP, 0, name)
            check(events.size == 2)
            return name
        } finally {
            snapshot.close()
            com.android.server.LocalServices.removeServiceForTest(serviceClass)
            if (previous != null) com.android.server.LocalServices.addService(serviceClass, previous)
        }
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
