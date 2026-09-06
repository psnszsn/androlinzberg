package com.android.server.stats

import android.app.ActivityThread
import android.os.Binder
import android.os.IPullAtomCallback
import android.os.IPullAtomResultReceiver
import android.os.IStatsd
import dev.artzig.system.unsupported

/** Exercises the stock service's offline callback cache and daemon reconnect protocol. */
object StockStatsManagerSmoke {
    @JvmStatic fun run() {
        val context: android.content.Context = ActivityThread.currentActivityThread().systemContext
        val allowedContext = object : android.content.ContextWrapper(context) {
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
                check(permission == android.Manifest.permission.REGISTER_STATS_PULL_ATOM)
            }
        }
        val service = StatsManagerService(allowedContext)
        val registrations = ArrayList<String>()
        val removals = ArrayList<Pair<Int, Int>>()
        var completed = 0
        var pulled = 0
        val callback = object : IPullAtomCallback.Stub() {
            override fun onPullAtom(atomTag: Int, result: IPullAtomResultReceiver) { pulled++ }
        }
        val daemon = object : IStatsd.Stub(), IStatsd by unsupported<IStatsd>() {
            override fun asBinder(): android.os.IBinder = this
            override fun registerPullAtomCallback(uid: Int, atomTag: Int, coolDownMillis: Long,
                timeoutMillis: Long, additiveFields: IntArray, pullerCallback: IPullAtomCallback) {
                check(uid == Binder.getCallingUid())
                check(pullerCallback.asBinder() === callback.asBinder())
                registrations.add("$atomTag:$coolDownMillis:$timeoutMillis:${additiveFields.toList()}")
            }
            override fun allPullersFromBootRegistered() { completed++ }
            override fun unregisterPullAtomCallback(uid: Int, atomTag: Int) { removals.add(uid to atomTag) }
        }
        val deniedContext = object : android.content.ContextWrapper(context) {
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
                check(permission == android.Manifest.permission.REGISTER_STATS_PULL_ATOM)
                throw SecurityException("test permission denial")
            }
        }
        val denied = StatsManagerService(deniedContext)
        check(runCatching {
            denied.registerPullAtomCallback(10000, 1000, 2000, intArrayOf(), callback)
        }.exceptionOrNull() is SecurityException)
        denied.statsdReady(daemon)
        check(registrations.isEmpty() && completed == 1)
        denied.statsdNotReady()
        completed = 0
        service.registerPullAtomCallback(10001, 1000, 2000, intArrayOf(2), callback)
        service.registerPullAtomCallback(10002, 3000, 4000, intArrayOf(), callback)
        service.unregisterPullAtomCallback(10002)
        check(registrations.isEmpty())
        service.statsdReady(daemon)
        check(registrations == listOf("10001:1000:2000:[2]") && completed == 1)
        service.registerPullAtomCallback(10001, 5000, 6000, intArrayOf(3), callback)
        check(registrations.last() == "10001:5000:6000:[3]")
        service.statsdNotReady()
        service.registerPullAtomCallback(10003, 7000, 8000, intArrayOf(4), callback)
        service.unregisterPullAtomCallback(10001)
        check(removals.isEmpty())
        registrations.clear()
        service.statsdReady(daemon)
        check(registrations == listOf("10003:7000:8000:[4]") && completed == 2)
        service.unregisterPullAtomCallback(10003)
        check(removals == listOf(Binder.getCallingUid() to 10003))
        service.statsdNotReady()
        registrations.clear()
        service.statsdReady(daemon)
        check(registrations.isEmpty() && completed == 3 && pulled == 0)
    }
}
