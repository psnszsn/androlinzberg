package dev.artzig.system

import android.app.PendingIntent
import android.location.Criteria
import android.location.GnssCapabilities
import android.location.ILocationCallback
import android.location.ILocationListener
import android.location.LastLocationRequest
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.location.provider.ProviderProperties
import android.os.Binder
import android.os.IBinder
import android.os.ICancellationSignal
import android.os.Process
import android.os.RemoteException
import android.util.Log

/**
 * The session's location manager over the real `ILocationManager` surface,
 * published as `Context.LOCATION_SERVICE`.
 *
 * Source note: `com.android.server.location.LocationManagerService` and its
 * `LocationProviderManager`s for a device without location providers: only the
 * passive provider exists (it always does in AOSP), location is off for the
 * user, so `getProviders(enabledOnly)` is empty, `isProviderEnabled` is false,
 * every last-known location is null, `getCurrentLocation` completes with null,
 * listener and PendingIntent registrations are kept (and never delivered),
 * GNSS capabilities are empty and mock providers keep the MOCK_LOCATION gate.
 */
class HostedLocationManagerService : android.location.ILocationManager.Stub(), android.location.ILocationManager by unsupported<android.location.ILocationManager>() {
    override fun asBinder(): android.os.IBinder = this

    @Volatile var packageService: HostedPackageManagerService? = null

    private val lock = Any()
    private val listeners = LinkedHashMap<IBinder, Pair<String, String?>>()
    private val pendingIntents = LinkedHashMap<PendingIntent, String>()
    private var locationEnabled = false

