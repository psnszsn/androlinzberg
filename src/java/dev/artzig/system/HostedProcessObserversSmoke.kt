package dev.artzig.system

import android.app.IProcessObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HostedProcessObserversSmoke {
    fun run() {
        val thread = HandlerThread("process-observers-fixture").apply { start() }
        val handler = Handler(thread.looper)
        val observers = HostedProcessObservers(handler)
        fun drain() {
            val done = CountDownLatch(1)
            check(handler.post { done.countDown() })
            check(done.await(5, TimeUnit.SECONDS))
        }
        fun batch(action: () -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            check(handler.post { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            check(entered.await(5, TimeUnit.SECONDS))
            try { action() } finally { release.countDown() }
            drain()
        }
        try {
            val first = Observer()
            observers.register(first)
            observers.register(first)
            observers.started(42, 10001, 10002, "example", "example:worker")
            check(first.events == if (android.app.Flags.enableProcessObserverBroadcastOnProcessStarted())
                listOf("start:42:10001:10002:example:example:worker") else emptyList<String>())
            first.events.clear()
            // Stock batches updates by PID and delivers activities before services.
            batch {
                observers.activitiesChanged(42, 10001, true)
                observers.servicesChanged(42, 10001, 3)
                observers.activitiesChanged(42, 10001, false)
                observers.servicesChanged(42, 10001, 5)
                observers.activitiesChanged(43, 10002, true)
            }
            check(first.events == listOf("activities:42:10001:false", "services:42:10001:5",
                "activities:43:10002:true"))
            first.events.clear()
            batch {
                observers.activitiesChanged(42, 10001, true)
                observers.died(42, 10001)
            }
            check(first.events == listOf("death:42:10001"))
            observers.unregister(first)
            observers.died(43, 10002)
            drain()
            check(first.events.size == 1)

            val dead = Observer()
            observers.register(dead)
            checkNotNull(dead.death).binderDied()
            val failedLink = Observer(failLink = true)
            observers.register(failedLink)
            val alive = Observer()
            observers.register(alive)
            val failingCallback = Observer(failCallback = true)
            observers.register(failingCallback)
            observers.died(44, 10003)
            drain()
            check(dead.events.isEmpty() && failedLink.events.isEmpty())
            check(alive.events == listOf("death:44:10003"))
            observers.unregister(alive)
            observers.unregister(failingCallback)

            // The public and generated local interfaces share AMS's permission gate.
            val denied = HostedActivityManagerService()
            check(runCatching { denied.registerProcessObserver(Observer()) }
                .exceptionOrNull() is SecurityException)
            val access = HostActivityManagerInternalAccess(denied, HostedPackageManagerService(),
                HostedUserLifecycle())
            val local = Class.forName("android.app.HostedActivityManagerInternal")
                .getConstructor(HostActivityManagerInternalAccess::class.java)
                .newInstance(access) as android.app.ActivityManagerInternal
            check(runCatching { local.registerProcessObserver(Observer()) }
                .exceptionOrNull() is SecurityException)
            denied.unregisterProcessObserver(Observer())
            local.unregisterProcessObserver(Observer())
        } finally {
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive)
        }
    }

    private class Observer(private val failLink: Boolean = false,
        private val failCallback: Boolean = false) : IProcessObserver.Stub() {
        var death: IBinder.DeathRecipient? = null
        val events = mutableListOf<String>()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink) throw RemoteException("observer already dead")
            death = recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (death !== recipient) return false
            death = null
            return true
        }
        override fun onProcessStarted(pid: Int, processUid: Int, packageUid: Int,
            packageName: String, processName: String) {
            events.add("start:$pid:$processUid:$packageUid:$packageName:$processName")
        }
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean) {
            events.add("activities:$pid:$uid:$foregroundActivities")
        }
        override fun onForegroundServicesChanged(pid: Int, uid: Int, serviceTypes: Int) {
            events.add("services:$pid:$uid:$serviceTypes")
        }
        override fun onProcessDied(pid: Int, uid: Int) {
            if (failCallback) throw RemoteException("observer died during callback")
            events.add("death:$pid:$uid")
        }
    }
}
