package dev.artzig.fixtures.services

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Compiled only into the independently signed APK. A framework class with the
 * same name cannot satisfy the service creation/class-loader regression. */
open class InstanceService : Service() {
    private val events = ArrayList<String>()
    @Volatile private var lastStartFlags = 0
    @Volatile private var lastStartId = 0
    @Volatile private var destroyObserver: IBinder? = null
    private val transport = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION + 2) {
                android.os.Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
                reply!!.writeNoException()
                return true
            }
            if (code == FIRST_CALL_TRANSACTION + 1) {
                destroyObserver = data.readStrongBinder()
                reply!!.writeNoException()
                return true
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val appOps = com.android.internal.app.IAppOpsService.Stub.asInterface(data.readStrongBinder())
            val op = appOps.startOperation(Binder(), android.app.AppOpsManager.OP_CAMERA,
                Process.myUid(), packageName, null, false, false, "isolated service contract", false, 0, -1)
            // Read actual procfs bytes, independently of the Android identity
            // APIs, to keep this a kernel-user-namespace regression.
            val kernelStatus = java.io.File("/proc/self/status").readLines()
            fun kernelId(field: String): Int = kernelStatus.single { it.startsWith("$field:") }
                .split(Regex("\\s+"))[1].toInt()
            val result = Bundle().apply {
                putInt("appOp", op.opMode)
                putInt("startFlags", lastStartFlags)
                putInt("startId", lastStartId)
                putString("mainStack", android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                putInt("uid", Process.myUid())
                putInt("callerUidForPid", Process.getUidForPid(Binder.getCallingPid()))
                putInt("kernelUid", kernelId("Uid"))
                putInt("kernelGid", kernelId("Gid"))
                putInt("pid", Process.myPid())
                putInt("ownerUid", applicationInfo.uid)
                putString("package", packageName)
                putString("process", Application.getProcessName())
                putString("codePath", applicationInfo.sourceDir)
                putString("loader", this@InstanceService.javaClass.classLoader.toString())
                putStringArrayList("events", synchronized(events) { ArrayList(events) })
                putInt("camera", checkSelfPermission(android.Manifest.permission.CAMERA))
                putBoolean("isolated", Process.isIsolated())
                putBoolean("noSharedCredentials", System.getenv("AZK_BINDER_COOKIE") == null &&
                    System.getenv("AZK_BINDER_SYSTEM_COOKIE") == null)
            }
            reply!!.writeNoException()
            reply.writeBundle(result)
            return true
        }
    }
    override fun onCreate() { super.onCreate(); synchronized(events) { events.add("create") } }
    override fun onBind(intent: Intent): IBinder {
        synchronized(events) { events.add("bind") }
        return transport
    }
    override fun onUnbind(intent: Intent): Boolean {
        synchronized(events) { events.add("unbind") }
        return true
    }
    override fun onRebind(intent: Intent) { synchronized(events) { events.add("rebind") } }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartFlags = flags
        lastStartId = startId
        synchronized(events) { events.add("start:${intent?.action}:$startId") }
        return when (intent?.action) {
            "sticky" -> START_STICKY
            "redeliver" -> START_REDELIVER_INTENT
            else -> START_NOT_STICKY
        }
    }
    override fun onDestroy() {
        synchronized(events) { events.add("destroy") }
        destroyObserver?.let { observer ->
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { observer.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0); reply.readException() }
            finally { data.recycle(); reply.recycle() }
        }
        super.onDestroy()
    }
}
class OtherService : InstanceService()
class ExternalService : InstanceService()

/** The gate is a real allowed Binder service, so the driver can hold onCreate
 * before finishAttachApplication without timers or framework test hooks. */
class FixtureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":attach_failure")) error("deliberate service attachment failure")
        if (!getProcessName().endsWith(":early_cancel")) return
        val gate = checkNotNull(android.os.ServiceManager.waitForService("dev.artzig.test.a.service-startup"))
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { gate.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0); reply.readException() }
        finally { data.recycle(); reply.recycle() }
    }
}

/** An ordinary APK process owns a second process's binding. Killing this client
 * must release its isolated child even though onDestroy cannot run. */
class ClientService : Service() {
    private val children = java.util.concurrent.LinkedBlockingQueue<IBinder>()
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
            children.add(service)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {}
    }
    override fun onCreate() {
        super.onCreate()
        check(bindIsolatedService(Intent().setClassName(packageName, "$packageName.OtherService"),
            BIND_AUTO_CREATE, "client_owned", mainExecutor, connection))
    }
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION) {
                val child = checkNotNull(children.poll(20, java.util.concurrent.TimeUnit.SECONDS))
                reply!!.writeNoException()
                reply.writeStrongBinder(child)
                return true
            }
            if (code == FIRST_CALL_TRANSACTION + 2) {
                android.os.Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
                reply!!.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
    override fun onDestroy() { unbindService(connection); super.onDestroy() }
}
