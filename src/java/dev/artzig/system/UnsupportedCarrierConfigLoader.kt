@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ICarrierConfigLoader method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedCarrierConfigLoader : com.android.internal.telephony.ICarrierConfigLoader.Stub() {
    override fun getConfigForSubId(subId: Int, callingPackage: String?): android.os.PersistableBundle? = throw UnsupportedOperationException("ICarrierConfigLoader.getConfigForSubId is not hosted")
    override fun getConfigForSubIdWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?): android.os.PersistableBundle? = throw UnsupportedOperationException("ICarrierConfigLoader.getConfigForSubIdWithFeature is not hosted")
    override fun overrideConfig(subId: Int, overrides: android.os.PersistableBundle?, persistent: Boolean) { throw UnsupportedOperationException("ICarrierConfigLoader.overrideConfig is not hosted") }
    override fun notifyConfigChangedForSubId(subId: Int) { throw UnsupportedOperationException("ICarrierConfigLoader.notifyConfigChangedForSubId is not hosted") }
    override fun updateConfigForPhoneId(phoneId: Int, simState: String?) { throw UnsupportedOperationException("ICarrierConfigLoader.updateConfigForPhoneId is not hosted") }
    override fun getDefaultCarrierServicePackageName(): String? = throw UnsupportedOperationException("ICarrierConfigLoader.getDefaultCarrierServicePackageName is not hosted")
    override fun getConfigSubsetForSubIdWithFeature(subId: Int, callingPackage: String?, callingFeatureId: String?, carrierConfigs: Array<String?>?): android.os.PersistableBundle? = throw UnsupportedOperationException("ICarrierConfigLoader.getConfigSubsetForSubIdWithFeature is not hosted")
}
