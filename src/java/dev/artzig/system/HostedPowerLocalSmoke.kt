package dev.artzig.system

import android.os.PowerManagerInternal
import dev.artzig.ArtzigTestDiagnostics
import org.json.JSONObject

object HostedPowerLocalSmoke {
    fun run() {
        val authority = HostedPowerManagerService()
        val local = Class.forName("android.os.HostedPowerManagerInternal")
            .getConstructor(HostedPowerManagerService::class.java).newInstance(authority) as PowerManagerInternal
        check(!authority.isBatterySaverSupported())
        var notifications = 0
        local.registerLowPowerModeObserver(object : PowerManagerInternal.LowPowerModeListener {
            override fun getServiceType(): Int = 3
            override fun onLowPowerModeChanged(state: android.os.PowerSaveState) { notifications++ }
        })
        local.registerLowPowerModeObserver(null)
        check(notifications == 0)
        for (type in intArrayOf(0, 3, 7)) {
            val binderState = checkNotNull(authority.getPowerSaveState(type))
            val localState = local.getLowPowerState(type)
            check(localState.batterySaverEnabled == binderState.batterySaverEnabled)
            check(localState.globalBatterySaverEnabled == binderState.globalBatterySaverEnabled)
            check(localState.brightnessFactor == binderState.brightnessFactor)
        }
        local.setUserActivityTimeoutOverrideFromWindowManager(-1)
        local.setPowerBoost(0, 0)
        local.setPowerMode(PowerManagerInternal.MODE_LAUNCH, true)
        local.setPowerMode(PowerManagerInternal.MODE_LAUNCH, false)
        check(!authority.setPowerModeChecked(PowerManagerInternal.MODE_LAUNCH, true))
        val mark = ArtzigTestDiagnostics.issueSequence()
        check(runCatching { local.setUserActivityTimeoutOverrideFromWindowManager(1000) }
            .exceptionOrNull() is UnsupportedOperationException)
        val issues = JSONObject(ArtzigTestDiagnostics.snapshot(Long.MAX_VALUE, mark)).getJSONArray("issues")
        check(issues.length() == 1)
        check(issues.getJSONObject(0).getString("owner") == "PowerManagerInternal")
        check(issues.getJSONObject(0).getString("member") == "setUserActivityTimeoutOverrideFromWindowManager")
    }
}
