package dev.artzig.system

import android.Manifest
import android.app.IApplicationThread
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ParceledListSlice
import android.os.Bundle
import android.os.IBinder
import android.widget.RemoteViews
import com.android.internal.appwidget.IAppWidgetHost

/**
 * The session's IAppWidgetService over the real AIDL surface, published as
 * `Context.APPWIDGET_SERVICE`.
 *
 * Source note: `com.android.server.appwidget.AppWidgetServiceImpl` for a device
 * with no widget providers: the host table (ids allocated per host, listening
 * state, per-widget options) is real, the provider table is empty (no
 * `APPWIDGET_UPDATE` receiver is parsed, so `getInstalledProviders` is empty and
 * `bindAppWidgetId` finds no provider), bind permission is per package behind
 * MODIFY_APPWIDGET_BIND_PERMISSIONS, pinning is unsupported without a launcher,
 * and updates from providers have no bound widget to reach. RemoteViews service
 * binding needs a bound widget and stays unserved.
 */
class HostedAppWidgetService(private val context: Context, private val permissions: HostedPermissions) : UnsupportedAppWidgetService() {
    private class Host(val packageName: String, val hostId: Int) {
        val widgetIds = LinkedHashSet<Int>()
        var listening: IAppWidgetHost? = null
    }

    private val lock = Any()
    private val hosts = LinkedHashMap<Pair<String, Int>, Host>()
    private val options = HashMap<Int, Bundle>()
    private val bindPermission = HashSet<String>()
    private var nextWidgetId = 1

    private fun host(callingPackage: String?, hostId: Int): Host {
        permissions.enforceCallerPackage(callingPackage, "appwidget")
        return hosts.getOrPut(callingPackage!! to hostId) { Host(callingPackage, hostId) }
    }

    override fun startListening(host: IAppWidgetHost?, callingPackage: String?, hostId: Int, appWidgetIds: IntArray?): ParceledListSlice<*>? {
        synchronized(lock) { host(callingPackage, hostId).listening = host }
        return ParceledListSlice(ArrayList<android.appwidget.PendingHostUpdate>())
    }
    override fun stopListening(callingPackage: String?, hostId: Int) {
        synchronized(lock) { hosts[callingPackage to hostId]?.listening = null }
    }
    override fun allocateAppWidgetId(callingPackage: String?, hostId: Int): Int = synchronized(lock) {
        val id = nextWidgetId++
        host(callingPackage, hostId).widgetIds.add(id)
        id
    }
    override fun deleteAppWidgetId(callingPackage: String?, appWidgetId: Int) {
        permissions.enforceCallerPackage(callingPackage, "deleteAppWidgetId")
        synchronized(lock) {
            hosts.values.filter { it.packageName == callingPackage }.forEach { it.widgetIds.remove(appWidgetId) }
            options.remove(appWidgetId)
        }
    }
    override fun deleteHost(packageName: String?, hostId: Int) {
        permissions.enforceCallerPackage(packageName, "deleteHost")
        synchronized(lock) { hosts.remove(packageName to hostId)?.widgetIds?.forEach { options.remove(it) } }
    }
    override fun deleteAllHosts() {
        val uidPackages = permissions
        synchronized(lock) {
            val own = hosts.values.filter { uidPackages.isCallerPackage(it.packageName) }
            for (h in own) { hosts.remove(h.packageName to h.hostId); h.widgetIds.forEach { options.remove(it) } }
        }
    }
    override fun getAppWidgetIdsForHost(callingPackage: String?, hostId: Int): IntArray? = synchronized(lock) {
        hosts[callingPackage to hostId]?.widgetIds?.toIntArray() ?: IntArray(0)
    }
    override fun setAppWidgetHidden(callingPackage: String?, hostId: Int) {}
    override fun getAppWidgetViews(callingPackage: String?, appWidgetId: Int): RemoteViews? = null
    override fun getAppWidgetInfo(callingPackage: String?, appWidgetId: Int): AppWidgetProviderInfo? = null
    override fun createAppWidgetConfigIntentSender(callingPackage: String?, appWidgetId: Int, intentFlags: Int): IntentSender? = null
    override fun getAppWidgetOptions(callingPackage: String?, appWidgetId: Int): Bundle? = synchronized(lock) { options[appWidgetId]?.let { Bundle(it) } ?: Bundle() }
    override fun updateAppWidgetOptions(callingPackage: String?, appWidgetId: Int, extras: Bundle?) {
        synchronized(lock) { options.getOrPut(appWidgetId) { Bundle() }.putAll(extras ?: return) }
    }
    override fun updateAppWidgetIds(callingPackage: String?, appWidgetIds: IntArray?, views: RemoteViews?) {}
    override fun partiallyUpdateAppWidgetIds(callingPackage: String?, appWidgetIds: IntArray?, views: RemoteViews?) {}
    override fun updateAppWidgetProvider(provider: ComponentName?, views: RemoteViews?) {}
    override fun updateAppWidgetProviderInfo(provider: ComponentName?, metadataKey: String?) {}
    override fun notifyAppWidgetViewDataChanged(packageName: String?, appWidgetIds: IntArray?, viewId: Int) {}
    override fun notifyProviderInheritance(componentNames: Array<ComponentName?>?) {}
    override fun getInstalledProvidersForProfile(categoryFilter: Int, profileId: Int, packageName: String?): ParceledListSlice<*>? = ParceledListSlice(ArrayList<AppWidgetProviderInfo>())
    override fun getAppWidgetIds(providerComponent: ComponentName?): IntArray? = IntArray(0)
    override fun isBoundWidgetPackage(packageName: String?, userId: Int): Boolean = false
    override fun hasBindAppWidgetPermission(packageName: String?, userId: Int): Boolean {
        permissions.enforce(Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS, "hasBindAppWidgetPermission")
        return synchronized(lock) { packageName in bindPermission }
    }
    override fun setBindAppWidgetPermission(packageName: String?, userId: Int, permission: Boolean) {
        permissions.enforce(Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS, "setBindAppWidgetPermission")
        synchronized(lock) { if (permission) bindPermission.add(packageName ?: return) else bindPermission.remove(packageName) }
    }
    /** AppWidgetServiceImpl.bindAppWidgetId: no provider matches, so nothing binds. */
    override fun bindAppWidgetId(callingPackage: String?, appWidgetId: Int, providerProfileId: Int, providerComponent: ComponentName?, options: Bundle?): Boolean {
        permissions.enforceCallerPackage(callingPackage, "bindAppWidgetId")
        return false
    }
    override fun getMaxBitmapMemory(): Int {
        val metrics = context.resources.displayMetrics
        return 6 * metrics.widthPixels * metrics.heightPixels
    }
    override fun requestPinAppWidget(packageName: String?, providerComponent: ComponentName?, extras: Bundle?, resultIntent: IntentSender?): Boolean = false
    override fun isRequestPinAppWidgetSupported(): Boolean = false
    override fun noteAppWidgetTapped(callingPackage: String?, appWidgetId: Int) {}
    override fun setWidgetPreview(providerComponent: ComponentName?, widgetCategories: Int, preview: RemoteViews?): Boolean = false
    override fun getWidgetPreview(callingPackage: String?, providerComponent: ComponentName?, profileId: Int, widgetCategory: Int): RemoteViews? = null
    override fun removeWidgetPreview(providerComponent: ComponentName?, widgetCategories: Int) {}
}
