@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IDeviceIdleController method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedDeviceIdleController : android.os.IDeviceIdleController.Stub() {
    override fun addPowerSaveWhitelistApp(name: String?) { throw UnsupportedOperationException("IDeviceIdleController.addPowerSaveWhitelistApp is not hosted") }
    override fun addPowerSaveWhitelistApps(packageNames: MutableList<String?>?): Int = throw UnsupportedOperationException("IDeviceIdleController.addPowerSaveWhitelistApps is not hosted")
    override fun removePowerSaveWhitelistApp(name: String?) { throw UnsupportedOperationException("IDeviceIdleController.removePowerSaveWhitelistApp is not hosted") }
    override fun removeSystemPowerWhitelistApp(name: String?) { throw UnsupportedOperationException("IDeviceIdleController.removeSystemPowerWhitelistApp is not hosted") }
    override fun restoreSystemPowerWhitelistApp(name: String?) { throw UnsupportedOperationException("IDeviceIdleController.restoreSystemPowerWhitelistApp is not hosted") }
    override fun getRemovedSystemPowerWhitelistApps(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getRemovedSystemPowerWhitelistApps is not hosted")
    override fun getSystemPowerWhitelistExceptIdle(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getSystemPowerWhitelistExceptIdle is not hosted")
    override fun getSystemPowerWhitelist(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getSystemPowerWhitelist is not hosted")
    override fun getUserPowerWhitelist(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getUserPowerWhitelist is not hosted")
    override fun getFullPowerWhitelistExceptIdle(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getFullPowerWhitelistExceptIdle is not hosted")
    override fun getFullPowerWhitelist(): Array<String?>? = throw UnsupportedOperationException("IDeviceIdleController.getFullPowerWhitelist is not hosted")
    override fun getAppIdWhitelistExceptIdle(): IntArray? = throw UnsupportedOperationException("IDeviceIdleController.getAppIdWhitelistExceptIdle is not hosted")
    override fun getAppIdWhitelist(): IntArray? = throw UnsupportedOperationException("IDeviceIdleController.getAppIdWhitelist is not hosted")
    override fun getAppIdUserWhitelist(): IntArray? = throw UnsupportedOperationException("IDeviceIdleController.getAppIdUserWhitelist is not hosted")
    override fun getAppIdTempWhitelist(): IntArray? = throw UnsupportedOperationException("IDeviceIdleController.getAppIdTempWhitelist is not hosted")
    override fun isPowerSaveWhitelistExceptIdleApp(name: String?): Boolean = throw UnsupportedOperationException("IDeviceIdleController.isPowerSaveWhitelistExceptIdleApp is not hosted")
    override fun isPowerSaveWhitelistApp(name: String?): Boolean = throw UnsupportedOperationException("IDeviceIdleController.isPowerSaveWhitelistApp is not hosted")
    override fun addPowerSaveTempWhitelistApp(name: String?, duration: Long, userId: Int, reasonCode: Int, reason: String?) { throw UnsupportedOperationException("IDeviceIdleController.addPowerSaveTempWhitelistApp is not hosted") }
    override fun addPowerSaveTempWhitelistAppForMms(name: String?, userId: Int, reasonCode: Int, reason: String?): Long = throw UnsupportedOperationException("IDeviceIdleController.addPowerSaveTempWhitelistAppForMms is not hosted")
    override fun addPowerSaveTempWhitelistAppForSms(name: String?, userId: Int, reasonCode: Int, reason: String?): Long = throw UnsupportedOperationException("IDeviceIdleController.addPowerSaveTempWhitelistAppForSms is not hosted")
    override fun whitelistAppTemporarily(name: String?, userId: Int, reasonCode: Int, reason: String?): Long = throw UnsupportedOperationException("IDeviceIdleController.whitelistAppTemporarily is not hosted")
    override fun exitIdle(reason: String?) { throw UnsupportedOperationException("IDeviceIdleController.exitIdle is not hosted") }
}
