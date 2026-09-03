package dev.artzig.system

import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager

/**
 * The session's ICarrierConfigLoader over the real AIDL surface, published as
 * "carrier_config".
 *
 * Source note: `com.android.phone.CarrierConfigLoader` for a device with no
 * subscriptions: a lookup for any subscription id yields
 * `CarrierConfigManager.getDefaultConfig()` because no phone id is valid, subset
 * lookups project that bundle onto the requested keys, there is no default
 * carrier service package, and the mutators keep AOSP's MODIFY_PHONE_STATE gate
 * and its IllegalArgumentException for an invalid phone id.
 */
class HostedCarrierConfigLoader(private val permissions: HostedTelephonyPermissions) : UnsupportedCarrierConfigLoader() {
    override fun getConfigForSubId(subId: Int, callingPackage: String?): PersistableBundle? = getConfigForSubIdWithFeature(subId, callingPackage, null)

    override fun getConfigForSubIdWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?): PersistableBundle? {
        permissions.enforceReadPhoneState("getCarrierConfig")
        return CarrierConfigManager.getDefaultConfig()
    }

    override fun getConfigSubsetForSubIdWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?, carrierConfigs: Array<String?>?): PersistableBundle? {
        permissions.enforceReadPhoneState("getCarrierConfigSubset")
        val defaults = CarrierConfigManager.getDefaultConfig()
        val subset = PersistableBundle()
        for (key in carrierConfigs ?: return subset) {
            if (key == null) continue
            val value = defaults.get(key) ?: continue
            subset.putObjectAsPersistable(key, value)
        }
        return subset
    }

    override fun getDefaultCarrierServicePackageName(): String? = null

    override fun notifyConfigChangedForSubId(subId: Int) {
        permissions.enforceModifyPhoneState("notifyConfigChangedForSubId")
        throw IllegalArgumentException("Invalid subscriptionId: $subId")
    }

    override fun updateConfigForPhoneId(phoneId: Int, simState: String?) {
        permissions.enforceModifyPhoneState("updateConfigForPhoneId")
        if (!SubscriptionManager.isValidPhoneId(phoneId)) throw IllegalArgumentException("Invalid phoneId: $phoneId")
    }

    override fun overrideConfig(subId: Int, overrides: PersistableBundle?, persistent: Boolean) {
        permissions.enforceModifyPhoneState("overrideConfig")
        throw IllegalArgumentException("Invalid phoneId!")
    }

    private fun PersistableBundle.putObjectAsPersistable(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Double -> putDouble(key, value)
            is String -> putString(key, value)
            is BooleanArray -> putBooleanArray(key, value)
            is IntArray -> putIntArray(key, value)
            is LongArray -> putLongArray(key, value)
            is DoubleArray -> putDoubleArray(key, value)
            is PersistableBundle -> putPersistableBundle(key, value)
            is Array<*> -> @Suppress("UNCHECKED_CAST") putStringArray(key, value as Array<String?>)
        }
    }
}
