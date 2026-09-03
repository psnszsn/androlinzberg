@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IAppWidgetService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedAppWidgetService : com.android.internal.appwidget.IAppWidgetService.Stub() {
    override fun startListening(host: com.android.internal.appwidget.IAppWidgetHost?, callingPackage: String?, hostId: Int, appWidgetIds: IntArray?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IAppWidgetService.startListening is not hosted")
    override fun stopListening(callingPackage: String?, hostId: Int) { throw UnsupportedOperationException("IAppWidgetService.stopListening is not hosted") }
    override fun allocateAppWidgetId(callingPackage: String?, hostId: Int): Int = throw UnsupportedOperationException("IAppWidgetService.allocateAppWidgetId is not hosted")
    override fun deleteAppWidgetId(callingPackage: String?, appWidgetId: Int) { throw UnsupportedOperationException("IAppWidgetService.deleteAppWidgetId is not hosted") }
    override fun deleteHost(packageName: String?, hostId: Int) { throw UnsupportedOperationException("IAppWidgetService.deleteHost is not hosted") }
    override fun deleteAllHosts() { throw UnsupportedOperationException("IAppWidgetService.deleteAllHosts is not hosted") }
    override fun getAppWidgetViews(callingPackage: String?, appWidgetId: Int): android.widget.RemoteViews? = throw UnsupportedOperationException("IAppWidgetService.getAppWidgetViews is not hosted")
    override fun getAppWidgetIdsForHost(callingPackage: String?, hostId: Int): IntArray? = throw UnsupportedOperationException("IAppWidgetService.getAppWidgetIdsForHost is not hosted")
    override fun setAppWidgetHidden(callingPackage: String?, hostId: Int) { throw UnsupportedOperationException("IAppWidgetService.setAppWidgetHidden is not hosted") }
    override fun createAppWidgetConfigIntentSender(callingPackage: String?, appWidgetId: Int, intentFlags: Int): android.content.IntentSender? = throw UnsupportedOperationException("IAppWidgetService.createAppWidgetConfigIntentSender is not hosted")
    override fun updateAppWidgetIds(callingPackage: String?, appWidgetIds: IntArray?, views: android.widget.RemoteViews?) { throw UnsupportedOperationException("IAppWidgetService.updateAppWidgetIds is not hosted") }
    override fun updateAppWidgetOptions(callingPackage: String?, appWidgetId: Int, extras: android.os.Bundle?) { throw UnsupportedOperationException("IAppWidgetService.updateAppWidgetOptions is not hosted") }
    override fun getAppWidgetOptions(callingPackage: String?, appWidgetId: Int): android.os.Bundle? = throw UnsupportedOperationException("IAppWidgetService.getAppWidgetOptions is not hosted")
    override fun partiallyUpdateAppWidgetIds(callingPackage: String?, appWidgetIds: IntArray?, views: android.widget.RemoteViews?) { throw UnsupportedOperationException("IAppWidgetService.partiallyUpdateAppWidgetIds is not hosted") }
    override fun updateAppWidgetProvider(provider: android.content.ComponentName?, views: android.widget.RemoteViews?) { throw UnsupportedOperationException("IAppWidgetService.updateAppWidgetProvider is not hosted") }
    override fun updateAppWidgetProviderInfo(provider: android.content.ComponentName?, metadataKey: String?) { throw UnsupportedOperationException("IAppWidgetService.updateAppWidgetProviderInfo is not hosted") }
    override fun notifyAppWidgetViewDataChanged(packageName: String?, appWidgetIds: IntArray?, viewId: Int) { throw UnsupportedOperationException("IAppWidgetService.notifyAppWidgetViewDataChanged is not hosted") }
    override fun getInstalledProvidersForProfile(categoryFilter: Int, profileId: Int, packageName: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IAppWidgetService.getInstalledProvidersForProfile is not hosted")
    override fun getAppWidgetInfo(callingPackage: String?, appWidgetId: Int): android.appwidget.AppWidgetProviderInfo? = throw UnsupportedOperationException("IAppWidgetService.getAppWidgetInfo is not hosted")
    override fun hasBindAppWidgetPermission(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IAppWidgetService.hasBindAppWidgetPermission is not hosted")
    override fun setBindAppWidgetPermission(packageName: String?, userId: Int, permission: Boolean) { throw UnsupportedOperationException("IAppWidgetService.setBindAppWidgetPermission is not hosted") }
    override fun bindAppWidgetId(callingPackage: String?, appWidgetId: Int, providerProfileId: Int, providerComponent: android.content.ComponentName?, options: android.os.Bundle?): Boolean = throw UnsupportedOperationException("IAppWidgetService.bindAppWidgetId is not hosted")
    override fun bindRemoteViewsService(callingPackage: String?, appWidgetId: Int, intent: android.content.Intent?, caller: android.app.IApplicationThread?, token: android.os.IBinder?, connection: android.app.IServiceConnection?, flags: Long): Boolean = throw UnsupportedOperationException("IAppWidgetService.bindRemoteViewsService is not hosted")
    override fun notifyProviderInheritance(componentNames: Array<android.content.ComponentName?>?) { throw UnsupportedOperationException("IAppWidgetService.notifyProviderInheritance is not hosted") }
    override fun getMaxBitmapMemory(): Int = throw UnsupportedOperationException("IAppWidgetService.getMaxBitmapMemory is not hosted")
    override fun getAppWidgetIds(providerComponent: android.content.ComponentName?): IntArray? = throw UnsupportedOperationException("IAppWidgetService.getAppWidgetIds is not hosted")
    override fun isBoundWidgetPackage(packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IAppWidgetService.isBoundWidgetPackage is not hosted")
    override fun requestPinAppWidget(packageName: String?, providerComponent: android.content.ComponentName?, extras: android.os.Bundle?, resultIntent: android.content.IntentSender?): Boolean = throw UnsupportedOperationException("IAppWidgetService.requestPinAppWidget is not hosted")
    override fun isRequestPinAppWidgetSupported(): Boolean = throw UnsupportedOperationException("IAppWidgetService.isRequestPinAppWidgetSupported is not hosted")
    override fun noteAppWidgetTapped(callingPackage: String?, appWidgetId: Int) { throw UnsupportedOperationException("IAppWidgetService.noteAppWidgetTapped is not hosted") }
    override fun setWidgetPreview(providerComponent: android.content.ComponentName?, widgetCategories: Int, preview: android.widget.RemoteViews?): Boolean = throw UnsupportedOperationException("IAppWidgetService.setWidgetPreview is not hosted")
    override fun getWidgetPreview(callingPackage: String?, providerComponent: android.content.ComponentName?, profileId: Int, widgetCategory: Int): android.widget.RemoteViews? = throw UnsupportedOperationException("IAppWidgetService.getWidgetPreview is not hosted")
    override fun removeWidgetPreview(providerComponent: android.content.ComponentName?, widgetCategories: Int) { throw UnsupportedOperationException("IAppWidgetService.removeWidgetPreview is not hosted") }
}
