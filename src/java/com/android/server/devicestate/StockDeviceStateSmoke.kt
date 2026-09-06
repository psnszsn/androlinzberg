package com.android.server.devicestate

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.devicestate.DeviceStateInfo
import android.hardware.devicestate.IDeviceStateManagerCallback
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import dev.artzig.system.HostedDesktopDeviceStatePolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stock service API with in-memory permissions/broadcasts; no global service replacement. */
object StockDeviceStateSmoke {
    fun run(base: Context) {
        var permitted = true
        var receiverRegistrations = 0
        val context = object : ContextWrapper(base) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
                permission: String?, scheduler: Handler?, flags: Int): Intent? {
                check(permission == Manifest.permission.CONTROL_DEVICE_STATE)
                check(flags == Context.RECEIVER_NOT_EXPORTED)
                check(filter.hasAction("com.android.server.devicestate.INTENT_ACTION_CANCEL_STATE"))
                receiverRegistrations++
                return null
            }
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
                check(permission == Manifest.permission.CONTROL_DEVICE_STATE)
                if (!permitted) throw SecurityException(message)
            }
        }
        val initialized = CountDownLatch(1)
        val service = DeviceStateManagerService(context, HostedDesktopDeviceStatePolicy(context)) {
            check(it == "0:DEFAULT")
            initialized.countDown()
        }
        check(initialized.await(5, TimeUnit.SECONDS))
        check(receiverRegistrations == 1)
        val api = service.binderService
        fun assertDefault(info: DeviceStateInfo) {
            check(info.baseState.identifier == 0 && info.currentState.identifier == 0)
            check(info.supportedStates.size == 1 && info.supportedStates[0].identifier == 0)
        }
        fun drain() {
            val done = CountDownLatch(1)
            check(service.handler.post { done.countDown() })
            check(done.await(5, TimeUnit.SECONDS))
        }
        assertDefault(api.deviceStateInfo)
        check(runCatching { api.registerCallback(null) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { api.requestBaseStateOverride(Binder(), 0, 0) }
            .exceptionOrNull() is IllegalStateException)
        val callback = Callback()
        val initialInfo = api.registerCallback(callback)
        initialInfo?.let(::assertDefault)
        drain()
        check(callback.infos.size + (if (initialInfo == null) 0 else 1) == 1)
        callback.infos.forEach(::assertDefault)
        check(callback.death != null)
        check(runCatching { api.registerCallback(Callback()) }.exceptionOrNull() is SecurityException)
        check(runCatching { api.requestBaseStateOverride(null, 0, 0) }
            .exceptionOrNull() is IllegalArgumentException)
        check(runCatching { api.requestBaseStateOverride(Binder(), 1, 0) }
            .exceptionOrNull() is IllegalArgumentException)
        val token = Binder()
        api.requestBaseStateOverride(token, 0, 0)
        drain()
        check(callback.active == listOf(token))
        check(runCatching { api.requestBaseStateOverride(token, 0, 0) }
            .exceptionOrNull() is IllegalStateException)
        api.cancelBaseStateOverride()
        drain()
        assertDefault(api.deviceStateInfo)
        permitted = false
        check(runCatching { api.requestBaseStateOverride(Binder(), 0, 0) }
            .exceptionOrNull() is SecurityException)
        check(runCatching { api.cancelBaseStateOverride() }.exceptionOrNull() is SecurityException)
        permitted = true
        checkNotNull(callback.death).binderDied()
        drain()
        check(runCatching { api.requestBaseStateOverride(Binder(), 0, 0) }
            .exceptionOrNull() is IllegalStateException)
        val dead = Callback(failLink = true)
        check(runCatching { api.registerCallback(dead) }.exceptionOrNull()?.cause is RemoteException)
        val replacement = Callback()
        api.registerCallback(replacement)?.let(::assertDefault)
        drain()
        checkNotNull(replacement.death).binderDied()
        drain()
    }

    private class Callback(private val failLink: Boolean = false) : IDeviceStateManagerCallback.Stub() {
        var death: IBinder.DeathRecipient? = null
        val infos = mutableListOf<DeviceStateInfo>()
        val active = mutableListOf<IBinder>()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink) throw RemoteException("device-state fixture already dead")
            death = recipient
        }
        override fun onDeviceStateInfoChanged(info: DeviceStateInfo) { infos.add(info) }
        override fun onRequestActive(token: IBinder) { active.add(token) }
        override fun onRequestCanceled(token: IBinder) {}
    }
}
