package dev.artzig

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.android.server.LocalServices
import com.android.server.firewall.IntentFirewall
import dev.artzig.system.HostedPackageManagerService
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Stock ATMS policy collaborator, including its Linux-driven rule reload path. */
object IntentFirewallSmoke {
    private fun eventually(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        check(predicate()) { description }
    }

    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        // AOSP's server-fixture mode avoids publishing system cache nonces
        // from this unprivileged, process-local package catalog.
        android.app.PropertyInvalidatedCache.disableForTestMode()
        HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 640, 480, 120, 0)
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle()))
        packages.installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        val internal = Class.forName("android.content.pm.HostedPackageManagerInternal")
            .getConstructor(HostedPackageManagerService::class.java).newInstance(packages)
            as PackageManagerInternal
        LocalServices.addService(PackageManagerInternal::class.java, internal)

        // This target has its own ARTZIG_DATA_DIR; never edit session policy.
        val rules = IntentFirewall.getRulesDir()
        check(rules.mkdirs() || rules.isDirectory)
        check(rules.listFiles().orEmpty().isEmpty()) { "Policy test requires an empty rules directory" }
        fileObservation(rules)
        val lock = Any()
        val permissionCalls = LinkedBlockingQueue<String>()
        val policyThread = HandlerThread("intent-policy-test").apply { start() }
        val firewall = IntentFirewall(object : IntentFirewall.AMSInterface {
            override fun getAMSLock(): Any = lock
            override fun checkComponentPermission(
                permission: String?, pid: Int, uid: Int, owningUid: Int, exported: Boolean,
            ): Int {
                permissionCalls.add("$permission:$pid:$uid:$owningUid:$exported")
                return if (permission == "test.permission" && uid == 10001)
                    PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }, Handler(policyThread.looper))
        val receiver = ApplicationInfo().apply { uid = 1000 }
        val component = ComponentName("test.receiver", "test.receiver.Target")
        fun activity(action: String, caller: Int = 10001): Boolean = synchronized(lock) {
            firewall.checkStartActivity(Intent(action).setComponent(component), caller, 123, null, receiver)
        }
        fun service(): Boolean = synchronized(lock) {
            firewall.checkService(component, Intent("test.BLOCK"), 10001, 123, null, receiver)
        }
        fun broadcast(action: String): Boolean = synchronized(lock) {
            firewall.checkBroadcast(Intent(action), 10001, 123, null, 1000)
        }
        val temporary = File(rules, "rules.tmp")
        val active = File(rules, "rules.xml")
        try {
            check(activity("test.BLOCK") && service() && broadcast("test.BLOCK"))
            temporary.writeText("""
                <rules>
                  <activity block="true">
                    <component-filter name="test.receiver/.Target"/>
                    <action equals="test.BLOCK"/>
                    <sender-permission name="test.permission"/>
                  </activity>
                  <activity block="true">
                    <component-filter name="test.receiver/.Target"/>
                    <action equals="test.SIGNATURE"/>
                    <sender type="signature"/>
                  </activity>
                  <broadcast block="true">
                    <intent-filter><action name="test.BROADCAST"/></intent-filter>
                  </broadcast>
                </rules>
            """.trimIndent())
            // Publishing a non-XML staging file cannot change policy.
            check(activity("test.BLOCK"))
            check(temporary.renameTo(active))
            eventually("Atomic policy replacement was not observed") { !activity("test.BLOCK") }
            check(activity("test.OTHER") && activity("test.BLOCK", 10002))
            check(service() && broadcast("test.BLOCK"))
            check(!broadcast("test.BROADCAST"))
            check(!activity("test.SIGNATURE", 1000) && activity("test.SIGNATURE", 10002))
            check(permissionCalls.contains("test.permission:123:10001:1000:true"))
            active.writeText("""
                <rules><service block="true">
                  <component-filter name="test.receiver/.Target"/>
                </service></rules>
            """.trimIndent())
            eventually("Close-write policy replacement was not observed") { !service() }
            check(activity("test.BLOCK") && broadcast("test.BROADCAST"))
            check(active.delete())
            eventually("Deleted policy remains active") { service() }
            println("IntentFirewall: matching, permission/signature policy, type isolation and live reload passed")
        } finally {
            temporary.delete()
            active.delete()
            policyThread.quitSafely()
            policyThread.join(5000)
            check(!policyThread.isAlive)
        }
    }

    private fun fileObservation(parent: File) {
        val directory = File(parent, "watch").apply { check(mkdir()) }
        val events = LinkedBlockingQueue<Pair<Int, String?>>()
        val observer = object : FileObserver(directory, CREATE or CLOSE_WRITE or DELETE) {
            override fun onEvent(event: Int, path: String?) { events.add(event to path) }
        }
        val file = File(directory, "observed")
        try {
            observer.startWatching()
            file.writeText("stock inotify")
            eventually("FileObserver did not deliver create and close-write") {
                events.any { it.first and FileObserver.CREATE != 0 && it.second == file.name } &&
                    events.any { it.first and FileObserver.CLOSE_WRITE != 0 && it.second == file.name }
            }
            check(file.delete())
            eventually("FileObserver did not deliver deletion") {
                events.any { it.first and FileObserver.DELETE != 0 && it.second == file.name }
            }
            observer.stopWatching()
            file.writeText("after stop")
            Thread.sleep(100)
            check(events.count { it.first and FileObserver.CREATE != 0 } == 1)
        } finally {
            observer.stopWatching()
            file.delete()
            check(directory.delete())
        }
    }
}
