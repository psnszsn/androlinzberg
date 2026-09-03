@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IOverlayManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedOverlayManager : android.content.om.IOverlayManager.Stub() {
    override fun getAllOverlays(userId: Int): MutableMap<String?, MutableList<android.content.om.OverlayInfo?>?>? = throw UnsupportedOperationException("IOverlayManager.getAllOverlays is not hosted")
    override fun getOverlayInfosForTarget(targetPackageName: String?, userId: Int): MutableList<android.content.om.OverlayInfo?>? = throw UnsupportedOperationException("IOverlayManager.getOverlayInfosForTarget is not hosted")
    override fun getOverlayInfo(packageName: String?, userId: Int): android.content.om.OverlayInfo? = throw UnsupportedOperationException("IOverlayManager.getOverlayInfo is not hosted")
    override fun getOverlayInfoByIdentifier(packageName: android.content.om.OverlayIdentifier?, userId: Int): android.content.om.OverlayInfo? = throw UnsupportedOperationException("IOverlayManager.getOverlayInfoByIdentifier is not hosted")
    override fun setEnabled(packageName: String?, enable: Boolean, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setEnabled is not hosted")
    override fun enableWithConstraints(packageName: String?, userId: Int, constraints: MutableList<android.content.om.OverlayConstraint?>?): Boolean = throw UnsupportedOperationException("IOverlayManager.enableWithConstraints is not hosted")
    override fun setEnabledExclusive(packageName: String?, enable: Boolean, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setEnabledExclusive is not hosted")
    override fun setEnabledExclusiveInCategory(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setEnabledExclusiveInCategory is not hosted")
    override fun setPriority(packageName: String?, newParentPackageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setPriority is not hosted")
    override fun setHighestPriority(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setHighestPriority is not hosted")
    override fun setLowestPriority(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IOverlayManager.setLowestPriority is not hosted")
    override fun getDefaultOverlayPackages(): Array<String?>? = throw UnsupportedOperationException("IOverlayManager.getDefaultOverlayPackages is not hosted")
    override fun invalidateCachesForOverlay(packageName: String?, userId: Int) { throw UnsupportedOperationException("IOverlayManager.invalidateCachesForOverlay is not hosted") }
    override fun commit(transaction: android.content.om.OverlayManagerTransaction?) { throw UnsupportedOperationException("IOverlayManager.commit is not hosted") }
    override fun getPartitionOrder(): String? = throw UnsupportedOperationException("IOverlayManager.getPartitionOrder is not hosted")
    override fun isDefaultPartitionOrder(): Boolean = throw UnsupportedOperationException("IOverlayManager.isDefaultPartitionOrder is not hosted")
}
