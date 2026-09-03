package dev.artzig.system

import android.app.usage.AppStandbyInfo
import android.app.usage.BroadcastResponseStatsList
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.pm.ParceledListSlice
import android.os.Binder
import android.os.Process
import android.os.UserHandle

/**
 * The session's usage stats over the real `IUsageStatsManager` surface,
 * published as `Context.USAGE_STATS_SERVICE`.
 *
 * Source note: `com.android.server.usage.UsageStatsService` and `UserUsageStatsService`
 * (`queryUsageStats`, `queryEvents`, the standby bucket answers of a device with app
 * standby disabled). AOSP records events from the activity task manager; this host has no
 * activity lifecycle of its own, so the activity manager reports process attach and death
 * as the foreground span of a package: an attached hosted app is the foreground app
 * (`ACTIVITY_RESUMED`), its death ends the span (`ACTIVITY_STOPPED`). Usage stats are
 * aggregated from those spans over the queried interval. App standby is disabled: every
 * package is `STANDBY_BUCKET_ACTIVE`, nothing is inactive.
 */
class HostedUsageStatsService : UnsupportedUsageStatsManager() {
    @Volatile var packageService: HostedPackageManagerService? = null

    private class Event(val packageName: String, val type: Int, val timeStamp: Long)

    private val lock = Any()
    private val events = ArrayList<Event>()
    /** Open foreground spans: package -> resume time (UserUsageStatsService.mLastForegroundTimes). */
    private val foregroundSince = LinkedHashMap<String, Long>()

