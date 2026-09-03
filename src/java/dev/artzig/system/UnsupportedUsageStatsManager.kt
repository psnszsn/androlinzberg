@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IUsageStatsManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedUsageStatsManager : android.app.usage.IUsageStatsManager.Stub() {
    override fun queryUsageStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUsageStatsManager.queryUsageStats is not hosted")
    override fun queryConfigurationStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUsageStatsManager.queryConfigurationStats is not hosted")
    override fun queryEventStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUsageStatsManager.queryEventStats is not hosted")
    override fun queryEvents(beginTime: Long, endTime: Long, callingPackage: String?): android.app.usage.UsageEvents? = throw UnsupportedOperationException("IUsageStatsManager.queryEvents is not hosted")
    override fun queryEventsForPackage(beginTime: Long, endTime: Long, callingPackage: String?): android.app.usage.UsageEvents? = throw UnsupportedOperationException("IUsageStatsManager.queryEventsForPackage is not hosted")
    override fun queryEventsForUser(beginTime: Long, endTime: Long, userId: Int, callingPackage: String?): android.app.usage.UsageEvents? = throw UnsupportedOperationException("IUsageStatsManager.queryEventsForUser is not hosted")
    override fun queryEventsForPackageForUser(beginTime: Long, endTime: Long, userId: Int, pkg: String?, callingPackage: String?): android.app.usage.UsageEvents? = throw UnsupportedOperationException("IUsageStatsManager.queryEventsForPackageForUser is not hosted")
    override fun queryEventsWithFilter(query: android.app.usage.UsageEventsQuery?, callingPackage: String?): android.app.usage.UsageEvents? = throw UnsupportedOperationException("IUsageStatsManager.queryEventsWithFilter is not hosted")
    override fun setAppInactive(packageName: String?, inactive: Boolean, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.setAppInactive is not hosted") }
    override fun isAppStandbyEnabled(): Boolean = throw UnsupportedOperationException("IUsageStatsManager.isAppStandbyEnabled is not hosted")
    override fun isAppInactive(packageName: String?, userId: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("IUsageStatsManager.isAppInactive is not hosted")
    override fun onCarrierPrivilegedAppsChanged() { throw UnsupportedOperationException("IUsageStatsManager.onCarrierPrivilegedAppsChanged is not hosted") }
    override fun reportChooserSelection(packageName: String?, userId: Int, contentType: String?, annotations: Array<String?>?, action: String?) { throw UnsupportedOperationException("IUsageStatsManager.reportChooserSelection is not hosted") }
    override fun getAppStandbyBucket(packageName: String?, callingPackage: String?, userId: Int): Int = throw UnsupportedOperationException("IUsageStatsManager.getAppStandbyBucket is not hosted")
    override fun setAppStandbyBucket(packageName: String?, bucket: Int, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.setAppStandbyBucket is not hosted") }
    override fun getAppStandbyBuckets(callingPackage: String?, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IUsageStatsManager.getAppStandbyBuckets is not hosted")
    override fun setAppStandbyBuckets(appBuckets: android.content.pm.ParceledListSlice<*>?, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.setAppStandbyBuckets is not hosted") }
    override fun getAppMinStandbyBucket(packageName: String?, callingPackage: String?, userId: Int): Int = throw UnsupportedOperationException("IUsageStatsManager.getAppMinStandbyBucket is not hosted")
    override fun setEstimatedLaunchTime(packageName: String?, estimatedLaunchTime: Long, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.setEstimatedLaunchTime is not hosted") }
    override fun setEstimatedLaunchTimes(appLaunchTimes: android.content.pm.ParceledListSlice<*>?, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.setEstimatedLaunchTimes is not hosted") }
    override fun registerAppUsageObserver(observerId: Int, packages: Array<String?>?, timeLimitMs: Long, callback: android.app.PendingIntent?, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.registerAppUsageObserver is not hosted") }
    override fun unregisterAppUsageObserver(observerId: Int, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.unregisterAppUsageObserver is not hosted") }
    override fun registerUsageSessionObserver(sessionObserverId: Int, observed: Array<String?>?, timeLimitMs: Long, sessionThresholdTimeMs: Long, limitReachedCallbackIntent: android.app.PendingIntent?, sessionEndCallbackIntent: android.app.PendingIntent?, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.registerUsageSessionObserver is not hosted") }
    override fun unregisterUsageSessionObserver(sessionObserverId: Int, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.unregisterUsageSessionObserver is not hosted") }
    override fun registerAppUsageLimitObserver(observerId: Int, packages: Array<String?>?, timeLimitMs: Long, timeUsedMs: Long, callback: android.app.PendingIntent?, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.registerAppUsageLimitObserver is not hosted") }
    override fun unregisterAppUsageLimitObserver(observerId: Int, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.unregisterAppUsageLimitObserver is not hosted") }
    override fun reportUsageStart(activity: android.os.IBinder?, token: String?, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.reportUsageStart is not hosted") }
    override fun reportPastUsageStart(activity: android.os.IBinder?, token: String?, timeAgoMs: Long, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.reportPastUsageStart is not hosted") }
    override fun reportUsageStop(activity: android.os.IBinder?, token: String?, callingPackage: String?) { throw UnsupportedOperationException("IUsageStatsManager.reportUsageStop is not hosted") }
    override fun reportUserInteraction(packageName: String?, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.reportUserInteraction is not hosted") }
    override fun reportUserInteractionWithBundle(packageName: String?, userId: Int, eventExtras: android.os.PersistableBundle?) { throw UnsupportedOperationException("IUsageStatsManager.reportUserInteractionWithBundle is not hosted") }
    override fun getUsageSource(): Int = throw UnsupportedOperationException("IUsageStatsManager.getUsageSource is not hosted")
    override fun forceUsageSourceSettingRead() { throw UnsupportedOperationException("IUsageStatsManager.forceUsageSourceSettingRead is not hosted") }
    override fun getLastTimeAnyComponentUsed(packageName: String?, callingPackage: String?): Long = throw UnsupportedOperationException("IUsageStatsManager.getLastTimeAnyComponentUsed is not hosted")
    override fun queryBroadcastResponseStats(packageName: String?, id: Long, callingPackage: String?, userId: Int): android.app.usage.BroadcastResponseStatsList? = throw UnsupportedOperationException("IUsageStatsManager.queryBroadcastResponseStats is not hosted")
    override fun clearBroadcastResponseStats(packageName: String?, id: Long, callingPackage: String?, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.clearBroadcastResponseStats is not hosted") }
    override fun clearBroadcastEvents(callingPackage: String?, userId: Int) { throw UnsupportedOperationException("IUsageStatsManager.clearBroadcastEvents is not hosted") }
    override fun isPackageExemptedFromBroadcastResponseStats(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IUsageStatsManager.isPackageExemptedFromBroadcastResponseStats is not hosted")
    override fun getAppStandbyConstant(key: String?): String? = throw UnsupportedOperationException("IUsageStatsManager.getAppStandbyConstant is not hosted")
}
