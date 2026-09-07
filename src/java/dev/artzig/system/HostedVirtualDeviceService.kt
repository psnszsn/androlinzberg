package dev.artzig.system

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import com.android.server.LocalServices
import com.android.server.companion.virtual.VirtualDeviceManagerInternal
import android.companion.AssociationRequest
import android.companion.ICompanionDeviceManager
import android.companion.virtual.*
import android.content.AttributionSource
import android.content.Context
import android.os.*
import java.util.Objects

/** Pinned VirtualDeviceManagerService.VirtualDeviceManagerImpl with no created virtual devices.
 * Keep normal default-device queries, listener ownership and CDM association validation behind
 * Binder. Creating a device requires a virtual display/input/camera backend; that boundary reports
 * an unsupported operation after the stock permission, association and attribution checks. */
internal class HostedVirtualDeviceService(
    private val context: Context,
    private val localService: VirtualDeviceManagerInternal? = LocalServices.getService(VirtualDeviceManagerInternal::class.java),
) :
    IVirtualDeviceManager.Stub(PermissionEnforcer.fromContext(context)) {
    private val listeners = RemoteCallbackList<IVirtualDeviceListener>()
    private val profiles = setOf(AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION,
        AssociationRequest.DEVICE_PROFILE_APP_STREAMING, AssociationRequest.DEVICE_PROFILE_NEARBY_DEVICE_STREAMING,
        AssociationRequest.DEVICE_PROFILE_VIRTUAL_DEVICE)
    private fun <T : Any> nn(value: T?): T = Objects.requireNonNull(value)!!
    private fun associations(user: Int): List<AssociationInfo> {
        val identity = Binder.clearCallingIdentity()
        try {
            val service = ICompanionDeviceManager.Stub.asInterface(ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE))
            return service.getAllAssociationsForUser(user)
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private val associationsLock = Any()
    private var activeAssociations: Map<String, AssociationInfo> = emptyMap()

    // VirtualDeviceManagerService.onStart/onCdmAssociationsChanged: publish the
    // initial snapshot, then consume CDM's executor callbacks and notify removals.
    init {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager != null) {
            onAssociationsChanged(manager.getAllAssociations(UserHandle.USER_ALL))
            manager.addOnAssociationsChangedListener(context.mainExecutor, ::onAssociationsChanged, UserHandle.USER_ALL)
        }
    }
    private fun onAssociationsChanged(values: List<AssociationInfo>) {
        val next = values.filter { it.deviceProfile in profiles && !it.isRevoked }
            .associateBy { "companion:${it.id}" }
        val removed = synchronized(associationsLock) {
            val removed = activeAssociations.keys - next.keys
            activeAssociations = next
            removed
        }
        if (removed.isNotEmpty()) localService?.onPersistentDeviceIdsRemoved(removed)
    }
    private fun persistentAssociations(): Map<String, AssociationInfo> =
        synchronized(associationsLock) { activeAssociations }

    override fun createVirtualDevice(token: IBinder?, attributionSource: AttributionSource?, associationId: Int,
        params: VirtualDeviceParams?, activityListener: IVirtualDeviceActivityListener?,
        soundEffectListener: IVirtualDeviceSoundEffectListener?): IVirtualDevice {
        createVirtualDevice_enforcePermission()
        nn(activityListener); nn(soundEffectListener)
        val attribution = nn(attributionSource)
        val user = Binder.getCallingUserHandle().identifier
        val association = associations(user).firstOrNull {
            it.belongsToPackage(user, attribution.packageName) && it.id == associationId
        } ?: throw IllegalArgumentException("No association with ID $associationId")
        require(association.deviceProfile in profiles) {
            "Unsupported CDM Association device profile ${association.deviceProfile} for virtual device creation."
        }
        attribution.enforceCallingUid()
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages?.contains(attribution.packageName) != true) throw SecurityException(
            "Package name ${attribution.packageName} does not belong to calling uid ${Binder.getCallingUid()}")
        nn(params)
        return unsupported<IVirtualDeviceManager>().createVirtualDevice(token, attribution, associationId,
            params, activityListener, soundEffectListener)
    }
    override fun getVirtualDevices(): List<VirtualDevice> = ArrayList()
    override fun getVirtualDevice(deviceId: Int): VirtualDevice? = null
    override fun registerVirtualDeviceListener(listener: IVirtualDeviceListener?) { listeners.register(nn(listener)) }
    override fun unregisterVirtualDeviceListener(listener: IVirtualDeviceListener?) { listeners.unregister(nn(listener)) }
    override fun getDeviceIdForDisplayId(displayId: Int): Int = Context.DEVICE_ID_DEFAULT
    override fun getDisplayNameForPersistentDeviceId(persistentDeviceId: String?): CharSequence? =
        persistentAssociations()[persistentDeviceId]?.displayName
    override fun isValidVirtualDeviceId(deviceId: Int): Boolean = false
    override fun getDevicePolicy(deviceId: Int, policyType: Int): Int = VirtualDeviceParams.DEVICE_POLICY_DEFAULT
    override fun getAudioPlaybackSessionId(deviceId: Int): Int = android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
    override fun getAudioRecordingSessionId(deviceId: Int): Int = android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
    override fun playSoundEffect(deviceId: Int, effectType: Int) { /* No matching virtual device owns a sound listener. */ }
    override fun isVirtualDeviceOwnedMirrorDisplay(displayId: Int): Boolean = false
    override fun getAllPersistentDeviceIds(): List<String> = persistentAssociations().keys.toList()
}
