@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ILocationManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedLocationManager : android.location.ILocationManager.Stub() {
    override fun getLastLocation(provider: String?, request: android.location.LastLocationRequest?, packageName: String?, attributionTag: String?): android.location.Location? = throw UnsupportedOperationException("ILocationManager.getLastLocation is not hosted")
    override fun getCurrentLocation(provider: String?, request: android.location.LocationRequest?, callback: android.location.ILocationCallback?, packageName: String?, attributionTag: String?, listenerId: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ILocationManager.getCurrentLocation is not hosted")
    override fun registerLocationListener(provider: String?, request: android.location.LocationRequest?, listener: android.location.ILocationListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.registerLocationListener is not hosted") }
    override fun unregisterLocationListener(listener: android.location.ILocationListener?) { throw UnsupportedOperationException("ILocationManager.unregisterLocationListener is not hosted") }
    override fun registerLocationPendingIntent(provider: String?, request: android.location.LocationRequest?, pendingIntent: android.app.PendingIntent?, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.registerLocationPendingIntent is not hosted") }
    override fun unregisterLocationPendingIntent(pendingIntent: android.app.PendingIntent?) { throw UnsupportedOperationException("ILocationManager.unregisterLocationPendingIntent is not hosted") }
    override fun injectLocation(location: android.location.Location?) { throw UnsupportedOperationException("ILocationManager.injectLocation is not hosted") }
    override fun requestListenerFlush(provider: String?, listener: android.location.ILocationListener?, requestCode: Int) { throw UnsupportedOperationException("ILocationManager.requestListenerFlush is not hosted") }
    override fun requestPendingIntentFlush(provider: String?, pendingIntent: android.app.PendingIntent?, requestCode: Int) { throw UnsupportedOperationException("ILocationManager.requestPendingIntentFlush is not hosted") }
    override fun requestGeofence(geofence: android.location.Geofence?, intent: android.app.PendingIntent?, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.requestGeofence is not hosted") }
    override fun removeGeofence(intent: android.app.PendingIntent?) { throw UnsupportedOperationException("ILocationManager.removeGeofence is not hosted") }
    override fun isGeocodeAvailable(): Boolean = throw UnsupportedOperationException("ILocationManager.isGeocodeAvailable is not hosted")
    override fun reverseGeocode(request: android.location.provider.ReverseGeocodeRequest?, callback: android.location.provider.IGeocodeCallback?) { throw UnsupportedOperationException("ILocationManager.reverseGeocode is not hosted") }
    override fun forwardGeocode(request: android.location.provider.ForwardGeocodeRequest?, callback: android.location.provider.IGeocodeCallback?) { throw UnsupportedOperationException("ILocationManager.forwardGeocode is not hosted") }
    override fun getGnssCapabilities(): android.location.GnssCapabilities? = throw UnsupportedOperationException("ILocationManager.getGnssCapabilities is not hosted")
    override fun getGnssYearOfHardware(): Int = throw UnsupportedOperationException("ILocationManager.getGnssYearOfHardware is not hosted")
    override fun getGnssHardwareModelName(): String? = throw UnsupportedOperationException("ILocationManager.getGnssHardwareModelName is not hosted")
    override fun getGnssAntennaInfos(): MutableList<android.location.GnssAntennaInfo?>? = throw UnsupportedOperationException("ILocationManager.getGnssAntennaInfos is not hosted")
    override fun registerGnssStatusCallback(callback: android.location.IGnssStatusListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.registerGnssStatusCallback is not hosted") }
    override fun unregisterGnssStatusCallback(callback: android.location.IGnssStatusListener?) { throw UnsupportedOperationException("ILocationManager.unregisterGnssStatusCallback is not hosted") }
    override fun registerGnssNmeaCallback(callback: android.location.IGnssNmeaListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.registerGnssNmeaCallback is not hosted") }
    override fun unregisterGnssNmeaCallback(callback: android.location.IGnssNmeaListener?) { throw UnsupportedOperationException("ILocationManager.unregisterGnssNmeaCallback is not hosted") }
    override fun addGnssMeasurementsListener(request: android.location.GnssMeasurementRequest?, listener: android.location.IGnssMeasurementsListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.addGnssMeasurementsListener is not hosted") }
    override fun removeGnssMeasurementsListener(listener: android.location.IGnssMeasurementsListener?) { throw UnsupportedOperationException("ILocationManager.removeGnssMeasurementsListener is not hosted") }
    override fun injectGnssMeasurementCorrections(corrections: android.location.GnssMeasurementCorrections?) { throw UnsupportedOperationException("ILocationManager.injectGnssMeasurementCorrections is not hosted") }
    override fun addGnssNavigationMessageListener(listener: android.location.IGnssNavigationMessageListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.addGnssNavigationMessageListener is not hosted") }
    override fun removeGnssNavigationMessageListener(listener: android.location.IGnssNavigationMessageListener?) { throw UnsupportedOperationException("ILocationManager.removeGnssNavigationMessageListener is not hosted") }
    override fun addGnssAntennaInfoListener(listener: android.location.IGnssAntennaInfoListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.addGnssAntennaInfoListener is not hosted") }
    override fun removeGnssAntennaInfoListener(listener: android.location.IGnssAntennaInfoListener?) { throw UnsupportedOperationException("ILocationManager.removeGnssAntennaInfoListener is not hosted") }
    override fun addProviderRequestListener(listener: android.location.provider.IProviderRequestListener?) { throw UnsupportedOperationException("ILocationManager.addProviderRequestListener is not hosted") }
    override fun removeProviderRequestListener(listener: android.location.provider.IProviderRequestListener?) { throw UnsupportedOperationException("ILocationManager.removeProviderRequestListener is not hosted") }
    override fun getGnssBatchSize(): Int = throw UnsupportedOperationException("ILocationManager.getGnssBatchSize is not hosted")
    override fun startGnssBatch(periodNanos: Long, listener: android.location.ILocationListener?, packageName: String?, attributionTag: String?, listenerId: String?) { throw UnsupportedOperationException("ILocationManager.startGnssBatch is not hosted") }
    override fun flushGnssBatch() { throw UnsupportedOperationException("ILocationManager.flushGnssBatch is not hosted") }
    override fun stopGnssBatch() { throw UnsupportedOperationException("ILocationManager.stopGnssBatch is not hosted") }
    override fun hasProvider(provider: String?): Boolean = throw UnsupportedOperationException("ILocationManager.hasProvider is not hosted")
    override fun getAllProviders(): MutableList<String?>? = throw UnsupportedOperationException("ILocationManager.getAllProviders is not hosted")
    override fun getProviders(criteria: android.location.Criteria?, enabledOnly: Boolean): MutableList<String?>? = throw UnsupportedOperationException("ILocationManager.getProviders is not hosted")
    override fun getBestProvider(criteria: android.location.Criteria?, enabledOnly: Boolean): String? = throw UnsupportedOperationException("ILocationManager.getBestProvider is not hosted")
    override fun getProviderProperties(provider: String?): android.location.provider.ProviderProperties? = throw UnsupportedOperationException("ILocationManager.getProviderProperties is not hosted")
    override fun isProviderPackage(provider: String?, packageName: String?, attributionTag: String?): Boolean = throw UnsupportedOperationException("ILocationManager.isProviderPackage is not hosted")
    override fun getProviderPackages(provider: String?): MutableList<String?>? = throw UnsupportedOperationException("ILocationManager.getProviderPackages is not hosted")
    override fun setExtraLocationControllerPackage(packageName: String?) { throw UnsupportedOperationException("ILocationManager.setExtraLocationControllerPackage is not hosted") }
    override fun getExtraLocationControllerPackage(): String? = throw UnsupportedOperationException("ILocationManager.getExtraLocationControllerPackage is not hosted")
    override fun setExtraLocationControllerPackageEnabled(enabled: Boolean) { throw UnsupportedOperationException("ILocationManager.setExtraLocationControllerPackageEnabled is not hosted") }
    override fun isExtraLocationControllerPackageEnabled(): Boolean = throw UnsupportedOperationException("ILocationManager.isExtraLocationControllerPackageEnabled is not hosted")
    override fun isProviderEnabledForUser(provider: String?, userId: Int): Boolean = throw UnsupportedOperationException("ILocationManager.isProviderEnabledForUser is not hosted")
    override fun isLocationEnabledForUser(userId: Int): Boolean = throw UnsupportedOperationException("ILocationManager.isLocationEnabledForUser is not hosted")
    override fun setLocationEnabledForUser(enabled: Boolean, userId: Int) { throw UnsupportedOperationException("ILocationManager.setLocationEnabledForUser is not hosted") }
    override fun isAdasGnssLocationEnabledForUser(userId: Int): Boolean = throw UnsupportedOperationException("ILocationManager.isAdasGnssLocationEnabledForUser is not hosted")
    override fun setAdasGnssLocationEnabledForUser(enabled: Boolean, userId: Int) { throw UnsupportedOperationException("ILocationManager.setAdasGnssLocationEnabledForUser is not hosted") }
    override fun isAutomotiveGnssSuspended(): Boolean = throw UnsupportedOperationException("ILocationManager.isAutomotiveGnssSuspended is not hosted")
    override fun setAutomotiveGnssSuspended(suspended: Boolean) { throw UnsupportedOperationException("ILocationManager.setAutomotiveGnssSuspended is not hosted") }
    override fun addTestProvider(name: String?, properties: android.location.provider.ProviderProperties?, locationTags: MutableList<String?>?, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.addTestProvider is not hosted") }
    override fun removeTestProvider(provider: String?, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.removeTestProvider is not hosted") }
    override fun setTestProviderLocation(provider: String?, location: android.location.Location?, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.setTestProviderLocation is not hosted") }
    override fun setTestProviderEnabled(provider: String?, enabled: Boolean, packageName: String?, attributionTag: String?) { throw UnsupportedOperationException("ILocationManager.setTestProviderEnabled is not hosted") }
    override fun getGnssTimeMillis(): android.location.LocationTime? = throw UnsupportedOperationException("ILocationManager.getGnssTimeMillis is not hosted")
    override fun sendExtraCommand(provider: String?, command: String?, extras: android.os.Bundle?) { throw UnsupportedOperationException("ILocationManager.sendExtraCommand is not hosted") }
    override fun getBackgroundThrottlingWhitelist(): Array<String?>? = throw UnsupportedOperationException("ILocationManager.getBackgroundThrottlingWhitelist is not hosted")
    override fun getIgnoreSettingsAllowlist(): android.os.PackageTagsList? = throw UnsupportedOperationException("ILocationManager.getIgnoreSettingsAllowlist is not hosted")
    override fun getAdasAllowlist(): android.os.PackageTagsList? = throw UnsupportedOperationException("ILocationManager.getAdasAllowlist is not hosted")
}
