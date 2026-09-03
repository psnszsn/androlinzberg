@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ISearchManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedSearchManager : android.app.ISearchManager.Stub() {
    override fun getSearchableInfo(launchActivity: android.content.ComponentName?): android.app.SearchableInfo? = throw UnsupportedOperationException("ISearchManager.getSearchableInfo is not hosted")
    override fun getSearchablesInGlobalSearch(): MutableList<android.app.SearchableInfo?>? = throw UnsupportedOperationException("ISearchManager.getSearchablesInGlobalSearch is not hosted")
    override fun getGlobalSearchActivities(): MutableList<android.content.pm.ResolveInfo?>? = throw UnsupportedOperationException("ISearchManager.getGlobalSearchActivities is not hosted")
    override fun getGlobalSearchActivity(): android.content.ComponentName? = throw UnsupportedOperationException("ISearchManager.getGlobalSearchActivity is not hosted")
    override fun getWebSearchActivity(): android.content.ComponentName? = throw UnsupportedOperationException("ISearchManager.getWebSearchActivity is not hosted")
    override fun launchAssist(userHandle: Int, args: android.os.Bundle?) { throw UnsupportedOperationException("ISearchManager.launchAssist is not hosted") }
}
