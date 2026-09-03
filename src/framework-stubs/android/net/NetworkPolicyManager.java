/* artzig stub of AOSP android.net.NetworkPolicyManager: the network policy service
 * (INetworkPolicyManager, NetworkTemplate/NetworkStats from the Connectivity module)
 * does not exist on this host. SubscriptionManager's subscription-plan and override
 * calls reach it; with no policy service there are no plans and overrides are
 * accepted and dropped, as NetworkPolicyManagerService does for an unknown subId. */
package android.net;

import android.content.Context;
import android.telephony.SubscriptionPlan;

public class NetworkPolicyManager {
    public static final int SUBSCRIPTION_OVERRIDE_UNMETERED = 1 << 0;
    public static final int SUBSCRIPTION_OVERRIDE_CONGESTED = 1 << 1;

    public NetworkPolicyManager(Context context) {}

    public SubscriptionPlan[] getSubscriptionPlans(int subId, String callingPackage) {
        return new SubscriptionPlan[0];
    }

    public void setSubscriptionPlans(int subId, SubscriptionPlan[] plans, long expirationDurationMillis,
            String callingPackage) {}

    public void setSubscriptionOverride(int subId, int overrideMask, int overrideValue, int[] networkTypes,
            long expirationDurationMillis, String callingPackage) {}
}
