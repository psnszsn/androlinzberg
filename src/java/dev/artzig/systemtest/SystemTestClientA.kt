@file:Suppress("DEPRECATION")

package dev.artzig.systemtest

import android.app.ActivityThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * system-test peer: package `dev.artzig.test.a`. Binds `EchoService` in
 * package `dev.artzig.test.b` through the session's activity manager and
 * verifies connection lifecycle, identity, and death. Exits 0 on success.
 */
internal object SystemTestClientA {
    private var failures = 0

    private fun check(condition: Boolean, what: String) {
        if (condition) {
            System.err.println("system-test-client-a: ok: $what")
        } else {
            failures++
            System.err.println("system-test-client-a: FAIL: $what")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        // Package identity comes from AZK_BINDER_PACKAGE (set by the harness);
        // the binder connection is established lazily on first use.
        val frameworkRes = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        // This process's context carries its own package identity: services
        // like the clipboard verify the calling package against the uid
        // (AppOpsService.checkPackage), so "android" would be a spoofed claim.
        dev.artzig.HostContexts.systemContext(frameworkRes, 640, 480, 120, 0)
        // Server-side install of this package's fixture APK, then attach and
        // bind (AOSP handleBindApplication: the process context is the
        // package's Application). The fixture requests WRITE_SECURE_SETTINGS;
        // the signature match with framework-res.apk (both signed by the
        // azk-platform test key) grants it at install.
        val fixtures = checkNotNull(System.getenv("AZK_FIXTURE_APKS")) { "AZK_FIXTURE_APKS is not set" }
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(installer.installPackage("$fixtures/dev.artzig.test.a.apk", null) == "dev.artzig.test.a")
        ActivityThread.attachApplicationHosted("dev.artzig.test.a")
        val appInfo = checkNotNull(ActivityThread.getPackageManager().getApplicationInfo("dev.artzig.test.a", 0L, 0))
        val context: android.content.Context =
            ActivityThread.currentActivityThread().`handleBindApplication$artzig`(appInfo, emptyList())
        thread(name = "system-test", isDaemon = true) {
            try {
                run(context)
            } catch (t: Throwable) {
                failures++
                System.err.println("system-test-client-a: FAIL: uncaught " + t)
                t.printStackTrace()
            }
            System.err.println("system-test-client-a: failures=$failures")
            Runtime.getRuntime().exit(if (failures == 0) 0 else 1)
        }
        Looper.loop()
    }

    private fun run(context: Context) {
        check(Process.myUid() != Process.SYSTEM_UID && Process.myUid() >= Process.FIRST_APPLICATION_UID, "app uid assigned")
        check(
            android.os.SystemProperties.get("sys.azk.boot_completed") == "1",
            "property set by the system process observed",
        )
        check(!android.os.Build.IS_EMULATOR, "IS_EMULATOR stays false outside the system process")
        var denied = false
        try {
            ServiceManager.addService("activity", android.os.Binder())
        } catch (e: SecurityException) {
            denied = true
        }
        check(denied, "an app may not register the activity manager's name")

        // Cross-process package manager: host B installs its fixture APK with
        // the session's PMS; poll for it (both peers start concurrently).
        val pm = context.packageManager
        var bInfo: android.content.pm.PackageInfo? = null
        val pmsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (bInfo == null && System.nanoTime() < pmsDeadline) {
            bInfo = try {
                pm.getPackageInfo("dev.artzig.test.b", 0)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Thread.sleep(100)
                null
            }
        }
        check(bInfo?.versionCode == 7 && bInfo?.versionName == "7.0", "cross-process getPackageInfo")
        val echoMatches = pm.queryIntentServices(Intent("dev.artzig.test.ECHO"), 0)
        check(
            echoMatches.size == 1 && echoMatches[0].serviceInfo.name == "dev.artzig.systemtest.EchoService",
            "cross-process queryIntentServices (real IntentFilter match)",
        )
        // Implicit resolution across packages through the real resolver: B's
        // fixture declares a MAIN/LAUNCHER activity.
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        check(
            launchers.any { it.activityInfo?.name == "dev.artzig.systemtest.BMainActivity" },
            "implicit cross-package LAUNCHER query",
        )
        val ipm = android.content.pm.IPackageManager.Stub.asInterface(
            ServiceManager.getService("package"),
        )
        val allPackages = ipm.allPackages
        check(
            allPackages.containsAll(listOf("android", "dev.artzig.test.a", "dev.artzig.test.b")),
            "getAllPackages lists the platform and both fixtures",
        )
        check(
            ipm.checkSignatures("dev.artzig.test.a", "dev.artzig.test.b", 0) ==
                android.content.pm.PackageManager.SIGNATURE_MATCH,
            "fixtures share the azk-platform signature",
        )
        check(
            ipm.getPackageUid("dev.artzig.test.a", 0, 0) == Process.myUid(),
            "package uid bound to the broker uid at attach",
        )
        // PropertyInvalidatedCache: an enabled-state mutation in the system
        // process bumps the cache_key.* nonce through the session property
        // area, so the client-side ApplicationInfo cache re-fetches.
        check(pm.getApplicationInfo("dev.artzig.test.a", 0).enabled, "own package starts enabled")
        pm.setApplicationEnabledSetting(
            "dev.artzig.test.a",
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0,
        )
        check(
            !pm.getApplicationInfo("dev.artzig.test.a", 0).enabled,
            "disabled state observed through the invalidated cache",
        )
        pm.setApplicationEnabledSetting(
            "dev.artzig.test.a",
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            0,
        )
        check(pm.getApplicationInfo("dev.artzig.test.a", 0).enabled, "re-enabled state observed")
        val enabledDenied = try {
            pm.setApplicationEnabledSetting(
                "dev.artzig.test.b",
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0,
            )
            false
        } catch (expected: SecurityException) {
            true
        }
        check(enabledDenied, "enabled-state mutation of another package denied")

        // Explicit-lookup enabled-state gate: a disabled component must not
        // resolve through getActivityInfo (PackageInfoUtils.generate* does not
        // evaluate enabled state; the service applies PackageUserStateUtils.isMatch).
        val toggle = ComponentName("dev.artzig.test.a", "dev.artzig.systemtest.AToggleActivity")
        check(pm.getActivityInfo(toggle, 0).enabled, "component resolvable while enabled")
        pm.setComponentEnabledSetting(
            toggle,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        val componentHidden = try {
            pm.getActivityInfo(toggle, 0)
            false
        } catch (expected: android.content.pm.PackageManager.NameNotFoundException) {
            true
        }
        check(componentHidden, "disabled component not returned by explicit lookup")
        // MATCH_DISABLED_COMPONENTS still finds it (AOSP escape hatch).
        check(
            pm.getActivityInfo(toggle, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
                .name == toggle.className,
            "disabled component visible with MATCH_DISABLED_COMPONENTS",
        )
        pm.setComponentEnabledSetting(
            toggle,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )

        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val bindingDied = CountDownLatch(1)
        var proxy: dev.artzig.binder.IBinderTestService? = null
        var connectedThread: Thread? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connectedThread = Thread.currentThread()
                proxy = dev.artzig.binder.IBinderTestService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                disconnected.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                bindingDied.countDown()
            }
        }
        val intent = Intent("dev.artzig.test.ECHO").setPackage("dev.artzig.test.b")
        intent.setData(android.net.Uri.parse("test://echo"))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE), "bindService accepted")
        check(connected.await(30, TimeUnit.SECONDS), "onServiceConnected delivered")
        check(connectedThread === Looper.getMainLooper().thread, "onServiceConnected ran on the main looper")

