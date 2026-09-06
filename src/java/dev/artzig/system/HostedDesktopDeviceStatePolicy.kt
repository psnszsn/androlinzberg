package dev.artzig.system

import android.content.Context
import android.hardware.devicestate.DeviceState
import com.android.server.devicestate.DeviceStatePolicy
import com.android.server.devicestate.DeviceStateProvider
import java.io.PrintWriter

/**
 * DeviceStateProviderImpl's default configuration and listener ordering, with
 * DeviceStatePolicyImpl's immediate configuration completion. The desktop has
 * no Android sensor/lid-switch provider; it has one fixed, non-folding posture.
 * The stock DeviceStateManagerService owns requests, permissions and callbacks.
 */
class HostedDesktopDeviceStatePolicy(context: Context) : DeviceStatePolicy(context) {
    private val provider = DesktopProvider()

    override fun getDeviceStateProvider(): DeviceStateProvider = provider

    override fun configureDeviceForState(state: Int, onComplete: Runnable) {
        require(state == 0) { "Unsupported desktop device state: $state" }
        onComplete.run()
    }

    override fun dump(writer: PrintWriter, args: Array<String>?) = provider.dump(writer, args)

    private class DesktopProvider : DeviceStateProvider {
        private val state = DeviceState(DeviceState.Configuration.Builder(0, "DEFAULT").build())
        private var listener: DeviceStateProvider.Listener? = null

        override fun setListener(listener: DeviceStateProvider.Listener?) {
            synchronized(this) {
                if (this.listener != null) throw RuntimeException("Provider already has a listener set.")
                this.listener = listener
            }
            if (listener == null) return
            listener.onSupportedDeviceStatesChanged(arrayOf(state),
                DeviceStateProvider.SUPPORTED_DEVICE_STATES_CHANGED_INITIALIZED)
            listener.onStateChanged(state.identifier)
        }

        override fun dump(writer: PrintWriter, args: Array<String>?) {
            writer.println("Desktop device state: $state (fixed posture)")
        }
    }
}
