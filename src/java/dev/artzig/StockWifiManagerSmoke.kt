package dev.artzig

import android.app.ActivityThread
import android.net.wifi.IWifiManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.WorkSource
import dev.artzig.system.unsupported

/** Stock WifiManager lock lifetimes and generated Binder wire contracts. */
object StockWifiManagerSmoke {
    fun run() {
        val context: android.content.Context = ActivityThread.currentActivityThread().systemContext
        val leases = HashSet<IBinder>()
        val sources = ArrayList<WorkSource?>()
        var acquires = 0
        var releases = 0
        val service = object : IWifiManager.Stub(), IWifiManager by unsupported<IWifiManager>() {
            override fun asBinder(): IBinder = this
            override fun getVerboseLoggingLevel(): Int = 0
            override fun getWifiEnabledState(): Int = WifiManager.WIFI_STATE_DISABLED
            override fun getConnectionInfo(packageName: String?, featureId: String?): WifiInfo = WifiInfo()
            override fun isFeatureSupported(feature: Int): Boolean = false
            override fun is5GHzBandSupported(): Boolean = false
            override fun acquireWifiLock(token: IBinder, mode: Int, tag: String?, ws: WorkSource?,
                packageName: String?, extras: Bundle?): Boolean {
                check(packageName == context.opPackageName)
                // AttributionSource may only be decoded inside a native Binder transaction.
                check(extras!!.containsKey(WifiManager.EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE))
                acquires++
                sources.add(ws?.let(::WorkSource))
                check(leases.add(token))
                return true
            }
            override fun releaseWifiLock(token: IBinder): Boolean {
                releases++
                return leases.remove(token)
            }
            override fun updateWifiLockWorkSource(token: IBinder, ws: WorkSource?,
                packageName: String?, extras: Bundle?) {
                check(token in leases)
                sources.add(ws?.let(::WorkSource))
            }
        }
        // Force the generated Proxy/Stub, including WorkSource and the attribution envelope.
        val relay = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.transact(code, data, reply, flags)
        }
        val manager = WifiManager(context, IWifiManager.Stub.asInterface(relay))
        check(!manager.isWifiEnabled && manager.wifiState == WifiManager.WIFI_STATE_DISABLED)
        check(manager.connectionInfo.networkId == -1 && !manager.is5GHzBandSupported)
        val lease = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "reference-counted")
        val attribution = WorkSource(10001, "named")
        lease.setWorkSource(attribution)
        lease.acquire()
        lease.acquire()
        check(lease.isHeld && acquires == 1 && leases.size == 1)
        check(sources.single()!!.get(0) == 10001 && sources.single()!!.getName(0) == null)
        check(attribution.getName(0) == "named")
        lease.setWorkSource(WorkSource(10002))
        check(sources.last()!!.get(0) == 10002)
        lease.release()
        check(lease.isHeld && releases == 0)
        lease.release()
        check(!lease.isHeld && releases == 1 && leases.isEmpty())
        check(runCatching { lease.release() }.exceptionOrNull() is RuntimeException)

        val simple = manager.createWifiLock("not-reference-counted")
        simple.setReferenceCounted(false)
        simple.acquire()
        simple.acquire()
        check(acquires == 2 && simple.isHeld)
        simple.release()
        simple.release()
        check(releases == 2 && !simple.isHeld && leases.isEmpty())

        // The public client limit is shared across held locks; overflow rolls back the RPC.
        val held = (0 until 50).map { manager.createWifiLock("limit-$it").apply { acquire() } }
        val overflow = manager.createWifiLock("overflow")
        check(runCatching { overflow.acquire() }.exceptionOrNull() is UnsupportedOperationException)
        check(!overflow.isHeld && leases.size == 50)
        held.forEach { it.release() }
        check(leases.isEmpty())
        manager.createWifiLock("after-limit").apply { acquire(); release() }
        check(leases.isEmpty())

        val request = WifiNetworkSpecifier.Builder().setSsid("fixture")
            .setWpa2Passphrase("fixture-password").build()
        val parcel = Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            check(WifiNetworkSpecifier.CREATOR.createFromParcel(parcel) == request)
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }
        check(runCatching { WifiNetworkSpecifier.Builder().build() }.exceptionOrNull()
            is IllegalStateException)
    }
}
