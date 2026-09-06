package dev.artzig

import android.app.ActivityThread
import android.net.MacAddress
import android.net.wifi.p2p.IWifiP2pListener
import android.net.wifi.p2p.IWifiP2pManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Parcelable

/** Stock Wi-Fi Direct wire formats and client callbacks, without a radio or supplicant. */
object StockWifiP2pSmoke {
    fun run(drain: () -> Unit) {
        val address = "fa:7b:7a:42:02:13"
        val config = WifiP2pConfig.Builder()
            .setDeviceAddress(MacAddress.fromString(address))
            .setNetworkName("DIRECT-ab-Printer")
            .setPassphrase("print-secret")
            .setGroupOperatingFrequency(2412)
            .enablePersistentMode(true)
            .build()
        val restored = parcelCopy(config, WifiP2pConfig.CREATOR)
        check(restored.deviceAddress == address && restored.networkName == "DIRECT-ab-Printer")
        check(restored.passphrase == "print-secret" && restored.groupOwnerBand == 2412)
        check(restored.networkId == config.networkId)
        check(runCatching { WifiP2pConfig.Builder().build() }.exceptionOrNull() is IllegalStateException)
        check(runCatching { WifiP2pConfig.Builder().setNetworkName("invalid") }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            WifiP2pConfig.Builder().setNetworkName("DIRECT-ab").setPassphrase("short").build()
        }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            WifiP2pConfig.Builder().setDeviceAddress(MacAddress.fromString(address))
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .setGroupOperatingFrequency(2412).build()
        }.exceptionOrNull() is IllegalStateException)

        val peer = WifiP2pDevice("P2P-DEVICE-FOUND $address p2p_dev_addr=$address " +
            "pri_dev_type=1-0050F204-1 name='Printer' config_methods=0x188 dev_capab=0x27 group_capab=0x1")
        check(peer.deviceName == "Printer" && peer.deviceAddress == address)
        check(peer.wpsPbcSupported() && peer.wpsDisplaySupported() && peer.wpsKeypadSupported())
        check(peer.isServiceDiscoveryCapable && peer.isGroupOwner)
        val peers = WifiP2pDeviceList().apply { update(peer) }
        val snapshot = parcelCopy(peers, WifiP2pDeviceList.CREATOR)
        peers.update(WifiP2pDevice(peer).apply { deviceName = "Renamed" })
        check(peers.get(address).deviceName == "Renamed")
        check(snapshot.get(address).deviceName == "Printer" && snapshot.isGroupOwner(address))
        check(runCatching { peers.update(WifiP2pDevice()) }.exceptionOrNull() is IllegalArgumentException)

        val request = WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR, "abcd")
        request.transactionId = 7
        check(request.supplicantQuery == "04000107abcd")
        check(parcelCopy(request, WifiP2pServiceRequest.CREATOR).supplicantQuery == request.supplicantQuery)
        check(runCatching { WifiP2pServiceRequest.newInstance(1, "abc") }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { WifiP2pServiceRequest.newInstance(1, "xy") }.exceptionOrNull() is IllegalArgumentException)
        val dns = WifiP2pDnsSdServiceInfo.newInstance("Printer", "_ipp._tcp", mapOf("note" to "desk"))
        check(dns.supplicantQueryList == listOf(
            "bonjour 045f697070c00c000c01 075072696e746572c027",
            "bonjour 077072696e746572045f697070c00c001001 096e6f74653d6465736b",
        ))
        check(parcelCopy(dns, WifiP2pServiceInfo.CREATOR).supplicantQueryList == dns.supplicantQueryList)
        testClient(drain)
    }

    private fun testClient(drain: () -> Unit) {
        val context: android.content.Context = ActivityThread.currentActivityThread().systemContext
        val main = Looper.getMainLooper()
        val requests = ArrayList<Int>()
        val handler = Handler(main) { message ->
            requests.add(message.what)
            if (message.what == WifiP2pManager.DISCOVER_PEERS) {
                // The pinned WifiP2pServiceImpl.P2pNotSupportedState returns this error.
                // Duplicate replies must not invoke an already-removed action listener.
                repeat(2) {
                    message.replyTo.send(Message.obtain().apply {
                        what = WifiP2pManager.DISCOVER_PEERS_FAILED
                        arg1 = WifiP2pManager.P2P_UNSUPPORTED
                        arg2 = message.arg2
                    })
                }
            }
            true
        }
        var token: IBinder? = null
        var closed: IBinder? = null
        var denied = false
        var features = 0L
        var missingMessenger = false
        val service = object : IWifiP2pManager.Stub() {
            override fun getMessenger(binder: IBinder, packageName: String, extras: Bundle): Messenger? {
                if (denied) throw SecurityException("test Wi-Fi permission denial")
                check(packageName == context.opPackageName)
                check(extras.getInt(WifiP2pManager.EXTRA_PARAM_KEY_DISPLAY_ID) == context.displayId)
                token = binder
                return if (missingMessenger) null else Messenger(handler)
            }
            override fun getP2pStateMachineMessenger(): Messenger = Messenger(handler)
            override fun close(binder: IBinder) { closed = binder }
            override fun setMiracastMode(mode: Int) { throw SecurityException("test display permission denial") }
            override fun checkConfigureWifiDisplayPermission() { throw SecurityException("test display permission denial") }
            override fun getSupportedFeatures(): Long = features
            override fun registerWifiP2pListener(listener: IWifiP2pListener, packageName: String, extras: Bundle) = error("unexpected listener registration")
            override fun unregisterWifiP2pListener(listener: IWifiP2pListener) = error("unexpected listener removal")
        }
        // Exercise generated Proxy/Stub parcels even though the fixture lives in this process.
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.asBinder().transact(code, data, reply, flags)
        }
        val manager = WifiP2pManager(IWifiP2pManager.Stub.asInterface(remote))
        check(!manager.isSetVendorElementsSupported && !manager.isGroupClientRemovalSupported)
        features = WifiP2pManager.FEATURE_SET_VENDOR_ELEMENTS
        check(manager.isSetVendorElementsSupported && !manager.isGroupClientRemovalSupported)
        val channel = checkNotNull(manager.initialize(context, main, null))
        try {
            val failures = ArrayList<Int>()
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = error("an unavailable radio cannot discover peers")
                override fun onFailure(reason: Int) {
                    check(Looper.myLooper() === main)
                    failures.add(reason)
                }
            })
            check(failures.isEmpty())
            drain()
            check(requests.take(2) == listOf(WifiP2pManager.UPDATE_CHANNEL_INFO, WifiP2pManager.DISCOVER_PEERS))
            check(failures == listOf(WifiP2pManager.P2P_UNSUPPORTED))
            check(runCatching { manager.setMiracastMode(1) }.exceptionOrNull() is SecurityException)
        } finally {
            channel.close()
            drain()
        }
        check(closed === token)
        missingMessenger = true
        check(manager.initialize(context, main, null) == null)
        denied = true
        check(runCatching { manager.initialize(context, main, null) }.exceptionOrNull() is SecurityException)
    }

    private fun <T : Parcelable> parcelCopy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
