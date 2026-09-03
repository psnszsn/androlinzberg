package dev.artzig.system

import android.Manifest
import android.content.om.OverlayConstraint
import android.content.om.OverlayIdentifier
import android.content.om.OverlayInfo

/**
 * The session's IOverlayManager over the real AIDL surface, published as
 * `Context.OVERLAY_SERVICE`.
 *
 * Source note: `com.android.server.om.OverlayManagerService` for a device with no
 * runtime resource overlays installed: every query is empty or null, and the
 * mutators keep the CHANGE_OVERLAY_PACKAGES gate and report false for an unknown
 * overlay, as `OverlayManagerServiceImpl` does. Fabricated (self-targeting)
 * overlay transactions need idmap2, which is not hosted, so `commit` stays unserved.
 */
class HostedOverlayManagerService(private val permissions: HostedPermissions) : UnsupportedOverlayManager() {
    private fun enforceChange(what: String) = permissions.enforce(Manifest.permission.CHANGE_OVERLAY_PACKAGES, what)

    override fun getAllOverlays(userId: Int): MutableMap<String?, MutableList<OverlayInfo?>?>? = HashMap()
    override fun getOverlayInfosForTarget(targetPackageName: String?, userId: Int): MutableList<OverlayInfo?>? = ArrayList()
    override fun getOverlayInfo(packageName: String?, userId: Int): OverlayInfo? = null
    override fun getOverlayInfoByIdentifier(packageName: OverlayIdentifier?, userId: Int): OverlayInfo? = null
    override fun setEnabled(packageName: String?, enable: Boolean, userId: Int): Boolean {
        enforceChange("setEnabled")
        return false
    }
    override fun enableWithConstraints(packageName: String?, userId: Int, constraints: MutableList<OverlayConstraint?>?): Boolean {
        enforceChange("enableWithConstraints")
        return false
    }
    override fun setEnabledExclusive(packageName: String?, enable: Boolean, userId: Int): Boolean {
        enforceChange("setEnabledExclusive")
        return false
    }
    override fun setEnabledExclusiveInCategory(packageName: String?, userId: Int): Boolean {
        enforceChange("setEnabledExclusiveInCategory")
        return false
    }
    override fun setPriority(packageName: String?, newParentPackageName: String?, userId: Int): Boolean {
        enforceChange("setPriority")
        return false
    }
    override fun setHighestPriority(packageName: String?, userId: Int): Boolean {
        enforceChange("setHighestPriority")
        return false
    }
    override fun setLowestPriority(packageName: String?, userId: Int): Boolean {
        enforceChange("setLowestPriority")
        return false
    }
    override fun getDefaultOverlayPackages(): Array<String?>? {
        permissions.enforce(Manifest.permission.MODIFY_THEME_OVERLAY, "getDefaultOverlayPackages")
        return arrayOfNulls(0)
    }
    override fun invalidateCachesForOverlay(packageName: String?, userId: Int) = enforceChange("invalidateCachesForOverlay")
    override fun getPartitionOrder(): String? = ""
    override fun isDefaultPartitionOrder(): Boolean = true
}