        val service = checkNotNull(proxy)
        check(service.add(20, 22) == 42, "cross-process service call")
        check(service.echo("héllo") == "héllo", "string round trip")
        val bundle = android.os.Bundle()
        bundle.putInt("i", 7)
        val back = service.roundTripBundle(bundle)
        check(back != null && back.getInt("i") == 7 && back.getString("server") == "seen", "bundle through a bound service")
        check(back?.getString("action") == "dev.artzig.test.ECHO", "bind Intent action reached onBind")
        check(back?.getString("data") == "test://echo", "bind Intent data reached onBind")
        check(service.callerUid() == Process.myUid(), "callee sees this package's uid")
        check(service.selfPid() != Process.myPid(), "service runs in another process")

        // --- android.os.Messenger (real class over IMessenger): cross-process send + reply.
        val messengerReply = CountDownLatch(1)
        var messengerWhat = -1
        var messengerArg = -1
        var messengerFrom: String? = null
        val replyTo = android.os.Messenger(android.os.Handler(Looper.getMainLooper()) { msg ->
            messengerWhat = msg.what
            messengerArg = msg.arg1
            messengerFrom = msg.data?.getString("from")
            messengerReply.countDown()
            true
        })
        val remoteMessenger = android.os.Messenger(ServiceManager.waitForService("dev.artzig.test.b.messenger"))
        val outbound = android.os.Message.obtain(null, 41, 21, 0)
        outbound.replyTo = replyTo
        remoteMessenger.send(outbound)
        check(messengerReply.await(30, TimeUnit.SECONDS), "Messenger reply delivered")
        check(messengerWhat == 42 && messengerArg == 42 && messengerFrom == "b", "Messenger reply carries what/arg1/data")
        check(android.os.UserHandle.myUserId() == 0, "single hosted user: myUserId is 0")
        check(android.os.UserHandle.getUserId(Process.myUid()) == 0, "broker app uid lies in user 0")
        check(android.os.UserHandle.isApp(Process.myUid()), "broker uid is an application uid")

