package dev.artzig.system

import android.app.SearchableInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.UserHandle
import android.util.Log

/**
 * The session's ISearchManager over the real AIDL surface, published as
 * `Context.SEARCH_SERVICE`.
 *
 * Source note: `com.android.server.search.SearchManagerService` and `Searchables`
 * for a device with no global-search or web-search provider: a searchable
 * activity's metadata is parsed from its `android.app.searchable` meta-data
 * exactly as `Searchables.getSearchableInfo` does, the global search list is
 * empty, and `launchAssist` has no assistant to start.
 */
class HostedSearchManagerService(private val context: Context, private val packages: android.content.pm.IPackageManager) : android.app.ISearchManager.Stub(), android.app.ISearchManager by unsupported<android.app.ISearchManager>() {
    override fun asBinder(): android.os.IBinder = this

    override fun getSearchableInfo(launchActivity: ComponentName?): SearchableInfo? {
        val component = launchActivity ?: return null
        val activityInfo = android.os.Binder.withCleanCallingIdentity<android.content.pm.ActivityInfo?> {
            packages.getActivityInfo(component, PackageManager.GET_META_DATA.toLong(), UserHandle.USER_SYSTEM)
        } ?: return null
        return try {
            SearchableInfo.getActivityMetaData(context, activityInfo, UserHandle.USER_SYSTEM)
        } catch (e: Exception) {
            Log.w(TAG, "searchable metadata of $component not readable", e)
            null
        }
    }
    override fun getSearchablesInGlobalSearch(): MutableList<SearchableInfo?>? = ArrayList()
    override fun getGlobalSearchActivities(): MutableList<ResolveInfo?>? = ArrayList()
    override fun getGlobalSearchActivity(): ComponentName? = null
    override fun getWebSearchActivity(): ComponentName? = null
    override fun launchAssist(userHandle: Int, args: Bundle?) {
        Log.i(TAG, "launchAssist: no assistant on this host")
    }

    private companion object {
        const val TAG = "HostedSearchManager"
    }
}