    private fun enforceSystem(what: String) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("$what requires android.permission.WRITE_SECURE_SETTINGS")
    }

    private fun validateProvider(provider: String?): String {
        requireNotNull(provider) { "invalid provider: null" }
        return provider
    }

    // ------------------------------------------------------------ providers

    override fun hasProvider(provider: String?): Boolean = provider == LocationManager.PASSIVE_PROVIDER
    override fun getAllProviders(): MutableList<String?>? = mutableListOf(LocationManager.PASSIVE_PROVIDER)
    override fun getProviders(criteria: Criteria?, enabledOnly: Boolean): MutableList<String?>? =
        if (enabledOnly && !synchronized(lock) { locationEnabled }) ArrayList() else mutableListOf(LocationManager.PASSIVE_PROVIDER)
    override fun getBestProvider(criteria: Criteria?, enabledOnly: Boolean): String? = getProviders(criteria, enabledOnly)?.firstOrNull()
    /** PassiveLocationProviderManager's properties. */
    override fun getProviderProperties(provider: String?): ProviderProperties? =
        if (provider == LocationManager.PASSIVE_PROVIDER) ProviderProperties.Builder()
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_COARSE)
            .build()
        else null
    override fun isProviderPackage(provider: String?, packageName: String?, attributionTag: String?): Boolean = false
    override fun getProviderPackages(provider: String?): MutableList<String?>? = ArrayList()
    override fun isProviderEnabledForUser(provider: String?, userId: Int): Boolean =
        provider == LocationManager.PASSIVE_PROVIDER && synchronized(lock) { locationEnabled }
    override fun isLocationEnabledForUser(userId: Int): Boolean = synchronized(lock) { locationEnabled }
    override fun setLocationEnabledForUser(enabled: Boolean, userId: Int) {
        enforceSystem("setLocationEnabledForUser")
        synchronized(lock) { locationEnabled = enabled }
    }
    override fun isAdasGnssLocationEnabledForUser(userId: Int): Boolean = false
    override fun setAdasGnssLocationEnabledForUser(enabled: Boolean, userId: Int) = enforceSystem("setAdasGnssLocationEnabledForUser")
    override fun isAutomotiveGnssSuspended(): Boolean = false
    override fun setAutomotiveGnssSuspended(suspended: Boolean) = enforceSystem("setAutomotiveGnssSuspended")

    // ------------------------------------------------------------ locations

    override fun getLastLocation(provider: String?, request: LastLocationRequest?, packageName: String?, attributionTag: String?): Location? {
        validateProvider(provider)
        return null
    }

    /** LocationProviderManager.getCurrentLocation with the provider disabled: the callback completes with null at once. */
    override fun getCurrentLocation(provider: String?, request: LocationRequest?, callback: ILocationCallback?, packageName: String?, attributionTag: String?, listenerId: String?): ICancellationSignal? {
        validateProvider(provider)
        try {
            callback?.onLocation(null)
        } catch (e: RemoteException) {
            // caller gone
        }
        return null
    }

    override fun registerLocationListener(provider: String?, request: LocationRequest?, listener: ILocationListener?, packageName: String?, attributionTag: String?, listenerId: String?) {
        val p = validateProvider(provider)
        requireNotNull(request) { "invalid null request" }
        val binder = listener?.asBinder() ?: throw IllegalArgumentException("invalid null listener")
        synchronized(lock) { listeners[binder] = p to packageName }
        Log.i(TAG, "location listener registered (never delivered): pkg=$packageName provider=$p")
    }

    override fun unregisterLocationListener(listener: ILocationListener?) {
        synchronized(lock) { listeners.remove(listener?.asBinder() ?: return) }
    }

    override fun registerLocationPendingIntent(provider: String?, request: LocationRequest?, pendingIntent: PendingIntent?, packageName: String?, attributionTag: String?) {
        val p = validateProvider(provider)
        requireNotNull(pendingIntent) { "invalid null pending intent" }
        synchronized(lock) { pendingIntents[pendingIntent] = p }
    }

    override fun unregisterLocationPendingIntent(pendingIntent: PendingIntent?) {
        synchronized(lock) { pendingIntents.remove(pendingIntent ?: return) }
    }

    override fun injectLocation(location: Location?) = enforceSystem("injectLocation")
    override fun requestListenerFlush(provider: String?, listener: ILocationListener?, requestCode: Int) {
        try {
            listener?.onFlushComplete(requestCode)
        } catch (e: RemoteException) {
        }
    }
    override fun requestPendingIntentFlush(provider: String?, pendingIntent: PendingIntent?, requestCode: Int) {}
    override fun requestGeofence(geofence: android.location.Geofence?, intent: PendingIntent?, packageName: String?, attributionTag: String?) {
        requireNotNull(geofence) { "invalid null geofence" }
        requireNotNull(intent) { "invalid null intent" }
    }
    override fun removeGeofence(intent: PendingIntent?) {}

    // ------------------------------------------------------------ geocoding and GNSS (absent)

    override fun isGeocodeAvailable(): Boolean = false
    override fun reverseGeocode(request: android.location.provider.ReverseGeocodeRequest?, callback: android.location.provider.IGeocodeCallback?) {
        try {
            callback?.onError("no geocoder")
        } catch (e: RemoteException) {
        }
    }
    override fun forwardGeocode(request: android.location.provider.ForwardGeocodeRequest?, callback: android.location.provider.IGeocodeCallback?) {
        try {
            callback?.onError("no geocoder")
        } catch (e: RemoteException) {
        }
    }
    override fun getGnssCapabilities(): GnssCapabilities? = GnssCapabilities.Builder().build()
    override fun getGnssYearOfHardware(): Int = 0
    override fun getGnssHardwareModelName(): String? = null
    override fun getGnssAntennaInfos(): MutableList<android.location.GnssAntennaInfo?>? = null
    override fun registerGnssStatusCallback(callback: android.location.IGnssStatusListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun unregisterGnssStatusCallback(callback: android.location.IGnssStatusListener?) {}
    override fun registerGnssNmeaCallback(callback: android.location.IGnssNmeaListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun unregisterGnssNmeaCallback(callback: android.location.IGnssNmeaListener?) {}
    override fun addGnssMeasurementsListener(request: android.location.GnssMeasurementRequest?, listener: android.location.IGnssMeasurementsListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun removeGnssMeasurementsListener(listener: android.location.IGnssMeasurementsListener?) {}
    override fun injectGnssMeasurementCorrections(corrections: android.location.GnssMeasurementCorrections?) = enforceSystem("injectGnssMeasurementCorrections")
    override fun addGnssNavigationMessageListener(listener: android.location.IGnssNavigationMessageListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun removeGnssNavigationMessageListener(listener: android.location.IGnssNavigationMessageListener?) {}
    override fun addGnssAntennaInfoListener(listener: android.location.IGnssAntennaInfoListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun removeGnssAntennaInfoListener(listener: android.location.IGnssAntennaInfoListener?) {}
    override fun addProviderRequestListener(listener: android.location.provider.IProviderRequestListener?) = enforceSystem("addProviderRequestListener")
    override fun removeProviderRequestListener(listener: android.location.provider.IProviderRequestListener?) {}
    override fun getGnssBatchSize(): Int = 0
    override fun startGnssBatch(periodNanos: Long, listener: ILocationListener?, packageName: String?, attributionTag: String?, listenerId: String?) {}
    override fun flushGnssBatch() {}
    override fun stopGnssBatch() {}
    override fun getGnssTimeMillis(): android.location.LocationTime? = null
    override fun sendExtraCommand(provider: String?, command: String?, extras: android.os.Bundle?) {
        validateProvider(provider)
        requireNotNull(command) { "invalid null command" }
    }

    // ------------------------------------------------------------ mock providers and policy

    /** LocationManagerService: mock providers need the MOCK_LOCATION app op (never granted here). */
    private fun enforceMockLocation(what: String) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw SecurityException("$what requires the MOCK_LOCATION app op")
    }
    override fun addTestProvider(name: String?, properties: ProviderProperties?, locationTags: MutableList<String?>?, packageName: String?, attributionTag: String?) = enforceMockLocation("addTestProvider")
    override fun removeTestProvider(provider: String?, packageName: String?, attributionTag: String?) = enforceMockLocation("removeTestProvider")
    override fun setTestProviderLocation(provider: String?, location: Location?, packageName: String?, attributionTag: String?) = enforceMockLocation("setTestProviderLocation")
    override fun setTestProviderEnabled(provider: String?, enabled: Boolean, packageName: String?, attributionTag: String?) = enforceMockLocation("setTestProviderEnabled")
    override fun setExtraLocationControllerPackage(packageName: String?) = enforceSystem("setExtraLocationControllerPackage")
    override fun getExtraLocationControllerPackage(): String? = null
    override fun setExtraLocationControllerPackageEnabled(enabled: Boolean) = enforceSystem("setExtraLocationControllerPackageEnabled")
    override fun isExtraLocationControllerPackageEnabled(): Boolean = false
    override fun getBackgroundThrottlingWhitelist(): Array<String?>? = arrayOfNulls(0)
    override fun getIgnoreSettingsAllowlist(): android.os.PackageTagsList? = android.os.PackageTagsList.Builder().build()
    override fun getAdasAllowlist(): android.os.PackageTagsList? = android.os.PackageTagsList.Builder().build()

    private companion object {
        const val TAG = "HostedLocationManager"
    }
}
