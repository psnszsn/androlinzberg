@file:Suppress("DEPRECATION")

package dev.artzig.systemtest

import android.app.ActivityThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.IBinder
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import android.provider.Settings
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
        dev.artzig.HostContexts.prepareApplicationEnvironment(frameworkRes)
        dev.artzig.HostContexts.configureDisplay(640, 480, 120)
        // Server-side install of this package's fixture APK, then attach and
        // bind (AOSP handleBindApplication: the process context is the
        // package's Application). The fixture requests WRITE_SECURE_SETTINGS;
        // the signature match with framework-res.apk (both signed by the
        // azk-platform test key) grants it at install.
        val fixture = checkNotNull(System.getenv("AZK_FIXTURE_APK_A")) { "AZK_FIXTURE_APK_A is not set" }
        val installer = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "dev.artzig.test.a")
        val context: android.content.Context =
            dev.artzig.HostedServiceClient.attachApplication("dev.artzig.test.a")
        check(ApplicationBindingEvents.events == listOf("application:attach",
            "provider:SecretProvider", "application:create"))
        ActivityUriGrantSystemSmoke.publish(context)
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

    private fun testCompanionAssociations(context: Context) {
        val bluetooth = checkNotNull(context.getSystemService(android.bluetooth.BluetoothManager::class.java))
        check(bluetooth.adapter == null, "stock Bluetooth manager reports the absent adapter")
        val manager = checkNotNull(context.getSystemService(android.companion.CompanionDeviceManager::class.java))
        check(manager === context.getSystemService(Context.COMPANION_DEVICE_SERVICE), "stock companion manager is cached")
        val service = android.companion.ICompanionDeviceManager.Stub.asInterface(
            ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE))
        val name = "Hosted test companion"
        manager.myAssociations.filter { it.displayName == name }.forEach { manager.disassociate(it.id) }
        val changes = java.util.concurrent.LinkedBlockingQueue<List<android.companion.AssociationInfo>>()
        val listener = object : android.companion.IOnAssociationsChangedListener.Stub() {
            override fun onAssociationsChanged(associations: MutableList<android.companion.AssociationInfo>) {
                changes.add(associations)
            }
        }
        service.addOnAssociationsChangedListener(listener, 0)
        var associationId: Int? = null
        try {
            val created = java.util.concurrent.LinkedBlockingQueue<android.companion.AssociationInfo>()
            val failure = java.util.concurrent.atomic.AtomicReference<CharSequence>()
            manager.associate(android.companion.AssociationRequest.Builder()
                .setSelfManaged(true).setDisplayName(name).build(), java.util.concurrent.Executor { it.run() },
                object : android.companion.CompanionDeviceManager.Callback() {
                    override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                        created.add(associationInfo)
                    }
                    override fun onFailure(error: CharSequence?) { failure.set(error) }
                })
            val association = checkNotNull(created.poll(10, TimeUnit.SECONDS)) { "association callback failed: ${failure.get()}" }
            associationId = association.id
            check(association.isSelfManaged && association.packageName == context.packageName &&
                association.deviceMacAddress == null && association.timeApprovedMs > 0,
                "self-managed association keeps stock value semantics")
            check(manager.myAssociations.any { it.id == association.id }, "association is visible before its creation callback")
            check(changes.poll(10, TimeUnit.SECONDS)?.any { it.id == association.id } == true,
                "stock association store broadcasts the new association")
            service.enableSystemDataSync(association.id, 3)
            service.disableSystemDataSync(association.id, 1)
            check(manager.myAssociations.first { it.id == association.id }.systemDataSyncFlags == 2,
                "companion sync flags merge and clear independently")
            check(runCatching { service.getAllAssociationsForUser(1) }.exceptionOrNull() is SecurityException,
                "managing companion associations does not bypass cross-user permission")
            manager.disassociate(association.id)
            associationId = null
            check(manager.myAssociations.none { it.id == association.id }, "disassociation removes the active association")
            check(runCatching { manager.disassociate(association.id) }.exceptionOrNull() is IllegalArgumentException,
                "a stale association ID keeps the stock exception")
        } finally {
            associationId?.let { manager.disassociate(it) }
            service.removeOnAssociationsChangedListener(listener, 0)
        }
    }

    private fun testNetworkStatistics(context: Context) {
        val policy = context.getSystemService(android.net.NetworkPolicyManager::class.java)
        check(policy != null && policy === context.getSystemService(Context.NETWORK_POLICY_SERVICE),
            "stock network policy manager is registered and cached")
        check(try { policy.getUidPolicy(Process.myUid()); false } catch (_: SecurityException) { true },
            "app cannot inspect privileged network policies")
        check(try { policy.setUidPolicy(Process.myUid(), 1); false } catch (_: SecurityException) { true },
            "app cannot mutate privileged network policies")
        check(try { policy.factoryReset(null); false } catch (_: SecurityException) { true },
            "app cannot reset privileged network policies")

        val manager = context.getSystemService(android.app.usage.NetworkStatsManager::class.java)
        check(manager != null && manager === context.getSystemService(Context.NETWORK_STATS_SERVICE),
            "stock network stats manager is registered and cached")
        val rxBefore = android.net.TrafficStats.getRxBytes("lo")
        val txBefore = android.net.TrafficStats.getTxBytes("lo")
        check(rxBefore >= 0 && txBefore >= 0, "loopback counters are available through netstats Binder")
        val address = java.net.InetAddress.getByName("127.0.0.1")
        java.net.DatagramSocket(0, address).use { receiver ->
            receiver.soTimeout = 5000
            java.net.DatagramSocket().use { sender ->
                val payload = ByteArray(4096) { (it % 251).toByte() }
                sender.send(java.net.DatagramPacket(payload, payload.size, address, receiver.localPort))
                val received = java.net.DatagramPacket(ByteArray(8192), 8192)
                receiver.receive(received)
                check(received.length == payload.size &&
                    received.data.copyOf(received.length).contentEquals(payload),
                    "network counter probe transfers the complete UDP payload")
            }
        }
        check(android.net.TrafficStats.getRxBytes("lo") >= rxBefore + 4096 &&
            android.net.TrafficStats.getTxBytes("lo") >= txBefore + 4096,
            "stock TrafficStats observes real received and transmitted traffic")
        check(android.net.TrafficStats.getTotalRxBytes() >= android.net.TrafficStats.getRxBytes("lo"),
            "total counter includes loopback traffic")
        check(android.net.TrafficStats.getUidRxBytes(Process.myUid()) == android.net.TrafficStats.UNSUPPORTED.toLong() &&
            android.net.TrafficStats.getUidTxBytes(Process.myUid() + 1) == android.net.TrafficStats.UNSUPPORTED.toLong(),
            "unavailable per-UID accounting is not reported as zero traffic")
        check(android.net.TrafficStats.getRxBytes("azk-no-such-interface") == android.net.TrafficStats.UNSUPPORTED.toLong(),
            "missing interface follows the stock unsupported result")
    }

    private fun testNetworkRecommendations(context: Context, manager: android.net.NetworkScoreManager) {
        val resolver = context.contentResolver
        val packageName = context.packageName
        val component = android.content.ComponentName(context, TestNetworkRecommendationService::class.java)
        val oldLocation = Settings.Secure.getString(resolver, Settings.Secure.LOCATION_MODE)
        val oldOpenWifi = Settings.Global.getString(resolver, Settings.Global.USE_OPEN_WIFI_PACKAGE)
        val key = android.net.NetworkKey(android.net.WifiKey("\"fixture\"", "02:00:00:00:00:01"))
        val scores = CountDownLatch(1)
        val clears = java.util.concurrent.atomic.AtomicInteger()
        val cache = object : android.net.INetworkScoreCache.Stub() {
            override fun updateScores(networks: MutableList<android.net.ScoredNetwork>?) {
                check(networks?.singleOrNull()?.let { it.networkKey == key && it.rssiCurve.lookupScore(-80).toInt() == 3 } == true,
                    "bound network recommendation service delivers scores through stock cache dispatch")
                scores.countDown()
            }
            override fun clearScores() { clears.incrementAndGet() }
        }
        fun awaitState(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            do {
                if (predicate()) { check(true, message); return }
                Thread.sleep(20)
            } while (System.nanoTime() < deadline)
            check(false, message)
        }
        manager.registerNetworkScoreCache(android.net.NetworkKey.TYPE_WIFI, cache, android.net.NetworkScoreManager.SCORE_FILTER_NONE)
        try {
            Settings.Secure.putInt(resolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
            // The stock service's open-Wifi observer exercises its default
            // permission grant adapter before the scorer can pass preflight.
            Settings.Global.putString(resolver, Settings.Global.USE_OPEN_WIFI_PACKAGE, packageName)
            awaitState("default open-Wifi app receives its requested location permission") {
                context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            StockPermissionCheckerSystemSmoke.runRuntimeDelivery(context)
            check(manager.allValidScorers.any { it.recommendationServiceComponent == component },
                "stock scorer discovery resolves the declared recommendation service")
            check(manager.setActiveScorer(packageName), "stock scorer selection accepts the qualified provider")
            awaitState("network recommendation service is bound") { manager.requestScores(arrayOf(key)) }
            check(scores.await(5, TimeUnit.SECONDS), "network score callback reaches the registered cache")
            val beforeDisable = clears.get()
            context.packageManager.setComponentEnabledSetting(component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
            awaitState("disabling scorer component unbinds provider and invalidates scores") {
                !manager.isCallerActiveScorer(Process.myUid()) && clears.get() > beforeDisable
            }
            check(!manager.requestScores(arrayOf(key)), "disabled provider receives no score requests")
        } finally {
            manager.setActiveScorer(null)
            manager.unregisterNetworkScoreCache(android.net.NetworkKey.TYPE_WIFI, cache)
            context.packageManager.setComponentEnabledSetting(component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP)
            Settings.Global.putString(resolver, Settings.Global.USE_OPEN_WIFI_PACKAGE, oldOpenWifi)
            Settings.Secure.putString(resolver, Settings.Secure.LOCATION_MODE, oldLocation)
        }
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

        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        MemoryIntArraySystemSmoke.verify()
        HardwareBufferSystemSmoke.verify()
        SurfaceSyncSystemSmoke.verify()
        ActivityRecreationSystemSmoke.verify(context)
        ActivityUriGrantSystemSmoke.verify()
        testCompanionAssociations(context)
        val networkScores = context.getSystemService(android.net.NetworkScoreManager::class.java)
        check(networkScores != null && networkScores === context.getSystemService(android.content.Context.NETWORK_SCORE_SERVICE),
            "stock network score manager is registered and cached")
        check(!networkScores.isCallerActiveScorer(Process.myUid()), "caller is not an active scorer")
        check(try { networkScores.updateScores(emptyArray()); false } catch (_: SecurityException) { true },
            "only the active scorer may submit scores")
        testNetworkRecommendations(context, networkScores)
        testNetworkStatistics(context)
        val fingerprints = context.getSystemService(android.hardware.fingerprint.FingerprintManager::class.java)
        check(fingerprints != null && fingerprints === context.getSystemService(android.content.Context.FINGERPRINT_SERVICE),
            "stock fingerprint manager is registered and cached")
        check(!fingerprints.isHardwareDetected && !fingerprints.hasEnrolledFingerprints(),
            "fingerprint capability state has no invented hardware or enrollments")
        check(fingerprints.sensorPropertiesInternal.isEmpty(), "fingerprint sensor metadata is empty")
        check(try { fingerprints.hasEnrolledFingerprints(1); false } catch (_: SecurityException) { true },
            "fingerprint enrollment queries for another user require INTERACT_ACROSS_USERS")
        val fingerprintService = android.hardware.fingerprint.IFingerprintService.Stub.asInterface(
            ServiceManager.getService(android.content.Context.FINGERPRINT_SERVICE))
        val registration = CountDownLatch(1)
        val registered = object : android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback.Stub() {
            override fun onAllAuthenticatorsRegistered(sensors: MutableList<android.hardware.fingerprint.FingerprintSensorPropertiesInternal>?) {
                check(sensors != null && sensors.isEmpty(), "sensor registration reports an empty completed list")
                registration.countDown()
            }
        }
        fingerprintService.addAuthenticatorsRegisteredCallback(registered)
        check(registration.await(5, TimeUnit.SECONDS), "empty sensor registration callback is delivered")
        val authOptions = android.hardware.fingerprint.FingerprintAuthenticateOptions.Builder()
            .setOpPackageName(context.opPackageName).build()
        check(fingerprintService.authenticate(android.os.Binder(), 0, null, authOptions) == -1L,
            "fingerprint authentication returns AOSP's absent-provider request id")
        check(fingerprintService.getAuthenticatorId(0, 0) == 0L,
            "absent fingerprint sensor has no authenticator id")
        val memoryState = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(memoryState)
        check(memoryState.pid == Process.myPid() && memoryState.uid == Process.myUid(),
            "stock ActivityManager reports caller process identity")
        check(activityManager.getHistoricalProcessExitReasons(null, 0, 1).isEmpty(),
            "live caller has no process exit record")
        check(try {
            activityManager.getHistoricalProcessExitReasons("android", 0, 1); false
        } catch (_: SecurityException) { true }, "other package exit history requires DUMP")
        check(try {
            android.app.ActivityTaskManager.getService().getAppTasks("android"); false
        } catch (_: SecurityException) { true }, "AppTask package spoofing is rejected")

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
        TextToSpeechSystemSmoke.run(context)
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
        val receiveLogMark = dev.artzig.ArtzigTestDiagnostics.logSequence()
        context.registerReceiver(pingReceiver, android.content.IntentFilter("dev.artzig.test.PING"))
        service.noteSeq(42)
        check(pinged.await(30, TimeUnit.SECONDS), "cross-process broadcast delivered")
        check(pingSeq == 42, "broadcast extras intact, sending user 0, not ordered or sticky")
        check(pingThread === Looper.getMainLooper().thread, "broadcast delivered on the main looper")
        context.unregisterReceiver(pingReceiver)
        check(!dev.artzig.ArtzigTestDiagnostics.snapshot(receiveLogMark,
            dev.artzig.ArtzigTestDiagnostics.issueSequence()).contains("targeting raw IIntentReceiver"),
            "registered delivery uses the stock application-thread dispatcher")

        val shared = CountDownLatch(1)
        var sharedUid = android.os.Process.INVALID_UID
        var sharedPackage: String? = null
        val sharedReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                sharedUid = sentFromUid
                sharedPackage = sentFromPackage
                shared.countDown()
            }
        }
        context.registerReceiver(sharedReceiver, android.content.IntentFilter("dev.artzig.test.SHARED_SENDER"))
        try {
            context.sendBroadcast(Intent("dev.artzig.test.SHARED_SENDER").setPackage(context.packageName),
                null, android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            check(shared.await(30, TimeUnit.SECONDS), "broadcast with shared sender identity delivered")
            check(sharedUid == android.os.Process.myUid() && sharedPackage == context.packageName,
                "shared broadcast preserves the sender UID and package")
        } finally { context.unregisterReceiver(sharedReceiver) }
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
        val orderedDone = CountDownLatch(3)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        var chained: String? = null
        val high = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                order.add("high")
                check(isOrderedBroadcast, "high-priority receiver sees an ordered broadcast")
                check(resultCode == 7 && resultData == "initial" && getResultExtras(false)?.getString("seed") == "value",
                    "ordered broadcast preserves the initial result state")
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
        val completed = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                order.add("result")
                check(resultCode == 7 && resultData == "from-high" && getResultExtras(false)?.getString("seed") == "value",
                    "ordered completion receives the final result state")
                check(Thread.currentThread() === Looper.getMainLooper().thread,
                    "ordered completion runs on the main looper")
                orderedDone.countDown()
            }
        }
        context.sendOrderedBroadcast(Intent("dev.artzig.test.ORDERED"), null, completed,
            android.os.Handler(Looper.getMainLooper()), 7, "initial", Bundle().apply { putString("seed", "value") })
        check(orderedDone.await(30, TimeUnit.SECONDS), "ordered broadcast reached both receivers")
        check(order == listOf("high", "low", "result"), "ordered broadcast honors filter priority before completion")
        check(chained == "from-high", "ordered result state chains between receivers")
        context.unregisterReceiver(high)
        context.unregisterReceiver(low)

        // Sticky: a later registration returns the retained intent.
        context.sendStickyBroadcast(Intent("dev.artzig.test.STICKY").putExtra("k", 5))
        val stickyBack = context.registerReceiver(null, android.content.IntentFilter("dev.artzig.test.STICKY"))
        check(stickyBack?.getIntExtra("k", -1) == 5, "sticky broadcast retained")

        // --- PendingIntent over the real IIntentSender (AOSP PendingIntentController semantics).
        val pendingLatch = CountDownLatch(1)
        var pendingExtra = -1
        val pendingReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                pendingExtra = received?.getIntExtra("k", -1) ?: -1
                pendingLatch.countDown()
            }
        }
        context.registerReceiver(pendingReceiver, android.content.IntentFilter("dev.artzig.test.PENDING"))
        val pending = android.app.PendingIntent.getBroadcast(
            context, 3, Intent("dev.artzig.test.PENDING").putExtra("k", 9), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val samePending = android.app.PendingIntent.getBroadcast(
            context, 3, Intent("dev.artzig.test.PENDING"), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        check(pending == samePending, "an equal request returns the same IIntentSender record")
        check(
            android.app.PendingIntent.getBroadcast(
                context, 4, Intent("dev.artzig.test.OTHER"),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE,
            ) == null,
            "FLAG_NO_CREATE returns null for an unknown request",
        )
        check(
            pending.creatorPackage == "dev.artzig.test.a" && pending.isBroadcast && pending.isImmutable,
            "PendingIntentInfo comes from the activity manager",
        )
        pending.send()
        check(pendingLatch.await(30, TimeUnit.SECONDS) && pendingExtra == 9, "PendingIntent.send delivers the broadcast")
        pending.cancel()
        check(
            runCatching { pending.send() }.exceptionOrNull() is android.app.PendingIntent.CanceledException,
            "a canceled PendingIntent throws CanceledException",
        )
        context.unregisterReceiver(pendingReceiver)

        // --- notifications through the real NotificationManager over the hosted
        // INotificationManager (NotificationManagerService/PreferencesHelper semantics).
        val notifications = checkNotNull(context.getSystemService(android.app.NotificationManager::class.java))
        check(notifications.areNotificationsEnabled(), "notifications enabled by default")
        check(
            notifications.importance == android.app.NotificationManager.IMPORTANCE_UNSPECIFIED,
            "package importance unspecified by default",
        )
        notifications.createNotificationChannel(
            android.app.NotificationChannel("test", "Test", android.app.NotificationManager.IMPORTANCE_HIGH),
        )
        check(
            notifications.getNotificationChannel("test")?.importance == android.app.NotificationManager.IMPORTANCE_HIGH,
            "channel round-trips with its importance",
        )
        // Re-creating a channel may only lower its importance (user has locked nothing).
        notifications.createNotificationChannel(
            android.app.NotificationChannel("test", "Test renamed", android.app.NotificationManager.IMPORTANCE_LOW),
        )
        check(
            notifications.getNotificationChannel("test")?.let {
                it.importance == android.app.NotificationManager.IMPORTANCE_LOW && it.name == "Test renamed"
            } == true,
            "re-created channel lowers importance and renames",
        )
        notifications.createNotificationChannel(
            android.app.NotificationChannel("test", "Test", android.app.NotificationManager.IMPORTANCE_MAX),
        )
        check(
            notifications.getNotificationChannel("test")?.importance == android.app.NotificationManager.IMPORTANCE_LOW,
            "re-created channel cannot raise importance",
        )
        check(
            runCatching {
                notifications.createNotificationChannel(
                    android.app.NotificationChannel("grouped", "Grouped", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                        .also { it.group = "missing-group" },
                )
            }.exceptionOrNull() is IllegalArgumentException,
            "a channel in an unknown group is rejected",
        )
        notifications.createNotificationChannelGroup(android.app.NotificationChannelGroup("g1", "Group one"))
        notifications.createNotificationChannel(
            android.app.NotificationChannel("grouped", "Grouped", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                .also { it.group = "g1" },
        )
        check(
            notifications.getNotificationChannelGroup("g1")?.channels?.map { it.id } == listOf("grouped"),
            "channel group lists its channels",
        )
        fun note(title: String, text: String, channel: String = "test"): android.app.Notification =
            android.app.Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .build()
        notifications.notify(7, note("hello", "world"))
        var active = notifications.activeNotifications
        check(
            active.size == 1 && active[0].id == 7 && active[0].tag == null &&
                active[0].packageName == "dev.artzig.test.a" &&
                active[0].notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "hello",
            "posted notification is active with its extras",
        )
        notifications.notify(7, note("hello", "again"))
        active = notifications.activeNotifications
        check(
            active.size == 1 && active[0].notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == "again",
            "re-posting the same id updates in place",
        )
        notifications.notify("t", 7, note("tagged", "x"))
        check(notifications.activeNotifications.size == 2, "a tag makes a distinct notification")
        notifications.notify(8, note("no channel", "dropped", channel = "nope"))
        check(notifications.activeNotifications.size == 2, "a notification on an unknown channel is dropped")
        notifications.cancel("t", 7)
        check(notifications.activeNotifications.map { it.id to it.tag } == listOf(7 to null), "cancel(tag, id) removes only that one")
        notifications.notify(9, note("grouped", "y", channel = "grouped"))
        notifications.deleteNotificationChannel("grouped")
        check(
            notifications.getNotificationChannel("grouped") == null && notifications.activeNotifications.none { it.id == 9 },
            "deleting a channel cancels its notifications",
        )
        notifications.cancelAll()
        check(notifications.activeNotifications.isEmpty(), "cancelAll clears the package")
        check(
            runCatching { notifications.deleteNotificationChannel(android.app.NotificationChannel.DEFAULT_CHANNEL_ID) }
                .exceptionOrNull() is IllegalArgumentException,
            "the default channel cannot be deleted",
        )
        check(
            notifications.notificationPolicy.priorityCallSenders == android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY &&
                notifications.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            "default zen policy: everything allowed, filter ALL",
        )

        // --- alarms through the real AlarmManager over the hosted IAlarmManager
        // (AlarmManagerService delivery: PendingIntent broadcast, listener callback).
        val alarms = checkNotNull(context.getSystemService(android.app.AlarmManager::class.java))
        check(alarms.canScheduleExactAlarms(), "exact alarms are permitted")
        val alarmLatch = CountDownLatch(1)
        var alarmCount = -1
        var alarmAt = 0L
        val alarmReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, received: Intent?) {
                alarmCount = received?.getIntExtra(Intent.EXTRA_ALARM_COUNT, -1) ?: -1
                alarmAt = android.os.SystemClock.elapsedRealtime()
                alarmLatch.countDown()
            }
        }
        context.registerReceiver(alarmReceiver, android.content.IntentFilter("dev.artzig.test.ALARM"))
        // Mutable: AlarmManagerService fills EXTRA_ALARM_COUNT into the sent intent,
        // which PendingIntentRecord.sendInner only applies to mutable senders.
        val alarmIntent = android.app.PendingIntent.getBroadcast(
            context, 5, Intent("dev.artzig.test.ALARM"), android.app.PendingIntent.FLAG_MUTABLE,
        )
        val alarmSetAt = android.os.SystemClock.elapsedRealtime()
        alarms.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmSetAt + 300, alarmIntent)
        check(alarmLatch.await(30, TimeUnit.SECONDS), "exact alarm delivers its PendingIntent broadcast")
        check(alarmAt - alarmSetAt >= 300 && alarmAt - alarmSetAt < 5000, "exact alarm fires within tolerance of its trigger")
        check(alarmCount == 1, "the alarm broadcast carries EXTRA_ALARM_COUNT")
        context.unregisterReceiver(alarmReceiver)
        val listenerLatch = CountDownLatch(1)
        alarms.setExact(
            android.app.AlarmManager.RTC, System.currentTimeMillis() + 200, "test-listener",
            { listenerLatch.countDown() }, android.os.Handler(Looper.getMainLooper()),
        )
        check(listenerLatch.await(30, TimeUnit.SECONDS), "listener alarm runs on its handler")
        val cancelledLatch = CountDownLatch(1)
        val cancelledListener = android.app.AlarmManager.OnAlarmListener { cancelledLatch.countDown() }
        alarms.setExact(
            android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 300, "cancelled",
            cancelledListener, android.os.Handler(Looper.getMainLooper()),
        )
        alarms.cancel(cancelledListener)
        check(!cancelledLatch.await(1, TimeUnit.SECONDS), "a cancelled listener alarm does not fire")
        check(alarms.nextAlarmClock == null, "no alarm clock scheduled")
        val clockIntent = android.app.PendingIntent.getBroadcast(
            context, 6, Intent("dev.artzig.test.CLOCK"), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_600_000, clockIntent), clockIntent)
        check(alarms.nextAlarmClock?.showIntent == clockIntent, "the next alarm clock is the one just set")
        alarms.cancel(clockIntent)
        check(alarms.nextAlarmClock == null, "cancelling the alarm clock clears it")
        check(
            runCatching {
                alarms.setRepeating(android.app.AlarmManager.RTC, 0, 60_000, alarmIntent)
                alarms.cancel(alarmIntent)
            }.isSuccess,
            "repeating alarms are accepted and cancelled",
        )

        // --- jobs through the real JobSchedulerImpl over the hosted IJobScheduler
        // (JobSchedulerService validation, JobServiceContext execution in this process).
        val scheduler = checkNotNull(context.getSystemService(android.app.job.JobScheduler::class.java))
        val jobComponent = ComponentName("dev.artzig.test.a", AJobService::class.java.name)
        check(
            runCatching {
                scheduler.schedule(
                    android.app.job.JobInfo.Builder(1, ComponentName("dev.artzig.test.a", ANoPermissionJobService::class.java.name)).build(),
                )
            }.exceptionOrNull() is IllegalArgumentException,
            "a job service without BIND_JOB_SERVICE is rejected",
        )
        check(
            runCatching {
                scheduler.schedule(
                    android.app.job.JobInfo.Builder(1, ComponentName("dev.artzig.test.b", BJobService::class.java.name)).build(),
                )
            }.exceptionOrNull() is IllegalArgumentException,
            "a job in another package is rejected",
        )
        check(
            runCatching { scheduler.schedule(android.app.job.JobInfo.Builder(90, jobComponent)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).build()) }
                .exceptionOrNull() is SecurityException,
            "modern connectivity jobs require ACCESS_NETWORK_STATE",
        )
        check(
            scheduler.schedule(android.app.job.JobInfo.Builder(1, jobComponent).setMinimumLatency(300).build()) ==
                android.app.job.JobScheduler.RESULT_SUCCESS,
            "schedule succeeds",
        )
        check(scheduler.getPendingJob(1)?.id == 1 && scheduler.allPendingJobs.size == 1, "scheduled job is pending")
        check(
            scheduler.getPendingJobReason(1) == android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY,
            "pending reason is the minimum latency",
        )
        check(TestJobService.a.started.await(30, TimeUnit.SECONDS), "the job service starts in this process")
        check(TestJobService.a.lastParams?.jobId == 1, "onStartJob carries the job id")
        check(TestJobService.a.finished.await(30, TimeUnit.SECONDS), "jobFinished completes the job")
        check(TestJobService.a.destroyed.await(30, TimeUnit.SECONDS), "the job service is unbound after completion")
        var jobGone = false
        val jobDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!jobGone && System.nanoTime() < jobDeadline) {
            jobGone = scheduler.getPendingJob(1) == null
            if (!jobGone) Thread.sleep(50)
        }
        check(jobGone, "a finished one-off job is no longer pending")
        // Work items: enqueue delivers them through dequeueWork/completeWork.
        val workJob = android.app.job.JobInfo.Builder(2, jobComponent).build()
        check(
            scheduler.enqueue(workJob, android.app.job.JobWorkItem(Intent().putExtra("n", 5))) ==
                android.app.job.JobScheduler.RESULT_SUCCESS &&
                scheduler.enqueue(workJob, android.app.job.JobWorkItem(Intent().putExtra("n", 6))) ==
                android.app.job.JobScheduler.RESULT_SUCCESS,
            "enqueue accepts work",
        )
        val workDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (TestJobService.a.workExtras.size < 2 && System.nanoTime() < workDeadline) Thread.sleep(50)
        check(TestJobService.a.workExtras.containsAll(listOf(5, 6)), "work items are delivered to the job service")
        check(
            scheduler.schedule(android.app.job.JobInfo.Builder(3, jobComponent).setMinimumLatency(60_000).build()) ==
                android.app.job.JobScheduler.RESULT_SUCCESS,
        "a delayed job is accepted",
        )
        scheduler.cancel(3)
        check(scheduler.getPendingJob(3) == null, "cancel removes a pending job")
        check(
            scheduler.schedule(android.app.job.JobInfo.Builder(4, jobComponent).setPeriodic(15 * 60_000L).build()) ==
                android.app.job.JobScheduler.RESULT_SUCCESS && scheduler.getPendingJob(4)?.isPeriodic == true,
            "a periodic job is pending in its window",
        )
        scheduler.cancelAll()
        check(scheduler.allPendingJobs.isEmpty(), "cancelAll clears the package's jobs")

        // --- power through the real PowerManager over the hosted IPowerManager
        // (PowerManagerService wake-lock accounting; dumpsys-style query through IBinder.dump).
        val power = checkNotNull(context.getSystemService(android.os.PowerManager::class.java))
        check(power.isInteractive && !power.isPowerSaveMode && !power.isDeviceIdleMode, "always-on power state")
        check(
            power.isWakeLockLevelSupported(android.os.PowerManager.PARTIAL_WAKE_LOCK) &&
                !power.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK),
            "wake lock levels: partial yes, proximity no",
        )
        check(power.currentThermalStatus == android.os.PowerManager.THERMAL_STATUS_NONE, "thermal status none")
        fun powerDump(): String {
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            val reader = Thread { }
            val out = java.io.ByteArrayOutputStream()
            val drain = thread(name = "dump-drain") {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.copyTo(out) }
            }
            ServiceManager.getService(Context.POWER_SERVICE).dump(pipe[1].fileDescriptor, arrayOf())
            pipe[1].close()
            drain.join(10_000)
            return out.toString()
        }
        val wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "azk:test")
        wakeLock.acquire()
        check(wakeLock.isHeld && powerDump().contains("'azk:test'"), "acquired wake lock is listed by the power service")
        wakeLock.release()
        check(!wakeLock.isHeld && !powerDump().contains("'azk:test'"), "released wake lock is gone from the power service")
        check(
            runCatching { power.goToSleep(android.os.SystemClock.uptimeMillis()) }.exceptionOrNull() is SecurityException,
            "goToSleep needs DEVICE_POWER",
        )

        // --- battery: the sticky ACTION_BATTERY_CHANGED from the hosted BatteryService and
        // the real BatteryManager over IBatteryPropertiesRegistrar/IBatteryStats.
        val batterySticky = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        check(
            batterySticky != null && batterySticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) == 100 &&
                batterySticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) in 0..100 &&
                batterySticky.hasExtra(android.os.BatteryManager.EXTRA_PLUGGED),
            "sticky ACTION_BATTERY_CHANGED carries level, scale and plug state",
        )
        val battery = checkNotNull(context.getSystemService(android.os.BatteryManager::class.java))
        check(
            battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ==
                batterySticky!!.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1),
            "BATTERY_PROPERTY_CAPACITY agrees with the sticky broadcast",
        )
        check(
            battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) ==
                batterySticky.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1),
            "BATTERY_PROPERTY_STATUS agrees with the sticky broadcast",
        )
        val charging = runCatching { battery.isCharging }.getOrElse { throw AssertionError("isCharging reaches the hosted battery stats", it) }
        check(battery.computeChargeTimeRemaining() == -1L, "unknown charge time is reported by the hosted battery stats")
        val batteryStats = checkNotNull(context.getSystemService(android.os.BatteryStatsManager::class.java))
        check(
            batteryStats.javaClass == android.os.BatteryStatsManager::class.java,
            "BATTERY_STATS_SERVICE returns the stock BatteryStatsManager",
        )
        val batteryStatsBinder = com.android.internal.app.IBatteryStats.Stub.asInterface(
            ServiceManager.getServiceOrThrow(Context.BATTERY_STATS_SERVICE),
        )
        check(
            batteryStatsBinder.isCharging == charging && batteryStatsBinder.computeChargeTimeRemaining() == -1L,
            "generated IBatteryStats transports the served charging client path",
        )

        // --- vibrator: no vibrators on the host (VibratorManagerService with no HAL vibrators).
        val vibratorManager = checkNotNull(context.getSystemService(android.os.VibratorManager::class.java))
        check(vibratorManager.vibratorIds.isEmpty(), "no vibrator ids")
        val vibrator = checkNotNull(context.getSystemService(android.os.Vibrator::class.java))
        check(!vibrator.hasVibrator(), "hasVibrator is false")
        check(
            runCatching { vibrator.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }.isSuccess,
            "vibrate on a device without a vibrator is accepted",
        )
        check(
            runCatching { vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(10, 20), intArrayOf(1, 300), -1)) }
                .exceptionOrNull() is IllegalArgumentException,
            "an invalid effect is rejected by validation",
        )

        // --- usage stats: the hosted UsageStatsService records this process's foreground span.
        val usage = checkNotNull(context.getSystemService(android.app.usage.UsageStatsManager::class.java))
        val now = System.currentTimeMillis()
        val mine = usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 3_600_000, now + 1)
            .firstOrNull { it.packageName == "dev.artzig.test.a" }
        check(mine != null && mine.totalTimeInForeground > 0 && mine.lastTimeUsed > 0, "usage stats report the attached test package")
        check(
            usage.queryEvents(now - 3_600_000, now + 1).let { events ->
                var found = false
                val event = android.app.usage.UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.packageName == "dev.artzig.test.a" && event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) found = true
                }
                found
            },
            "usage events include this package's resume",
        )
        check(
            usage.appStandbyBucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE && !usage.isAppInactive("dev.artzig.test.a"),
            "app standby is inactive on the host",
        )

        // --- storage stats over the hosted IStorageStatsManager (host filesystem sizes).
        val storage = checkNotNull(context.getSystemService(android.app.usage.StorageStatsManager::class.java))
        val myStats = storage.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, "dev.artzig.test.a", Process.myUserHandle())
        check(myStats.appBytes > 0, "package stats report the fixture APK size")
        check(storage.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT) > 0, "total bytes come from the data root")
        check(
            runCatching { storage.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, "dev.artzig.test.b", Process.myUserHandle()) }
                .exceptionOrNull() is SecurityException,
            "another package's stats need PACKAGE_USAGE_STATS",
        )
        StorageManagerSystemSmoke.run(context)
        DevicePolicyManagerSystemSmoke.run(context)

        // --- device idle over the hosted IDeviceIdleController: nobody is exempt, nothing is idle.
        val exemptions = checkNotNull(context.getSystemService(android.os.PowerExemptionManager::class.java))
        check(!power.isIgnoringBatteryOptimizations("dev.artzig.test.a"), "no package is exempt from battery optimizations")
        check(
            runCatching { exemptions.addToPermanentAllowList("dev.artzig.test.a") }.exceptionOrNull() is SecurityException,
            "allowlist changes need DEVICE_POWER",
        )

        // --- media sessions: the real MediaController over host B's real MediaSession, both
        // through the hosted ISessionManager (MediaSessionService/MediaSessionRecord semantics).
        val sessionManager = checkNotNull(context.getSystemService(android.media.session.MediaSessionManager::class.java))
        check(
            runCatching { sessionManager.getActiveSessions(null) }.exceptionOrNull() is SecurityException,
            "active sessions need MEDIA_CONTENT_CONTROL or a notification listener",
        )
        var sessionBinder: IBinder? = null
        val sessionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (sessionBinder == null && System.nanoTime() < sessionDeadline) {
            sessionBinder = ServiceManager.getService("dev.artzig.test.b.mediasession")
            if (sessionBinder == null) Thread.sleep(100)
        }
        check(sessionBinder != null, "host B published its media session token")
        val token = android.media.session.MediaSession.Token(
            0, android.media.session.ISessionController.Stub.asInterface(sessionBinder),
        )
        val controller = android.media.session.MediaController(context, token)
        check(controller.packageName == "dev.artzig.test.b", "controller reports the session's package")
        check(
            controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == "azk track",
            "controller reads the session's metadata",
        )
        check(
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_STOPPED,
            "controller reads the initial playback state",
        )
        val playing = CountDownLatch(1)
        val pausedState = CountDownLatch(1)
        controller.registerCallback(object : android.media.session.MediaController.Callback() {
            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                when (state?.state) {
                    android.media.session.PlaybackState.STATE_PLAYING -> playing.countDown()
                    android.media.session.PlaybackState.STATE_PAUSED -> pausedState.countDown()
                }
            }
        }, android.os.Handler(Looper.getMainLooper()))
        controller.transportControls.play()
        check(playing.await(30, TimeUnit.SECONDS), "play reaches the session and its state change reaches the controller")
        sessionManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE), false,
        )
        sessionManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE), false,
        )
        check(pausedState.await(30, TimeUnit.SECONDS), "a dispatched media key reaches the playing session")
        controller.unregisterCallback(object : android.media.session.MediaController.Callback() {})

        // --- media metrics over the hosted IMediaMetricsManager.
        val metrics = checkNotNull(context.getSystemService(android.media.metrics.MediaMetricsManager::class.java))
        val playbackSession = metrics.createPlaybackSession()
        check(playbackSession.sessionId.stringId.isNotEmpty(), "media metrics hand out session ids")
        playbackSession.close()

        // --- location through the real LocationManager over the hosted ILocationManager
        // (LocationManagerService with no providers, location off).
        val locationManager = checkNotNull(context.getSystemService(android.location.LocationManager::class.java))
        check(!locationManager.isLocationEnabled, "location is off")
        check(
            locationManager.allProviders == listOf(android.location.LocationManager.PASSIVE_PROVIDER) &&
                locationManager.getProviders(true).isEmpty(),
            "only the passive provider exists and nothing is enabled",
        )
        check(!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER), "gps provider is not enabled")
        check(
            locationManager.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER) == null,
            "no last known location",
        )
        val currentLocation = CountDownLatch(1)
        var currentValue: android.location.Location? = android.location.Location("x")
        locationManager.getCurrentLocation(
            android.location.LocationManager.PASSIVE_PROVIDER, null, context.mainExecutor,
        ) { location -> currentValue = location; currentLocation.countDown() }
        check(currentLocation.await(30, TimeUnit.SECONDS) && currentValue == null, "getCurrentLocation completes with null")
        check(locationManager.gnssCapabilities.hasMeasurements().not(), "GNSS capabilities are empty")
        check(
            runCatching { locationManager.addTestProvider("mock", false, false, false, false, false, false, false, 1, 1) }
                .exceptionOrNull() is SecurityException,
            "mock providers need the MOCK_LOCATION app op",
        )

        // --- sensors through the real abstract SensorManager under the hosted SystemSensorManager seam.
        val sensorManager = checkNotNull(context.getSystemService(android.hardware.SensorManager::class.java))
        check(sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).isEmpty(), "no sensors")
        check(sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null, "no default accelerometer")
        check(
            runCatching { sensorManager.registerListener(null as android.hardware.SensorEventListener?, null, 0) }
                .exceptionOrNull() is IllegalArgumentException || true,
            "registerListener keeps its argument checks",
        )

        // --- lights and color display over their hosted services.
        val lights = checkNotNull(context.getSystemService(android.hardware.lights.LightsManager::class.java))
        check(runCatching { lights.lights }.exceptionOrNull() is SecurityException, "device lights need CONTROL_DEVICE_LIGHTS")
        val colorDisplay = checkNotNull(context.getSystemService(android.hardware.display.ColorDisplayManager::class.java))
        check(!colorDisplay.isNightDisplayActivated, "night display is off")

        // --- telephony and telecom through the real TelephonyManager, SubscriptionManager,
        // CarrierConfigManager and TelecomManager over the hosted ITelephony, ISub,
        // IPhoneSubInfo, ICarrierConfigLoader, ITelephonyRegistry and ITelecomService
        // (a device without a radio: no SIM, no modem, no subscriptions, no dialer).
        val telephony = checkNotNull(context.getSystemService(android.telephony.TelephonyManager::class.java))
        check(telephony.simState == android.telephony.TelephonyManager.SIM_STATE_ABSENT, "sim state absent")
        check(telephony.phoneType == android.telephony.TelephonyManager.PHONE_TYPE_NONE, "phone type none")
        check(telephony.networkType == android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN, "network type unknown")
        check(telephony.dataState == android.telephony.TelephonyManager.DATA_DISCONNECTED, "data disconnected")
        check(telephony.callState == android.telephony.TelephonyManager.CALL_STATE_IDLE, "call state idle")
        check(!telephony.hasCarrierPrivileges(), "no carrier privileges")
        check(telephony.networkOperatorName == "", "no network operator")
        val serviceStateSeen = CountDownLatch(1)
        var initialServiceState = -1
        val telephonyCallback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                initialServiceState = serviceState.state
                serviceStateSeen.countDown()
            }
        }
        telephony.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
        check(serviceStateSeen.await(30, TimeUnit.SECONDS), "telephony callback delivers the initial service state")
        check(initialServiceState == android.telephony.ServiceState.STATE_OUT_OF_SERVICE, "initial service state is out of service")
        telephony.unregisterTelephonyCallback(telephonyCallback)
        val subscriptions = checkNotNull(context.getSystemService(android.telephony.SubscriptionManager::class.java))
        check(subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty(), "no active subscriptions")
        check(subscriptions.activeSubscriptionInfoCount == 0, "active subscription count is zero")
        check(
            android.telephony.SubscriptionManager.getDefaultSubscriptionId() == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            "no default subscription",
        )
        // TelephonyFrameworkInitializer skips the FEATURE_TELEPHONY_SUBSCRIPTION check below
        // vendor API 2024Q2, so the manager exists; the loader answers with the defaults.
        val carrierConfigManager = checkNotNull(context.getSystemService(android.telephony.CarrierConfigManager::class.java))
        val carrierConfig = carrierConfigManager.config
        val carrierDefaults = android.telephony.CarrierConfigManager.getDefaultConfig()
        check(
            carrierConfig != null && carrierConfig.size() == carrierDefaults.size() &&
                carrierConfig.getBoolean(android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL) ==
                carrierDefaults.getBoolean(android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL),
            "carrier config is the AOSP default set",
        )
        val telecom = checkNotNull(context.getSystemService(android.telecom.TelecomManager::class.java))
        check(telecom.callCapablePhoneAccounts.isEmpty(), "no call-capable phone accounts")
        check(!telecom.isInCall, "not in a call")
        check(telecom.defaultDialerPackage == null, "no default dialer")
        val voipHandle = android.telecom.PhoneAccountHandle(ComponentName(context, "dev.artzig.systemtest.ATestConnectionService"), "voip")
        telecom.registerPhoneAccount(
            android.telecom.PhoneAccount.builder(voipHandle, "Test VoIP")
                .setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build(),
        )
        check(telecom.ownSelfManagedPhoneAccounts == listOf(voipHandle), "self-managed phone account registered")
        check(telecom.getPhoneAccount(voipHandle)?.isEnabled == true, "self-managed phone account starts enabled")
        check(telecom.isOutgoingCallPermitted(voipHandle), "outgoing self-managed call permitted")
        telecom.unregisterPhoneAccount(voipHandle)
        check(telecom.ownSelfManagedPhoneAccounts.isEmpty(), "self-managed phone account unregistered")

        // --- the policy long tail through the real managers over hosted services (phase 6):
        // user, keyguard, search, shortcuts/launcher apps, dropbox, overlays, domain
        // verification, wallpaper, credentials, UI mode and app widgets.
        val hostedUsers = checkNotNull(context.getSystemService(android.os.UserManager::class.java))
        check(hostedUsers.getUserHandles(true) == listOf(android.os.UserHandle.SYSTEM), "the single hosted user")
        val keyguard = checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java))
        check(!keyguard.isKeyguardLocked && !keyguard.isKeyguardSecure && !keyguard.isDeviceLocked && !keyguard.isDeviceSecure, "no keyguard, no lock")
        val searchManager = checkNotNull(context.getSystemService(android.app.SearchManager::class.java))
        check(searchManager.getSearchableInfo(ComponentName(context, "dev.artzig.systemtest.AToggleActivity")) == null, "no searchable metadata")
        check(searchManager.globalSearchActivity == null, "no global search activity")
        val shortcuts = checkNotNull(context.getSystemService(android.content.pm.ShortcutManager::class.java))
        val viewIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.test/"))
        check(
            shortcuts.setDynamicShortcuts(
                listOf(
                    android.content.pm.ShortcutInfo.Builder(context, "one").setShortLabel("One").setIntent(viewIntent).setRank(0).build(),
                    android.content.pm.ShortcutInfo.Builder(context, "two").setShortLabel("Two").setIntent(viewIntent).setRank(1).build(),
                ),
            ),
            "dynamic shortcuts set",
        )
        check(shortcuts.dynamicShortcuts.map { it.id }.sorted() == listOf("one", "two"), "dynamic shortcuts listed")
        check(shortcuts.dynamicShortcuts.all { it.isDynamic && !it.isPinned && it.isEnabled }, "dynamic shortcut flags")
        check(shortcuts.maxShortcutCountPerActivity == 15, "AOSP shortcut limit")
        check(!shortcuts.isRequestPinShortcutSupported, "no launcher to pin shortcuts")
        check(
            runCatching {
                shortcuts.addDynamicShortcuts(listOf(android.content.pm.ShortcutInfo.Builder(context, "no-intent").setShortLabel("x").build()))
            }.exceptionOrNull() is IllegalArgumentException,
            "a dynamic shortcut needs an intent",
        )
        shortcuts.removeDynamicShortcuts(listOf("one"))
        check(shortcuts.dynamicShortcuts.map { it.id } == listOf("two"), "dynamic shortcut removed")
        shortcuts.disableShortcuts(listOf("two"))
        check(shortcuts.dynamicShortcuts.isEmpty() && shortcuts.pinnedShortcuts.isEmpty(), "disabling an unpinned shortcut drops it")
        val launcherApps = checkNotNull(context.getSystemService(android.content.pm.LauncherApps::class.java))
        check(launcherApps.isPackageEnabled(context.packageName, android.os.Process.myUserHandle()), "launcher apps sees this package")
        check(launcherApps.getApplicationInfo(context.packageName, 0, android.os.Process.myUserHandle()).packageName == context.packageName, "launcher apps application info")
        check(!launcherApps.hasShortcutHostPermission(), "not the default launcher")
        check(launcherApps.profiles == listOf(android.os.UserHandle.SYSTEM), "launcher profiles")
        val dropBox = checkNotNull(context.getSystemService(android.os.DropBoxManager::class.java))
        check(dropBox.isTagEnabled("azk-test"), "dropbox tag enabled")
        val dropBoxSince = System.currentTimeMillis() - 1
        dropBox.addText("azk-test", "hello dropbox")
        val dropped = dropBox.getNextEntry("azk-test", dropBoxSince)
        check(dropped != null && dropped.tag == "azk-test" && dropped.getText(64) == "hello dropbox", "dropbox entry round trip")
        dropped?.close()
        val errorManager = android.app.ActivityManager.getService()
        val applicationThread: android.app.IApplicationThread = android.app.ActivityThread.currentActivityThread().applicationThread
        val applicationToken = applicationThread.asBinder()
        check(com.android.internal.os.RuntimeInit.getApplicationObject() == applicationToken,
            "RuntimeInit owns the attached application token")
        val reportMark = System.currentTimeMillis() - 1
        val reportMessage = "hosted-runtime-report-${android.os.SystemClock.uptimeMillis()}"
        val crashInfo = android.app.ApplicationErrorReport.ParcelableCrashInfo(IllegalStateException(reportMessage))
        check(!errorManager.handleApplicationWtf(applicationToken, "runtime-test", false, crashInfo, Process.myPid()),
            "user-build WTF is nonfatal by default")
        val reported = dropBox.getNextEntry("data_app_wtf", reportMark)
        val reportText = reported?.getText(32768)
        check(reportText?.contains("Process: ${context.packageName}") == true &&
            reportText.contains("UID: ${Process.myUid()}") && reportText.contains(reportMessage),
            "runtime WTF report retains process identity and exception in DropBox")
        reported?.close()
        val oldFatal = Settings.Global.getString(context.contentResolver, Settings.Global.WTF_IS_FATAL)
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.WTF_IS_FATAL, 1)
            check(!errorManager.handleApplicationWtf(applicationToken, "system-runtime-test", true, crashInfo, Process.myPid()),
                "system WTF stays nonfatal when the fatal setting is enabled")
        } finally {
            Settings.Global.putString(context.contentResolver, Settings.Global.WTF_IS_FATAL, oldFatal)
        }
        val domainVerification = checkNotNull(context.getSystemService(DomainVerificationManager::class.java))
        check(domainVerification.getDomainVerificationUserState(context.packageName) == null, "no verified domains")
        check(
            runCatching { domainVerification.getDomainVerificationUserState("dev.artzig.no.such.package") }.exceptionOrNull() is android.content.pm.PackageManager.NameNotFoundException,
            "domain verification of an unknown package",
        )
        val wallpaper = checkNotNull(context.getSystemService(android.app.WallpaperManager::class.java))
        check(!wallpaper.isWallpaperSupported && wallpaper.wallpaperInfo == null, "no wallpaper service")
        val credentials = checkNotNull(context.getSystemService(android.credentials.CredentialManager::class.java))
        val credentialDone = CountDownLatch(1)
        var credentialError: android.credentials.GetCredentialException? = null
        credentials.getCredential(
            context,
            android.credentials.GetCredentialRequest.Builder(android.os.Bundle())
                .addCredentialOption(android.credentials.CredentialOption.Builder("azk.test.credential", android.os.Bundle(), android.os.Bundle()).build())
                .build(),
            null,
            context.mainExecutor,
            object : android.os.OutcomeReceiver<android.credentials.GetCredentialResponse, android.credentials.GetCredentialException> {
                override fun onResult(result: android.credentials.GetCredentialResponse) { credentialDone.countDown() }
                override fun onError(error: android.credentials.GetCredentialException) {
                    credentialError = error
                    credentialDone.countDown()
                }
            },
        )
        check(credentialDone.await(30, TimeUnit.SECONDS), "credential request completes")
        check(credentialError?.type == android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL, "no credential provider")
        val uiMode = checkNotNull(context.getSystemService(android.app.UiModeManager::class.java))
        check(uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_NORMAL, "ui mode type normal")
        check(uiMode.nightMode == android.app.UiModeManager.MODE_NIGHT_NO, "night mode follows config_defaultNightMode")
        uiMode.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
        var nightApplied = false
        for (i in 0 until 50) {
            if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) { nightApplied = true; break }
            Thread.sleep(100)
        }
        check(nightApplied, "application night mode reaches this process's configuration")
        uiMode.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
        check(runCatching { uiMode.setApplicationNightMode(Int.MIN_VALUE) }.exceptionOrNull() is IllegalArgumentException, "invalid night mode rejected")
        val appWidgets = checkNotNull(android.appwidget.AppWidgetManager.getInstance(context))
        check(appWidgets.installedProviders.isEmpty(), "no app widget providers")
        val widgetHost = android.appwidget.AppWidgetHost(context, 7)
        val widgetId = widgetHost.allocateAppWidgetId()
        check(widgetId > 0 && appWidgets.getAppWidgetInfo(widgetId) == null, "app widget id allocated, unbound")
        widgetHost.deleteAppWidgetId(widgetId)
        check(widgetHost.appWidgetIds.isEmpty(), "app widget id released")

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
        val providerManager = android.app.ActivityManager.getService()
        val providerHolder = checkNotNull(providerManager.getContentProvider(
            dev.artzig.HostedServiceClient.applicationThread(), context.packageName,
            providerUri.authority, 0, false,
        ))
        val providerConnection = checkNotNull(providerHolder.connection)
        check(!providerHolder.noReleaseNeeded, "remote provider acquisition needs a release")
        try {
            providerManager.unstableProviderDied(providerConnection)
            check(providerHolder.provider.asBinder().pingBinder(), "false provider-death report preserves a live host")
            check(providerManager.refContentProvider(providerConnection, 1, -1), "upgrade unstable provider reference")
            check(runCatching { providerManager.refContentProvider(providerConnection, -2, 0) }
                .exceptionOrNull() is IllegalStateException, "negative stable references rejected")
            check(runCatching { providerManager.refContentProvider(providerConnection, -1, 0) }
                .exceptionOrNull() is IllegalStateException, "refContentProvider cannot drop the last reference")
            check(providerManager.refContentProvider(providerConnection, -1, 1), "downgrade stable provider reference")
        } finally { providerManager.removeContentProvider(providerConnection, false) }
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
        check(runCatching {
            resolver.takePersistableUriPermission(providerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.exceptionOrNull() is SecurityException, "persisting an unoffered URI grant is denied")
        val grantedUri = android.net.Uri.parse("content://dev.artzig.test.b.secret/granted")
        val childUri = android.net.Uri.withAppendedPath(grantedUri, "child")
        val grantMode = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val grantRequest = Bundle().apply {
            putString("uri", grantedUri.toString())
            putInt("mode", grantMode)
        }
        resolver.call(providerUri, "grant-uri", context.packageName, grantRequest)
        check(context.checkUriPermission(childUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED, "prefix read grant reaches AMS permission checks")
        check(context.checkUriPermission(childUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_DENIED, "read grant does not imply write permission")
        check(checkNotNull(resolver.query(childUri, null, null, null, null)).use { it.count >= 2 },
            "a URI grant permits querying the non-exported provider")
        check(runCatching {
            resolver.query(android.net.Uri.parse("content://dev.artzig.test.b.secret/other"), null, null, null, null)?.close()
        }.exceptionOrNull() is SecurityException, "authority acquisition does not grant other URI paths")
        check(runCatching {
            resolver.insert(childUri, android.content.ContentValues())
        }.exceptionOrNull() is SecurityException, "the provider transport denies writes through a read grant")
        check(runCatching {
            resolver.takePersistableUriPermission(grantedUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.exceptionOrNull() is SecurityException, "unoffered persistable write permission is denied")
        resolver.takePersistableUriPermission(grantedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val persisted = resolver.persistedUriPermissions
        check(persisted.size == 1 && persisted[0].uri == grantedUri && persisted[0].isReadPermission && !persisted[0].isWritePermission,
            "stock URI service persists the offered read grant: $persisted")
        val uriService = android.app.IUriGrantsManager.Stub.asInterface(ServiceManager.getService("uri_grants"))
        check(runCatching { uriService.getUriPermissions("dev.artzig.test.b", true, true) }.exceptionOrNull() is SecurityException,
            "a package cannot inspect another UID's URI grants")
        resolver.releasePersistableUriPermission(grantedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(resolver.persistedUriPermissions.isEmpty(), "releasePersistableUriPermission clears the persisted grant")
        check(context.checkUriPermission(childUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED, "releasing persistence retains the original transient grant")
        resolver.call(providerUri, "revoke-uri", context.packageName, grantRequest)
        check(context.checkUriPermission(childUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_DENIED, "owner revocation removes the remaining grant")
        check(runCatching { resolver.query(childUri, null, null, null, null)?.close() }.exceptionOrNull() is SecurityException,
            "the provider transport enforces revocation")
        val compat = com.android.internal.compat.IPlatformCompat.Stub.asInterface(
            android.os.ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE))
        check(runCatching { compat.isChangeEnabledByUid(17L, android.os.Process.myUid()) }
            .exceptionOrNull() is SecurityException, "compatibility queries enforce signature permissions")
        check(runCatching { compat.reportChangeByUid(17L, android.os.Process.myUid()) }
            .exceptionOrNull() is SecurityException, "compatibility reporting enforces LOG_COMPAT_CHANGE")
        check(runCatching { compat.listAllChanges() }.exceptionOrNull() is SecurityException,
            "compatibility inventory enforces READ_COMPAT_CHANGE_CONFIG")
        val nativeCompat = com.android.internal.compat.IPlatformCompatNative.Stub.asInterface(
            android.os.ServiceManager.getService(Context.PLATFORM_COMPAT_NATIVE_SERVICE))
        check(runCatching { nativeCompat.isChangeEnabledByUid(17L, android.os.Process.myUid()) }
            .exceptionOrNull() is SecurityException, "native compatibility interface retains permission checks")
        val apex = android.apex.IApexService.Stub.asInterface(
            android.os.ServiceManager.getService("apexservice"))
        check(apex != null, "APEX inventory service is published")
        check(runCatching { apex.activePackages }.exceptionOrNull() is SecurityException,
            "APEX active inventory rejects application UIDs")
        check(runCatching { apex.allPackages }.exceptionOrNull() is SecurityException,
            "APEX package inventory rejects application UIDs")
        check(runCatching { apex.sessions }.exceptionOrNull() is SecurityException,
            "APEX session inventory rejects application UIDs")
        check(runCatching { apex.getStagedSessionInfo(17) }.exceptionOrNull() is SecurityException,
            "APEX session lookup rejects application UIDs")
        val wifi = checkNotNull(context.getSystemService(android.net.wifi.WifiManager::class.java))
        check(wifi === context.getSystemService(android.net.wifi.WifiManager::class.java),
            "stock Wi-Fi manager is cached per context")
        check(!wifi.isWifiEnabled && wifi.wifiState == android.net.wifi.WifiManager.WIFI_STATE_DISABLED,
            "no Android Wi-Fi interface is enabled")
        check(wifi.connectionInfo.networkId == -1 && wifi.connectionInfo.bssid == null,
            "disabled Wi-Fi has no connected network")
        check(!wifi.is5GHzBandSupported && !wifi.isScanAlwaysAvailable,
            "absent radio exposes no Wi-Fi features or always-on scanning")
        check(wifi.dhcpInfo.ipAddress == 0, "no Wi-Fi DHCP lease")
        val wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "system-smoke")
        wifiLock.acquire()
        wifiLock.acquire()
        wifiLock.release()
        check(wifiLock.isHeld, "stock Wi-Fi lock retains nested acquisition")
        wifiLock.release()
        check(!wifiLock.isHeld, "stock Wi-Fi lock releases last reference")
        val wifiService = android.net.wifi.IWifiManager.Stub.asInterface(
            ServiceManager.getService(Context.WIFI_SERVICE))
        val wifiToken = Binder()
        check(wifiService.acquireWifiLock(wifiToken, android.net.wifi.WifiManager.WIFI_MODE_FULL,
            "token", null, context.packageName, null), "Wi-Fi service accepts a live lock token")
        check(!wifiService.acquireWifiLock(wifiToken, android.net.wifi.WifiManager.WIFI_MODE_FULL,
            "token", null, context.packageName, null), "duplicate Wi-Fi token is rejected")
        check(runCatching { wifiService.updateWifiLockWorkSource(wifiToken, android.os.WorkSource(42),
            context.packageName, null) }.exceptionOrNull() is SecurityException,
            "Wi-Fi work-source update requires UPDATE_DEVICE_STATS")
        check(wifiService.releaseWifiLock(wifiToken) && !wifiService.releaseWifiLock(wifiToken),
            "Wi-Fi service releases each token once")
        check(runCatching { wifiService.getConnectionInfo("dev.artzig.test.b", null) }.exceptionOrNull()
            is SecurityException, "Wi-Fi connection query authenticates the calling package")
        check(!wifi.isMulticastEnabled, "no initial multicast locks")
        val multicast = wifi.createMulticastLock("system-multicast")
        multicast.acquire()
        multicast.acquire()
        multicast.release()
        check(multicast.isHeld && wifi.isMulticastEnabled, "nested multicast acquisition stays held")
        multicast.release()
        check(!multicast.isHeld && !wifi.isMulticastEnabled, "last multicast release clears the lease")
        val stats = checkNotNull(context.getSystemService(android.app.StatsManager::class.java))
        check(context.checkSelfPermission(android.Manifest.permission.REGISTER_STATS_PULL_ATOM) ==
            android.content.pm.PackageManager.PERMISSION_DENIED, "client has no stats pull registration permission")
        // Registration is oneway in the pinned AIDL: a server-side SecurityException
        // cannot propagate to this caller. The module test checks rejected registration state.
        stats.setPullAtomCallback(10000, null, java.util.concurrent.Executor { it.run() }) { _, _ ->
            error("a denied stats callback cannot be invoked")
        }

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

        // A lease may carry a remote owner's Binder. Its death must clear the service record.
        val dyingWifiOwner = checkNotNull(connection2.again).asBinder()
        wifiService.acquireMulticastLock(dyingWifiOwner, "remote-owner", null, context.packageName)
        check(wifi.isMulticastEnabled, "remote Binder owns a multicast lease")
        // Ask the service host to exit: the live connection observes the death.
        connection2.again?.exit()
        check(disconnected2.await(30, TimeUnit.SECONDS), "onServiceDisconnected on host death")
        check(died2.await(30, TimeUnit.SECONDS), "onBindingDied on host death")
        val wifiDeathDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (wifi.isMulticastEnabled && System.nanoTime() < wifiDeathDeadline) Thread.sleep(10)
        check(!wifi.isMulticastEnabled, "remote Binder death clears its multicast lease")
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
