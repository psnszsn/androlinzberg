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
    private val transport = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val appOps = com.android.internal.app.IAppOpsService.Stub.asInterface(data.readStrongBinder())
            val op = appOps.startOperation(Binder(), android.app.AppOpsManager.OP_CAMERA,
                Process.myUid(), packageName, null, false, false, "isolated service contract", false, 0, -1)
            val result = Bundle().apply {
                putInt("appOp", op.opMode)
                putString("mainStack", android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
                putInt("uid", Process.myUid())
                putInt("kernelUid", android.system.Os.getuid())
                putInt("kernelGid", android.system.Os.getgid())
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
    override fun onDestroy() { synchronized(events) { events.add("destroy") }; super.onDestroy() }
}
class OtherService : InstanceService()
class ExternalService : InstanceService()
