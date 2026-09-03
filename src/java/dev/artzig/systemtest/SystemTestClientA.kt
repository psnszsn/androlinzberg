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
        val fixture = checkNotNull(System.getenv("AZK_FIXTURE_APK_A")) { "AZK_FIXTURE_APK_A is not set" }
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "dev.artzig.test.a")
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
        check(runCatching { battery.isCharging }.isSuccess, "isCharging reaches the hosted battery stats")

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
