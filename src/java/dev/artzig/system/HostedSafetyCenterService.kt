package dev.artzig.system

import android.os.IBinder
import android.safetycenter.ISafetyCenterManager

/**
 * The mainline SafetyCenter service is not part of the hosted session.
 * Source: pinned SafetyCenterService.isSafetyCenterEnabled, which is false when
 * the device does not support SafetyCenter; its clients (AccessibilityManager-
 * Service.setNonA11yToolNotificationToMatchSafetyCenter among them) then keep
 * their non-SafetyCenter behaviour. Every other call is a structured
 * unsupported diagnostic.
 */
internal class HostedSafetyCenterService : ISafetyCenterManager.Stub(),
    ISafetyCenterManager by unsupported<ISafetyCenterManager>() {
    override fun asBinder(): IBinder = this
    override fun isSafetyCenterEnabled(): Boolean = false
}