        // --- broadcasts: cross-process delivery, ordered chain, sticky.
        val pinged = CountDownLatch(1)
        var pingSeq = -1
        var pingThread: Thread? = null
        val pingReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                pingSeq = received?.getIntExtra("seq", -1) ?: -1
                pingThread = Thread.currentThread()
                // Real BroadcastReceiver.PendingResult: registered, non-ordered, user 0.
                if (sendingUserId != 0 || isOrderedBroadcast || isInitialStickyBroadcast) pingSeq = -2
                pinged.countDown()
            }
        }
        context.registerReceiver(pingReceiver, android.content.IntentFilter("dev.artzig.test.PING"))
        service.noteSeq(42)
        check(pinged.await(30, TimeUnit.SECONDS), "cross-process broadcast delivered")
        check(pingSeq == 42, "broadcast extras intact, sending user 0, not ordered or sticky")
        check(pingThread === Looper.getMainLooper().thread, "broadcast delivered on the main looper")
        context.unregisterReceiver(pingReceiver)
        // noteSeq is oneway and also writes clip-42 in B; wait for that write
        // so it cannot land in the middle of the clipboard section below.
        var clipWaitMs = 0
        while (clipWaitMs < 30_000) {
            val fromB = context.getSystemService(android.content.ClipboardManager::class.java)
                ?.primaryClip?.getItemAt(0)?.text
            if (fromB == "clip-42") break
            Thread.sleep(50)
            clipWaitMs += 50
        }
        check(clipWaitMs < 30_000, "clipboard write paired with the broadcast observed")

        // Ordered: high priority first, result state chains to the lower one.
        val orderedDone = CountDownLatch(2)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        var chained: String? = null
        val high = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                order.add("high")
                check(isOrderedBroadcast, "high-priority receiver sees an ordered broadcast")
                setResultData("from-high")
                orderedDone.countDown()
            }
        }
        val low = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                order.add("low")
                chained = resultData
                orderedDone.countDown()
            }
        }
        val highFilter = android.content.IntentFilter("dev.artzig.test.ORDERED")
        highFilter.setPriority(10)
        context.registerReceiver(high, highFilter)
        val lowFilter = android.content.IntentFilter("dev.artzig.test.ORDERED")
        lowFilter.setPriority(1)
        context.registerReceiver(low, lowFilter)
        context.sendOrderedBroadcast(Intent("dev.artzig.test.ORDERED"), null)
        check(orderedDone.await(30, TimeUnit.SECONDS), "ordered broadcast reached both receivers")
        check(order == listOf("high", "low"), "ordered broadcast honors filter priority")
        check(chained == "from-high", "ordered result state chains between receivers")
        context.unregisterReceiver(high)
        context.unregisterReceiver(low)

        // Sticky: a later registration returns the retained intent.
        context.sendStickyBroadcast(Intent("dev.artzig.test.STICKY").putExtra("k", 5))
        val stickyBack = context.registerReceiver(null, android.content.IntentFilter("dev.artzig.test.STICKY"))
        check(stickyBack?.getIntExtra("k", -1) == 5, "sticky broadcast retained")

        // --- clipboard through the reused AOSP ClipboardService.
        val clipboard = checkNotNull(context.getSystemService(android.content.ClipboardManager::class.java))
        val clipChanged = CountDownLatch(1)
        clipboard.addPrimaryClipChangedListener { clipChanged.countDown() }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "from-a"))
        check(clipboard.hasPrimaryClip(), "clipboard set visible")
        val roundTrip = clipboard.primaryClip
        if (roundTrip?.getItemAt(0)?.text != "from-a") {
            System.err.println(
                "system-test-client-a: round trip diagnostic: clip=" + roundTrip +
                    " text=" + roundTrip?.getItemAt(0)?.text +
                    " label=" + roundTrip?.description?.label,
            )
        }
        check(roundTrip?.getItemAt(0)?.text == "from-a", "clipboard round trip")
        check(clipChanged.await(30, TimeUnit.SECONDS), "primary clip listener notified")
        val clipFromB = CountDownLatch(1)
        clipboard.addPrimaryClipChangedListener {
            if (clipboard.primaryClip?.getItemAt(0)?.text == "clip-43") clipFromB.countDown()
        }
        service.noteSeq(43)
        check(clipFromB.await(30, TimeUnit.SECONDS), "clipboard set by another process observed")

        // --- cross-process content provider (real CursorWindow transport).
        val providerUri = android.net.Uri.parse("content://dev.artzig.test.b.provider/rows")
        val resolver = context.contentResolver
        val cursor = resolver.query(providerUri, null, null, null, null)
        check(cursor != null, "cross-process provider query returned a cursor")
        val names = ArrayList<String>()
        var sum = 0L
        cursor!!.use {
            while (it.moveToNext()) {
                names.add(it.getString(it.getColumnIndexOrThrow("name")))
                sum += it.getLong(it.getColumnIndexOrThrow("value"))
            }
        }
        check(names == listOf("alpha", "beta") && sum == 30L, "provider rows arrive through a CursorWindow")
        check(
            resolver.getType(providerUri) == "vnd.android.cursor.dir/vnd.artzig.row",
            "cross-process provider getType",
        )
        val reply = resolver.call("dev.artzig.test.b.provider", "ping", "arg1", null)
        check(reply?.getString("echo") == "ping:arg1", "provider call round trip")
        val values = android.content.ContentValues()
        values.put("name", "gamma")
        check(
            resolver.insert(providerUri, values)?.toString() ==
                "content://dev.artzig.test.b.provider/rows/gamma",
            "cross-process provider insert",
        )
        val deniedProvider = try {
            resolver.query(android.net.Uri.parse("content://dev.artzig.test.b.secret/x"), null, null, null, null)
            false
        } catch (expected: SecurityException) {
            true
        }
        check(deniedProvider, "non-exported provider denied across uids")

        // --- the real provider transport (ContentProviderNative): batch, cancellation, uri permission.
        val remoteProvider = checkNotNull(resolver.acquireProvider("dev.artzig.test.b.provider"))
        val source = context.attributionSource
        val ops = arrayListOf(
            android.content.ContentProviderOperation.newInsert(providerUri).withValue("name", "delta").build(),
            android.content.ContentProviderOperation.newDelete(providerUri).build(),
        )
        val results = remoteProvider.applyBatch(source, "dev.artzig.test.b.provider", ops)
        check(
            results.size == 2 && results[0].uri.toString() == "content://dev.artzig.test.b.provider/rows/delta" && results[1].count == 1,
            "applyBatch crosses the process boundary with typed results",
        )
        val remoteSignal = checkNotNull(remoteProvider.createCancellationSignal())
        thread(name = "cancel-slow-query", isDaemon = true) {
            Thread.sleep(300)
            try { remoteSignal.cancel() } catch (ignored: android.os.RemoteException) {}
        }
        val cancelled = try {
            remoteProvider.query(source, android.net.Uri.parse("content://dev.artzig.test.b.provider/slow"), null, null, remoteSignal)
            false
        } catch (expected: android.os.OperationCanceledException) {
            true
        }
        check(cancelled, "a CancellationSignal cancels a query running in another process")
        check(
            remoteProvider.checkUriPermission(source, providerUri, Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_DENIED,
            "checkUriPermission reaches the provider (default denies)",
        )
        check(
            resolver.query(android.net.Uri.parse("content://no.such.authority/x"), null, null, null, null) == null,
            "unknown authority yields null",
        )

        // --- real ContentResolver surface: ContentProviderClient and persisted URI grants.
        val client = checkNotNull(resolver.acquireContentProviderClient("dev.artzig.test.b.provider"))
        client.use { c ->
            val clientCursor = checkNotNull(c.query(providerUri, null, null, null, null))
            check(clientCursor.count >= 2, "ContentProviderClient.query rides the acquired provider")
            clientCursor.close()
            check(c.getType(providerUri) == "vnd.android.cursor.dir/vnd.artzig.row", "ContentProviderClient.getType")
            check(c.localContentProvider == null, "remote provider has no local ContentProvider")
        }
        val unstable = checkNotNull(resolver.acquireUnstableContentProviderClient(providerUri))
        check(unstable.call("ping", "u", null)?.getString("echo") == "ping:u", "unstable client call")
        unstable.release()
        check(resolver.persistedUriPermissions.isEmpty(), "no persisted grants initially")
        resolver.takePersistableUriPermission(providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val persisted = resolver.persistedUriPermissions
        check(
            persisted.size == 1 && persisted[0].uri == providerUri && persisted[0].isReadPermission && !persisted[0].isWritePermission,
            "takePersistableUriPermission recorded through IUriGrantsManager: " + persisted,
        )
        resolver.releasePersistableUriPermission(providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(resolver.persistedUriPermissions.isEmpty(), "releasePersistableUriPermission clears the grant")

        // --- settings through the verbatim AOSP client + hosted SettingsProvider.
        // The fixture requests WRITE_SECURE_SETTINGS (granted at install via
        // the platform-signature match) but not WRITE_SETTINGS, so the system
        // table stays gated while secure/global writes are allowed.
        val systemTableDenied = try {
            android.provider.Settings.System.putString(resolver, "azk_test_setting", "nope")
            false
        } catch (expected: SecurityException) {
            true
        }
        check(systemTableDenied, "system settings write denied without WRITE_SETTINGS")
        val settingChanged = CountDownLatch(1)
        val observer = object : android.database.ContentObserver(android.os.Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                if (uri?.lastPathSegment == "azk_test_setting") settingChanged.countDown()
            }
        }
        resolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor("azk_test_setting"),
            false,
            observer,
        )
        check(
            android.provider.Settings.Secure.putString(resolver, "azk_test_setting", "42"),
            "secure settings write accepted with the permission",
        )
        check(
            android.provider.Settings.Secure.getString(resolver, "azk_test_setting") == "42",
            "secure settings round trip through the session provider",
        )
        check(
            android.provider.Settings.Secure.getInt(resolver, "azk_test_setting", 0) == 42,
            "secure settings typed read",
        )
        check(settingChanged.await(30, TimeUnit.SECONDS), "ContentObserver fired across processes")
        resolver.unregisterContentObserver(observer)
        check(
            android.provider.Settings.Global.getString(resolver, "azk_test_setting") == null,
            "tables are distinct",
        )

        // --- identity/policy trio on the real AIDL surfaces.
        val userManager = android.os.IUserManager.Stub.asInterface(
            ServiceManager.getService(Context.USER_SERVICE),
        )
        check(userManager != null, "user manager published session-wide")
        check(userManager.getUsers(false, false, false)?.size == 1, "single hosted user")
        check(userManager.isUserRunning(0) && userManager.isUserUnlocked(0), "system user running and unlocked")
        check(userManager.getUserInfo(0)?.id == 0, "user info for user 0")
        val appOps = com.android.internal.app.IAppOpsService.Stub.asInterface(
            ServiceManager.getService("appops"),
        )
        check(appOps != null, "app-ops service published session-wide")
        check(
            appOps.checkOperation(29, Process.myUid(), "dev.artzig.test.a") ==
                android.app.AppOpsManager.MODE_ALLOWED,
            "app-ops default to allowed",
        )
        check(
            appOps.checkPackage(Process.myUid(), "dev.artzig.test.b") ==
                android.app.AppOpsManager.MODE_ERRORED,
            "app-ops rejects a spoofed package claim",
        )
        val opsDenied = try {
            appOps.setMode(29, Process.myUid(), "dev.artzig.test.a", android.app.AppOpsManager.MODE_ERRORED)
            false
        } catch (expected: SecurityException) {
            true
        }
        check(opsDenied, "app-op modes are system-only")
        val grantDenied = try {
            ipm.grantRuntimePermission("dev.artzig.test.a", "android.permission.CAMERA", 0)
            false
        } catch (expected: SecurityException) {
            true
        }
        check(grantDenied, "runtime permission grants are system-only")

        // Unbind: the last connection triggers onUnbind in the host; an unbound
        // connection receives no further callbacks.
        context.unbindService(connection)
        Thread.sleep(300)
        check(disconnected.count == 1L && bindingDied.count == 1L, "unbound connection stays silent")
        val reconnected = CountDownLatch(1)
        val disconnected2 = CountDownLatch(1)
        val died2 = CountDownLatch(1)
        val connection2 = object : ServiceConnection {
            var again: dev.artzig.binder.IBinderTestService? = null
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                again = dev.artzig.binder.IBinderTestService.Stub.asInterface(service)
                reconnected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { disconnected2.countDown() }
            override fun onBindingDied(name: ComponentName?) { died2.countDown() }
        }
        check(context.bindService(intent, connection2, Context.BIND_AUTO_CREATE), "rebind accepted")
        check(reconnected.await(30, TimeUnit.SECONDS), "rebind connected")

        // Ask the service host to exit: the live connection observes the death.
        connection2.again?.exit()
        check(disconnected2.await(30, TimeUnit.SECONDS), "onServiceDisconnected on host death")
        check(died2.await(30, TimeUnit.SECONDS), "onBindingDied on host death")
        var dead = false
        try {
            connection2.again?.add(1, 1)
        } catch (e: android.os.DeadObjectException) {
            dead = true
        }
        check(dead, "calls after host death throw DeadObjectException")
        // The provider record died with its host; re-acquisition finds no
        // process and (with no launcher in this harness) yields null.
        check(
            resolver.query(providerUri, null, null, null, null) == null,
            "provider gone after host death",
        )
    }
}