    /** HostedActivityManagerService reports process attach (foreground=true) and death. */
    fun reportProcessState(packageName: String, foreground: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (foreground) {
                foregroundSince[packageName] = now
                events.add(Event(packageName, UsageEvents.Event.ACTIVITY_RESUMED, now))
            } else if (foregroundSince.remove(packageName) != null) {
                events.add(Event(packageName, UsageEvents.Event.ACTIVITY_STOPPED, now))
            }
        }
    }

    private fun checkCallerIsSystemOrSameApp(pkg: String?) {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == 0) return
        val packages = packageService?.getPackagesForUid(uid)
        if (packages == null || packages.isEmpty() || packages.contains(pkg)) return
        throw SecurityException("Calling uid $uid cannot query usage stats as $pkg")
    }

    // ------------------------------------------------------------ stats

    /** UserUsageStatsService.queryUsageStats: foreground spans folded into one UsageStats per package. */
    override fun queryUsageStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?, userId: Int): ParceledListSlice<*>? {
        checkCallerIsSystemOrSameApp(callingPackage)
        if (bucketType < UsageStatsManager.INTERVAL_DAILY || bucketType > UsageStatsManager.INTERVAL_BEST) {
            throw IllegalArgumentException("Bad interval type: $bucketType")
        }
        val now = System.currentTimeMillis()
        val stats = LinkedHashMap<String, UsageStats>()
        synchronized(lock) {
            var openSince = HashMap<String, Long>()
            for (event in events) {
                if (event.timeStamp > endTime) break
                val s = stats.getOrPut(event.packageName) { newStats(event.packageName, beginTime, endTime) }
                when (event.type) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        openSince[event.packageName] = event.timeStamp
                        if (event.timeStamp >= beginTime) {
                            s.mLastTimeUsed = event.timeStamp
                            s.mLastTimeVisible = event.timeStamp
                            s.mLaunchCount++
                            s.mAppLaunchCount++
                        }
                    }
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val since = openSince.remove(event.packageName) ?: continue
                        addForeground(s, since, event.timeStamp, beginTime, endTime)
                    }
                    UsageEvents.Event.USER_INTERACTION -> if (event.timeStamp >= beginTime) s.mLastTimeUsed = event.timeStamp
                }
            }
            for ((pkg, since) in openSince) {
                val s = stats.getOrPut(pkg) { newStats(pkg, beginTime, endTime) }
                addForeground(s, since, Math.min(now, endTime), beginTime, endTime)
            }
        }
        return ParceledListSlice(stats.values.filter { it.mTotalTimeInForeground > 0 || it.mLastTimeUsed >= beginTime })
    }

    private fun newStats(packageName: String, beginTime: Long, endTime: Long): UsageStats = UsageStats().also {
        it.mPackageName = packageName
        it.mBeginTimeStamp = beginTime
        it.mEndTimeStamp = endTime
    }

    private fun addForeground(s: UsageStats, since: Long, until: Long, beginTime: Long, endTime: Long) {
        val start = Math.max(since, beginTime)
        val end = Math.min(until, endTime)
        if (end > start) {
            s.mTotalTimeInForeground += end - start
            s.mTotalTimeVisible += end - start
            s.mLastTimeUsed = Math.max(s.mLastTimeUsed, end)
            s.mLastTimeVisible = Math.max(s.mLastTimeVisible, end)
        }
    }

    override fun queryConfigurationStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?): ParceledListSlice<*>? =
        ParceledListSlice(emptyList<android.app.usage.ConfigurationStats>())

    override fun queryEventStats(bucketType: Int, beginTime: Long, endTime: Long, callingPackage: String?): ParceledListSlice<*>? =
        ParceledListSlice(emptyList<android.app.usage.EventStats>())

    private fun eventsBetween(beginTime: Long, endTime: Long, packageFilter: String?): UsageEvents {
        val list = ArrayList<UsageEvents.Event>()
        val pool = LinkedHashSet<String>()
        synchronized(lock) {
            for (event in events) {
                if (event.timeStamp < beginTime || event.timeStamp >= endTime) continue
                if (packageFilter != null && event.packageName != packageFilter) continue
                val e = UsageEvents.Event(event.type, event.timeStamp)
                e.mPackage = event.packageName
                list.add(e)
                pool.add(event.packageName)
            }
        }
        return UsageEvents(list, pool.toTypedArray(), false)
    }

    override fun queryEvents(beginTime: Long, endTime: Long, callingPackage: String?): UsageEvents? {
        checkCallerIsSystemOrSameApp(callingPackage)
        return eventsBetween(beginTime, endTime, null)
    }

    override fun queryEventsForPackage(beginTime: Long, endTime: Long, callingPackage: String?): UsageEvents? {
        checkCallerIsSystemOrSameApp(callingPackage)
        return eventsBetween(beginTime, endTime, callingPackage)
    }

    override fun queryEventsForUser(beginTime: Long, endTime: Long, userId: Int, callingPackage: String?): UsageEvents? = queryEvents(beginTime, endTime, callingPackage)
    override fun queryEventsForPackageForUser(beginTime: Long, endTime: Long, userId: Int, pkg: String?, callingPackage: String?): UsageEvents? =
        eventsBetween(beginTime, endTime, pkg)
    override fun queryEventsWithFilter(query: UsageEventsQuery?, callingPackage: String?): UsageEvents? {
        query ?: return null
        return eventsBetween(query.beginTimeMillis, query.endTimeMillis, null)
    }

    // ------------------------------------------------------------ standby (disabled)

    override fun setAppInactive(packageName: String?, inactive: Boolean, userId: Int) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setAppInactive requires android.permission.CHANGE_APP_IDLE_STATE")
    }
    override fun isAppStandbyEnabled(): Boolean = false
    override fun isAppInactive(packageName: String?, userId: Int, callingPackage: String?): Boolean = false
    override fun onCarrierPrivilegedAppsChanged() {}
    override fun reportChooserSelection(packageName: String?, userId: Int, contentType: String?, annotations: Array<String?>?, action: String?) {}
    override fun getAppStandbyBucket(packageName: String?, callingPackage: String?, userId: Int): Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    override fun setAppStandbyBucket(packageName: String?, bucket: Int, userId: Int) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setAppStandbyBucket requires android.permission.CHANGE_APP_IDLE_STATE")
    }
    override fun getAppStandbyBuckets(callingPackage: String?, userId: Int): ParceledListSlice<*>? {
        val packages = packageService?.getPackagesForUid(Binder.getCallingUid()) ?: arrayOf()
        return ParceledListSlice(packages.map { AppStandbyInfo(it, UsageStatsManager.STANDBY_BUCKET_ACTIVE) })
    }
    override fun setAppStandbyBuckets(appBuckets: ParceledListSlice<*>?, userId: Int) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("setAppStandbyBuckets requires android.permission.CHANGE_APP_IDLE_STATE")
    }
    override fun getAppMinStandbyBucket(packageName: String?, callingPackage: String?, userId: Int): Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE
    override fun setEstimatedLaunchTime(packageName: String?, estimatedLaunchTime: Long, userId: Int) {}
    override fun setEstimatedLaunchTimes(appLaunchTimes: ParceledListSlice<*>?, userId: Int) {}
    override fun registerAppUsageObserver(observerId: Int, packages: Array<String?>?, timeLimitMs: Long, callback: android.app.PendingIntent?, callingPackage: String?) {}
    override fun unregisterAppUsageObserver(observerId: Int, callingPackage: String?) {}
    override fun registerUsageSessionObserver(sessionObserverId: Int, observed: Array<String?>?, timeLimitMs: Long, sessionThresholdTimeMs: Long, limitReachedCallbackIntent: android.app.PendingIntent?, sessionEndCallbackIntent: android.app.PendingIntent?, callingPackage: String?) {}
    override fun unregisterUsageSessionObserver(sessionObserverId: Int, callingPackage: String?) {}
    override fun registerAppUsageLimitObserver(observerId: Int, packages: Array<String?>?, timeLimitMs: Long, timeUsedMs: Long, callback: android.app.PendingIntent?, callingPackage: String?) {}
    override fun unregisterAppUsageLimitObserver(observerId: Int, callingPackage: String?) {}
    override fun reportUsageStart(activity: android.os.IBinder?, token: String?, callingPackage: String?) {}
    override fun reportPastUsageStart(activity: android.os.IBinder?, token: String?, timeAgoMs: Long, callingPackage: String?) {}
    override fun reportUsageStop(activity: android.os.IBinder?, token: String?, callingPackage: String?) {}
    override fun reportUserInteraction(packageName: String?, userId: Int) {
        synchronized(lock) { events.add(Event(packageName ?: return, UsageEvents.Event.USER_INTERACTION, System.currentTimeMillis())) }
    }
    override fun reportUserInteractionWithBundle(packageName: String?, userId: Int, eventExtras: android.os.PersistableBundle?) = reportUserInteraction(packageName, userId)
    override fun getUsageSource(): Int = UsageStatsManager.USAGE_SOURCE_TASK_ROOT_ACTIVITY
    override fun forceUsageSourceSettingRead() {}
    override fun getLastTimeAnyComponentUsed(packageName: String?, callingPackage: String?): Long =
        synchronized(lock) { events.lastOrNull { it.packageName == packageName }?.timeStamp ?: 0L }
    override fun queryBroadcastResponseStats(packageName: String?, id: Long, callingPackage: String?, userId: Int): BroadcastResponseStatsList? =
        BroadcastResponseStatsList(ArrayList())
    override fun clearBroadcastResponseStats(packageName: String?, id: Long, callingPackage: String?, userId: Int) {}
    override fun clearBroadcastEvents(callingPackage: String?, userId: Int) {}
    override fun isPackageExemptedFromBroadcastResponseStats(packageName: String?, userId: Int): Boolean = false
    override fun getAppStandbyConstant(key: String?): String? = null
}
