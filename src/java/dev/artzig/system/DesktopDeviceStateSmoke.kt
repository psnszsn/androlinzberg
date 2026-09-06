package dev.artzig.system

import android.content.Context
import android.hardware.devicestate.DeviceState
import com.android.server.devicestate.DeviceStatePolicy
import com.android.server.devicestate.DeviceStateProvider

object DesktopDeviceStateSmoke {
    fun run(context: Context) {
        // Exercise the real resource factory too: a stock service must select
        // the desktop provider without a second service-specific configuration.
        val policy = DeviceStatePolicy.Provider.fromResources(context.resources).instantiate(context)
        check(policy is HostedDesktopDeviceStatePolicy)
        val events = mutableListOf<String>()
        val listener = object : DeviceStateProvider.Listener {
            override fun onSupportedDeviceStatesChanged(states: Array<DeviceState>, reason: Int) {
                check(reason == DeviceStateProvider.SUPPORTED_DEVICE_STATES_CHANGED_INITIALIZED)
                check(states.size == 1 && states[0].identifier == 0)
                check(states[0].configuration.systemProperties.isEmpty())
                check(states[0].configuration.physicalProperties.isEmpty())
                events.add("supported")
            }
            override fun onStateChanged(identifier: Int) {
                check(identifier == 0)
                check(events == listOf("supported"))
                events.add("state")
            }
        }
        val provider = policy.deviceStateProvider
        provider.setListener(null) // Stock provider does not consume registration on null.
        provider.setListener(listener)
        check(events == listOf("supported", "state"))
        check(runCatching { provider.setListener(listener) }.exceptionOrNull()?.message ==
            "Provider already has a listener set.")
        provider.onSystemReady()
        check(events == listOf("supported", "state"))
        var completions = 0
        policy.configureDeviceForState(0) { completions++ }
        check(completions == 1)
        check(runCatching { policy.configureDeviceForState(1) { completions++ } }
            .exceptionOrNull() is IllegalArgumentException)
        check(completions == 1)
    }
}
