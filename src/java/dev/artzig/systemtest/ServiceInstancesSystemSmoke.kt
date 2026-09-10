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
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            connected.add(name to service)
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
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
    fun run(context: Context) {
        val path = java.io.File(checkNotNull(System.getenv("AZK_FIXTURE_SERVICE_INSTANCES"))).canonicalPath
        val ownerUid = context.packageManager.getApplicationInfo(PACKAGE, 0).uid
        check(context.packageManager.checkPermission(android.Manifest.permission.CAMERA, PACKAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        check(context.packageManager.checkSignatures(PACKAGE, context.packageName) ==
            android.content.pm.PackageManager.SIGNATURE_NO_MATCH)
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
            val external = bind("ExternalService", "external",
                Context.BIND_AUTO_CREATE.toLong() or Context.BIND_EXTERNAL_SERVICE_LONG)
            check(external.first == ComponentName(context.packageName, "$PACKAGE.ExternalService:external"))
            check(external.second.getString("package") == context.packageName)
            check(external.second.getInt("ownerUid") == context.applicationInfo.uid)
            println("Service instances: separate/reused names, shared process, external identity, APK loading and kernel isolated UID passed")
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
