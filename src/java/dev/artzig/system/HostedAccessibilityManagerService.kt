package dev.artzig.system

import android.content.pm.ParceledListSlice
import android.graphics.Color
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.IAccessibilityManager
import android.view.accessibility.IAccessibilityManagerClient
import com.android.internal.util.IntPair

/**
 * The system-server half of the stock [AccessibilityManager] client.
 *
 * Android normally obtains this state from AccessibilityManagerService. The
 * hosted runtime has one always-enabled, in-process accessibility consumer:
 * the headless test controller. Keep that policy behind the real Binder
 * contract so application code observes the stock framework client.
 */
class HostedAccessibilityManagerService :
    IAccessibilityManager.Stub(),
    IAccessibilityManager by unsupported<IAccessibilityManager>() {
    override fun asBinder() = this

    // AccessibilityManager.tryConnectToServiceLocked decodes the low word as
    // state and the high word as relevant event types (AOSP IntPair layout).
    override fun addClient(client: IAccessibilityManagerClient?, userId: Int): Long =
        IntPair.of(
            AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED,
            AccessibilityEvent.TYPES_ALL_MASK,
        )

    override fun removeClient(client: IAccessibilityManagerClient?, userId: Int): Boolean = true

    override fun sendAccessibilityEvent(event: AccessibilityEvent?, userId: Int) {
        android.view.ArtzigAccessibilityBridge.markAccessibilityEvent(event)
    }

    override fun getInstalledAccessibilityServiceList(userId: Int) =
        ParceledListSlice(emptyList<android.accessibilityservice.AccessibilityServiceInfo>())

    override fun getEnabledAccessibilityServiceList(feedbackType: Int, userId: Int) =
        emptyList<android.accessibilityservice.AccessibilityServiceInfo>()

    override fun getRecommendedTimeoutMillis(): Long = IntPair.of(0, 0)

    override fun getFocusStrokeWidth(): Int = 0

    override fun getFocusColor(): Int = Color.TRANSPARENT
}
