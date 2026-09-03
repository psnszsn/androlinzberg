package dev.artzig.system

import android.os.Binder
import android.os.Process

/**
 * The session's device-idle controller over the real `IDeviceIdleController`
 * surface, published as `Context.DEVICE_IDLE_CONTROLLER` (`"deviceidle"`),
 * behind the real `DeviceIdleManager` and `PowerExemptionManager`.
 *
 * Source note: `com.android.server.DeviceIdleController.BinderService` for a
 * host that never enters idle or light idle: the power-save allowlists are
 * empty (nothing needs exempting from a doze that never happens), temporary
 * allowlisting is accepted and has no effect, and the mutating calls keep the
 * AOSP DEVICE_POWER permission check.
 */
class HostedDeviceIdleController : UnsupportedDeviceIdleController() {
    private fun enforceDevicePower(what: String) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("$what requires android.permission.DEVICE_POWER")
        }
    }

    private val lock = Any()
    private val userAllowlist = LinkedHashSet<String>()

    override fun addPowerSaveWhitelistApp(name: String?) {
        enforceDevicePower("addPowerSaveWhitelistApp")
        synchronized(lock) { userAllowlist.add(name ?: return) }
    }
    override fun addPowerSaveWhitelistApps(packageNames: MutableList<String?>?): Int {
        enforceDevicePower("addPowerSaveWhitelistApps")
        synchronized(lock) { packageNames?.filterNotNull()?.let { userAllowlist.addAll(it) } }
        return 0
    }
    override fun removePowerSaveWhitelistApp(name: String?) {
        enforceDevicePower("removePowerSaveWhitelistApp")
        synchronized(lock) { userAllowlist.remove(name) }
    }
    override fun removeSystemPowerWhitelistApp(name: String?) = enforceDevicePower("removeSystemPowerWhitelistApp")
    override fun restoreSystemPowerWhitelistApp(name: String?) = enforceDevicePower("restoreSystemPowerWhitelistApp")
    override fun getRemovedSystemPowerWhitelistApps(): Array<String?>? = arrayOfNulls(0)
    override fun getSystemPowerWhitelistExceptIdle(): Array<String?>? = arrayOfNulls(0)
    override fun getSystemPowerWhitelist(): Array<String?>? = arrayOfNulls(0)
    override fun getUserPowerWhitelist(): Array<String?>? = synchronized(lock) { userAllowlist.toTypedArray<String?>() }
    override fun getFullPowerWhitelistExceptIdle(): Array<String?>? = getUserPowerWhitelist()
    override fun getFullPowerWhitelist(): Array<String?>? = getUserPowerWhitelist()
    override fun getAppIdWhitelistExceptIdle(): IntArray? = IntArray(0)
    override fun getAppIdWhitelist(): IntArray? = IntArray(0)
    override fun getAppIdUserWhitelist(): IntArray? = IntArray(0)
    override fun getAppIdTempWhitelist(): IntArray? = IntArray(0)
    override fun isPowerSaveWhitelistExceptIdleApp(name: String?): Boolean = synchronized(lock) { name in userAllowlist }
    override fun isPowerSaveWhitelistApp(name: String?): Boolean = synchronized(lock) { name in userAllowlist }
    override fun addPowerSaveTempWhitelistApp(name: String?, duration: Long, userId: Int, reasonCode: Int, reason: String?) {
        enforceDevicePower("addPowerSaveTempWhitelistApp")
    }
    override fun addPowerSaveTempWhitelistAppForMms(name: String?, userId: Int, reasonCode: Int, reason: String?): Long {
        enforceDevicePower("addPowerSaveTempWhitelistAppForMms")
        return 0
    }
    override fun addPowerSaveTempWhitelistAppForSms(name: String?, userId: Int, reasonCode: Int, reason: String?): Long {
        enforceDevicePower("addPowerSaveTempWhitelistAppForSms")
        return 0
    }
    override fun whitelistAppTemporarily(name: String?, userId: Int, reasonCode: Int, reason: String?): Long {
        enforceDevicePower("whitelistAppTemporarily")
        return 0
    }
    override fun exitIdle(reason: String?) = enforceDevicePower("exitIdle")
}
