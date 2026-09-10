package dev.artzig.systemtest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Public client APIs against independently compiled/signed service APK code. */
internal object ServiceInstancesSystemSmoke {
    private const val PACKAGE = "dev.artzig.fixtures.services"
    private class Connection : ServiceConnection {
        val connected = LinkedBlockingQueue<Pair<ComponentName, IBinder>>()
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val died = java.util.concurrent.CountDownLatch(1)
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            connected.add(name to service)
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected.countDown() }
        override fun onBindingDied(name: ComponentName) { died.countDown() }
    }
    private fun snapshot(binder: IBinder): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStrongBinder(android.os.ServiceManager.getService(Context.APP_OPS_SERVICE))
            check(binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.readException()
            return checkNotNull(reply.readBundle())
        } finally { data.recycle(); reply.recycle() }
    }
    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { description }
            Thread.sleep(10)
        }
    }

    private fun pendingAndRepeatedBindings(context: Context) {
        val manager = android.app.ActivityManager.getService()
        val instance = "deferred"
        val intent = Intent().setClassName(PACKAGE, "$PACKAGE.InstanceService")
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_SHARED_ISOLATED_PROCESS
        val dormant = Connection()
        val active = Connection()
        // No AUTO_CREATE must retain a successful binding without starting a child.
        check(context.bindIsolatedService(intent, Context.BIND_SHARED_ISOLATED_PROCESS,
            instance, context.mainExecutor, dormant))
        check(dormant.connected.isEmpty())
        val pending = manager.getServices(100, 0).single { it.process == "${context.packageName}:ishared:$instance" }
        check(pending.pid == 0 && pending.clientCount == 1)
        try {
            // Repeated binds through one dispatcher belong to separate records;
            // its eventual unbind must remove all of them across both services.
            check(context.bindIsolatedService(intent, flags, instance, context.mainExecutor, active))
            check(context.bindIsolatedService(Intent(intent).setAction("second-filter"), flags,
                instance, context.mainExecutor, active))
            check(context.bindIsolatedService(Intent().setClassName(PACKAGE, "$PACKAGE.OtherService"),
                flags, instance, context.mainExecutor, active))
            val first = checkNotNull(dormant.connected.poll(30, TimeUnit.SECONDS))
            val delivered = listOf(checkNotNull(active.connected.poll(30, TimeUnit.SECONDS)),
                checkNotNull(active.connected.poll(30, TimeUnit.SECONDS)))
            check(delivered.map { it.first.className }.toSet() ==
                setOf("$PACKAGE.InstanceService", "$PACKAGE.OtherService"))
            val records = manager.getServices(100, 0).filter { it.process == "${context.packageName}:ishared:$instance" }
            check(records.map { it.pid }.distinct().size == 1)
            check(records.single { it.service.className.endsWith(".InstanceService") }.clientCount == 3)
            awaitCondition("Both retained filters should reach onBind") {
                snapshot(first.second).getStringArrayList("events") == arrayListOf("create", "bind", "bind")
            }
        } finally {
            context.unbindService(active)
            context.unbindService(dormant)
        }
        awaitCondition("One unbind must remove every service connection for the dispatcher") {
            manager.getServices(100, 0).none { it.process == "${context.packageName}:ishared:$instance" }
        }

        // A binding registered before startService participates in realStart's
        // create -> bindings -> start-arguments ordering, also for isolated services.
        val started = Connection()
        check(context.bindService(intent, started, 0))
        try {
            check(context.startService(Intent(intent).setAction("queued-start")) != null)
            val service = checkNotNull(started.connected.poll(30, TimeUnit.SECONDS)).second
            awaitCondition("Queued start must follow create and bind") {
                snapshot(service).getStringArrayList("events") == arrayListOf("create", "bind", "start:queued-start:1")
            }
            check(snapshot(service).getBoolean("isolated"))
        } finally {
            context.stopService(intent)
            context.unbindService(started)
        }
    }

    private fun pendingCancellationAndFailure(context: Context) {
        val manager = android.app.ActivityManager.getService()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var childPid = 0
        val gate = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(android.os.UserHandle.isIsolated(android.os.Binder.getCallingUid()))
                childPid = android.os.Binder.getCallingPid()
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                reply!!.writeNoException()
                return true
            }
        }
        android.os.ServiceManager.addService("dev.artzig.test.a.service-startup", gate, true)
        val intent = Intent().setClassName(PACKAGE, "$PACKAGE.InstanceService")
        val connection = Connection()
        check(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "early_cancel", context.mainExecutor, connection))
        try {
            check(entered.await(15, TimeUnit.SECONDS))
            check(connection.connected.isEmpty())
            context.unbindService(connection)
        } finally { release.countDown() }
        awaitCondition("Early unbind must retire the pending isolated child after attachment") {
            manager.getRunningAppProcesses().none { it.pid == childPid }
        }
        check(connection.connected.isEmpty() && connection.died.count == 1L)
        val failed = Connection()
        check(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "attach_failure", context.mainExecutor, failed))
        try {
            check(failed.died.await(20, TimeUnit.SECONDS)) { "Failed Application.onCreate must terminate pending binding" }
            check(failed.connected.isEmpty())
            check(manager.getServices(100, 0).none { it.process.endsWith(":attach_failure") })
        } finally { context.unbindService(failed) }
    }

    private fun kill(binder: IBinder) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { check(binder.transact(IBinder.FIRST_CALL_TRANSACTION + 2, data, reply, 0)); reply.readException() }
        finally { data.recycle(); reply.recycle() }
    }

    private fun restartAndStartResults(context: Context) {
        val intent = Intent().setClassName(PACKAGE, "$PACKAGE.InstanceService")
        val bound = Connection()
        check(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "restart", context.mainExecutor, bound))
        try {
            val old = checkNotNull(bound.connected.poll(30, TimeUnit.SECONDS)).second
            val state = snapshot(old)
            kill(old)
            check(bound.disconnected.await(10, TimeUnit.SECONDS))
            val replacement = checkNotNull(bound.connected.poll(30, TimeUnit.SECONDS)).second
            check(snapshot(replacement).getInt("pid") != state.getInt("pid"))
            check(snapshot(replacement).getStringArrayList("events") == arrayListOf("create", "bind"))
            check(bound.died.count == 1L)
            check(!old.pingBinder())
        } finally { context.unbindService(bound) }

        for (mode in arrayOf("sticky", "redeliver", "not-sticky")) {
            val connection = Connection()
            check(context.bindService(intent, connection, 0))
            check(context.startService(Intent(intent).setAction(mode)) != null)
            try {
                val old = checkNotNull(connection.connected.poll(30, TimeUnit.SECONDS)).second
                awaitCondition("First $mode start should execute") { snapshot(old).getInt("startId") == 1 }
                kill(old)
                check(connection.disconnected.await(10, TimeUnit.SECONDS))
                if (mode == "not-sticky") {
                    check(connection.died.await(10, TimeUnit.SECONDS)) { "START_NOT_STICKY must stop without an AUTO_CREATE binding" }
                    check(connection.connected.isEmpty())
                } else {
                    val replacement = checkNotNull(connection.connected.poll(30, TimeUnit.SECONDS)).second
                    awaitCondition("$mode service must receive its restart arguments") {
                        snapshot(replacement).getInt("startId") > 0
                    }
                    val state = snapshot(replacement)
                    val expected = if (mode == "sticky") "start:null:2" else "start:redeliver:1"
                    check(state.getStringArrayList("events") == arrayListOf("create", "bind", expected)) {
                        "$mode: ${state.getStringArrayList("events")}" }
                    check(state.getInt("startFlags") == if (mode == "redeliver") android.app.Service.START_FLAG_REDELIVERY else 0)
                    check(connection.died.count == 1L)
                }
            } finally {
                context.stopService(intent)
                context.unbindService(connection)
            }
        }
    }

    private fun clientDeathReleasesChild(context: Context) {
        val connection = Connection()
        check(context.bindService(Intent().setClassName(PACKAGE, "$PACKAGE.ClientService"),
            connection, Context.BIND_AUTO_CREATE))
        try {
            val client = checkNotNull(connection.connected.poll(30, TimeUnit.SECONDS)).second
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            val child = try {
                check(client.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                reply.readException()
                checkNotNull(reply.readStrongBinder())
            } finally { data.recycle(); reply.recycle() }
            val state = snapshot(child)
            val death = java.util.concurrent.CountDownLatch(1)
            child.linkToDeath({ death.countDown() }, 0)
            kill(client)
            check(connection.disconnected.await(10, TimeUnit.SECONDS))
            check(death.await(10, TimeUnit.SECONDS)) { "Dead client's isolated service survived" }
            awaitCondition("Dead client must release its child process") {
                android.app.ActivityManager.getService().getRunningAppProcesses().none { it.pid == state.getInt("pid") }
            }
        } finally { context.unbindService(connection) }
    }

    private fun replacementDuringDestroy(context: Context) {
        val intent = Intent().setClassName(PACKAGE, "$PACKAGE.InstanceService")
        val previous = Connection(); val next = Connection()
        check(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "replacement", context.mainExecutor, previous))
        val oldBinder = checkNotNull(previous.connected.poll(30, TimeUnit.SECONDS)).second
        val old = snapshot(oldBinder)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val died = java.util.concurrent.CountDownLatch(1)
        oldBinder.linkToDeath({ died.countDown() }, 0)
        val observer = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                reply!!.writeNoException()
                return true
            }
        }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeStrongBinder(observer)
            check(oldBinder.transact(IBinder.FIRST_CALL_TRANSACTION + 1, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
        context.unbindService(previous)
        try {
            check(entered.await(10, TimeUnit.SECONDS)) { "onDestroy was not entered" }
            check(oldBinder.pingBinder())
            check(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "replacement", context.mainExecutor, next))
            val replacement = snapshot(checkNotNull(next.connected.poll(30, TimeUnit.SECONDS)).second)
            check(replacement.getInt("pid") != old.getInt("pid"))
            check(replacement.getInt("uid") != old.getInt("uid"))
            check(replacement.getString("process") == old.getString("process"))
        } finally { release.countDown(); context.unbindService(next) }
        check(died.await(10, TimeUnit.SECONDS)) { "Old service process survived onDestroy completion" }
    }

    fun run(context: Context) {
        val path = java.io.File(checkNotNull(System.getenv("AZK_FIXTURE_SERVICE_INSTANCES"))).canonicalPath
        val ownerUid = context.packageManager.getApplicationInfo(PACKAGE, 0).uid
        check(context.packageManager.checkPermission(android.Manifest.permission.CAMERA, PACKAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        check(context.packageManager.checkSignatures(PACKAGE, context.packageName) ==
            android.content.pm.PackageManager.SIGNATURE_NO_MATCH)
        pendingAndRepeatedBindings(context)
        pendingCancellationAndFailure(context)
        replacementDuringDestroy(context)
        restartAndStartResults(context)
        clientDeathReleasesChild(context)
        val connections = ArrayList<Connection>()
        val deaths = ArrayList<Pair<IBinder, java.util.concurrent.CountDownLatch>>()
        fun bind(service: String, instance: String, flags: Long = Context.BIND_AUTO_CREATE.toLong()): Pair<ComponentName, Bundle> {
            val connection = Connection()
            val intent = Intent().setClassName(PACKAGE, "$PACKAGE.$service")
            // Pinned ContextWrapper omits the long-flags overload. Exercise its
            // original base ContextImpl for bit 62; ordinary flags use the wrapper.
            val bound = if (flags ushr 32 == 0L) context.bindIsolatedService(intent,
                flags.toInt(), instance, context.mainExecutor, connection)
            else (context as android.content.ContextWrapper).baseContext.bindIsolatedService(intent,
                Context.BindServiceFlags.of(flags), instance, context.mainExecutor, connection)
            check(bound)
            connections.add(connection)
            val result = checkNotNull(connection.connected.poll(30, TimeUnit.SECONDS)) {
                "No service connection for $service/$instance"
            }
            val death = java.util.concurrent.CountDownLatch(1)
            result.second.linkToDeath({ death.countDown() }, 0)
            deaths.add(result.second to death)
            val state = snapshot(result.second)
            val uid = state.getInt("uid")
            check(uid in 99000..99999 && uid == state.getInt("kernelUid") && uid == state.getInt("kernelGid")) { state.toString() }
            check(state.getBoolean("isolated"))
            check(state.getBoolean("noSharedCredentials"))
            check(state.getInt("appOp") == android.app.AppOpsManager.MODE_ERRORED)
            check(state.getInt("camera") == android.content.pm.PackageManager.PERMISSION_DENIED)
            check(state.getStringArrayList("events") == arrayListOf("create", "bind")) { state.toString() }
            check(state.getString("codePath") == path)
            check(state.getString("loader")!!.contains(path)) { state.toString() }
            return result.first to state
        }
        try {
            val first = bind("InstanceService", "one")
            val again = bind("InstanceService", "one")
            val second = bind("InstanceService", "two")
            check(first.first == ComponentName(PACKAGE, "$PACKAGE.InstanceService"))
            check(first.second.getInt("pid") == again.second.getInt("pid"))
            check(first.second.getInt("pid") != second.second.getInt("pid"))
            check(first.second.getInt("uid") != second.second.getInt("uid"))
            check(first.second.getInt("ownerUid") == ownerUid)
            check(first.second.getString("process") == "$PACKAGE:worker:$PACKAGE.InstanceService:one")
            val sharedFlags = Context.BIND_AUTO_CREATE.toLong() or Context.BIND_SHARED_ISOLATED_PROCESS.toLong()
            val shared = bind("InstanceService", "shared", sharedFlags)
            val sharedOther = bind("OtherService", "shared", sharedFlags)
            check(shared.second.getInt("pid") == sharedOther.second.getInt("pid"))
            check(shared.second.getString("process") == "${context.packageName}:ishared:shared")
            val privateFlags = Context.BIND_AUTO_CREATE.toLong() or Context.BIND_PACKAGE_ISOLATED_PROCESS.toLong()
            val privateOne = bind("InstanceService", "private_one", privateFlags)
            val privateTwo = bind("OtherService", "private_two", privateFlags)
            check(privateOne.second.getInt("pid") == privateTwo.second.getInt("pid"))
            check(privateOne.second.getString("process") == "$PACKAGE:worker")
            check(privateOne.second.getInt("pid") != shared.second.getInt("pid"))
            val invalid = Connection()
            check(runCatching {
                context.bindIsolatedService(Intent().setClassName(PACKAGE, "$PACKAGE.InstanceService"),
                    privateFlags.toInt() or Context.BIND_SHARED_ISOLATED_PROCESS,
                    "invalid_both", context.mainExecutor, invalid)
            }.exceptionOrNull() is SecurityException)
            check(runCatching {
                context.startService(Intent().setClassName(PACKAGE, "$PACKAGE.ExternalService"))
            }.exceptionOrNull() is SecurityException)
            val external = bind("ExternalService", "external",
                Context.BIND_AUTO_CREATE.toLong() or Context.BIND_EXTERNAL_SERVICE_LONG)
            check(external.first == ComponentName(context.packageName, "$PACKAGE.ExternalService:external"))
            check(external.second.getString("package") == context.packageName)
            check(external.second.getInt("ownerUid") == context.applicationInfo.uid)
            println("Service instances: separate/reused names, shared/private sharing, external identity, APK loading and kernel isolated UID passed")
        } finally {
            for (connection in connections) context.unbindService(connection)
        }
        for ((binder, death) in deaths) check(death.await(10, TimeUnit.SECONDS)) {
            "An unneeded isolated process survived its final service destruction: ${runCatching { snapshot(binder).let { "${it.getStringArrayList("events")}: ${it.getString("mainStack")}" } }}; running=${android.app.ActivityManager.getService().getServices(100, 0).map { "${it.service}:${it.clientCount}" }}"
        }
    }
}

/** Fixture installation belongs to the external system launcher, not an app UID. */
internal object ServiceFixtureInstallSmoke {
    @JvmStatic fun main(args: Array<String>) {
        dev.artzig.BinderSession.setIdentity("android", true)
        val path = java.io.File(checkNotNull(System.getenv("AZK_FIXTURE_SERVICE_INSTANCES"))).canonicalPath
        check(checkNotNull(dev.artzig.HostedServiceClient.installerOrNull()).installPackage(path, null) ==
            "dev.artzig.fixtures.services")
        android.app.ActivityThread.getPackageManager().grantRuntimePermission(
            "dev.artzig.fixtures.services", android.Manifest.permission.CAMERA, 0)
    }
}
