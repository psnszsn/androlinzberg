package dev.artzig.system

import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
import android.Manifest.permission.UPDATE_DEVICE_STATS
import android.Manifest.permission.WAKE_LOCK
import android.net.DhcpInfo
import android.net.wifi.IWifiManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.WorkSource

/**
 * WifiServiceImpl's disabled-interface queries and WifiLockManager's Binder leases.
 * The host has no Android Wi-Fi interface, supplicant, power mode or multicast filter.
 * Keep permissions, lock identity, work attribution and death cleanup at this seam;
 * the stock WifiManager owns client reference counts and lock limits.
 */
class HostedWifiService(private val permissions: HostedPermissions) :
    IWifiManager.Stub(), IWifiManager by unsupported<IWifiManager>() {
    override fun asBinder(): IBinder = this

    private val lock = Any()
    private val wifiLocks = HashMap<IBinder, Lease>()
    private val multicastLocks = ArrayList<Lease>()

    private inner class Lease(
        val token: IBinder,
        val uid: Int,
        val tag: String?,
        var workSource: WorkSource?,
    ) : IBinder.DeathRecipient {
        override fun binderDied() {
            synchronized(lock) {
                if (wifiLocks[token] === this) wifiLocks.remove(token)
                multicastLocks.remove(this)
            }
        }

        fun link(): Boolean = try {
            token.linkToDeath(this, 0)
            true
        } catch (_: RemoteException) { false }

        fun unlink() { token.unlinkToDeath(this, 0) }
    }

    private fun access() = permissions.enforce(ACCESS_WIFI_STATE, "Wi-Fi state")
    private fun wake() = permissions.enforce(WAKE_LOCK, "Wi-Fi lock")
    private fun multicast() = permissions.enforce(CHANGE_WIFI_MULTICAST_STATE, "Wi-Fi multicast lock")

    override fun getVerboseLoggingLevel(): Int = WifiManager.VERBOSE_LOGGING_LEVEL_DISABLED
    override fun is24GHzBandSupported(): Boolean = false
    override fun is5GHzBandSupported(): Boolean = false
    override fun is6GHzBandSupported(): Boolean = false
    override fun is60GHzBandSupported(): Boolean = false
    override fun isWifiStandardSupported(standard: Int): Boolean = false
    override fun getWifiEnabledState(): Int {
        access()
        return WifiManager.WIFI_STATE_DISABLED
    }

    override fun isFeatureSupported(feature: Int): Boolean {
        access()
        if (feature < 0) throw IndexOutOfBoundsException("bitIndex < 0: $feature")
        return false
    }

    override fun isScanAlwaysAvailable(): Boolean {
        access()
        return false
    }

    override fun getConnectionInfo(callingPackage: String?, callingFeatureId: String?): WifiInfo {
        access()
        permissions.enforceCallerPackage(callingPackage, "Wi-Fi connection info")
        return WifiInfo()
    }

    override fun getDhcpInfo(packageName: String?): DhcpInfo {
        access()
        permissions.enforceCallerPackage(packageName, "Wi-Fi DHCP info")
        return DhcpInfo()
    }

    private fun attribution(source: WorkSource?, packageName: String?): WorkSource =
        if (source == null || source.isEmpty) WorkSource(Binder.getCallingUid(),
            packageName ?: throw NullPointerException("Name can't be null"))
        else WorkSource(source)

    override fun acquireWifiLock(token: IBinder, lockType: Int, tag: String?, ws: WorkSource?,
        packageName: String?, extras: Bundle?): Boolean {
        if (packageName == null) throw NullPointerException("Package name should not be null")
        wake()
        val source = attribution(ws, packageName)
        if (lockType !in WifiManager.WIFI_MODE_FULL..WifiManager.WIFI_MODE_FULL_LOW_LATENCY) {
            throw IllegalArgumentException("lockMode =$lockType")
        }
        synchronized(lock) {
            if (wifiLocks.containsKey(token)) return false
            val lease = Lease(token, Binder.getCallingUid(), tag, source)
            if (!lease.link()) return false
            wifiLocks[token] = lease
            return true
        }
    }

    override fun releaseWifiLock(token: IBinder): Boolean {
        wake()
        val lease = synchronized(lock) { wifiLocks.remove(token) } ?: return false
        lease.unlink()
        return true
    }

    override fun updateWifiLockWorkSource(token: IBinder, ws: WorkSource?,
        packageName: String?, extras: Bundle?) {
        permissions.enforce(UPDATE_DEVICE_STATS, "Wi-Fi work attribution")
        val source = attribution(ws, packageName)
        synchronized(lock) {
            val lease = wifiLocks[token] ?: throw IllegalArgumentException("Wifi lock not active")
            lease.workSource = source
        }
    }

    override fun acquireMulticastLock(token: IBinder, lockTag: String?, attributionTag: String?,
        packageName: String?) {
        multicast()
        synchronized(lock) {
            val lease = Lease(token, Binder.getCallingUid(), lockTag, null)
            if (lease.link()) multicastLocks.add(lease)
        }
    }

    override fun releaseMulticastLock(token: IBinder, lockTag: String?) {
        multicast()
        val uid = Binder.getCallingUid()
        val lease = synchronized(lock) {
            val index = multicastLocks.indexOfLast {
                it.token == token && it.uid == uid && it.tag == lockTag
            }
            if (index < 0) null else multicastLocks.removeAt(index)
        }
        lease?.unlink()
    }

    override fun isMulticastEnabled(): Boolean {
        access()
        // Hosted peers do not have WifiMulticastLockManager's inactive-owner mode.
        return synchronized(lock) { multicastLocks.isNotEmpty() }
    }

    override fun initializeMulticastFiltering() {
        multicast()
        // WifiMulticastLockManager has no interface on which to install a filter.
    }
}
