package dev.artzig.system

import android.Manifest.permission.MANAGE_COMPANION_DEVICES
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.IAssociationRequestCallback
import android.companion.ICompanionDeviceManager
import android.companion.IOnAssociationsChangedListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import com.android.server.companion.association.AssociationDiskStore
import com.android.server.companion.association.AssociationStore
import com.android.server.companion.utils.PermissionsUtils

/**
 * CompanionDeviceManagerService / AssociationRequestsProcessor's immediate,
 * self-managed association path. Reuse the stock store for IDs, persistence,
 * caller checks, Binder listener death and change notification ordering.
 * The complete service also requires device discovery, consent UI, role grants,
 * transport and presence binding; unserved operations use the Binder diagnostic
 * delegate instead of claiming those interactions succeeded.
 */
class HostedCompanionDeviceService(private val context: Context) :
    ICompanionDeviceManager.Stub(), ICompanionDeviceManager by unsupported<ICompanionDeviceManager>() {
    private val store = AssociationStore(context, UserManager.get(context), AssociationDiskStore())
    private val mutationLock = Any()

    init { store.refreshCache() }

    private fun owner(packageName: String, userId: Int) =
        PermissionsUtils.enforceCallerCanManageAssociationsForPackage(context, userId, packageName, null)

    private fun manager(userId: Int) {
        context.enforceCallingOrSelfPermission(MANAGE_COMPANION_DEVICES, "companion associations")
        PermissionsUtils.enforceCallerIsSystemOrCanInteractWithUserId(context, userId)
    }

    override fun getAssociations(callingPackage: String, userId: Int): MutableList<AssociationInfo> {
        owner(callingPackage, userId)
        return store.getActiveAssociationsByPackage(userId, callingPackage)
    }

    override fun getAllAssociationsForUser(userId: Int): MutableList<AssociationInfo> {
        manager(userId)
        return if (userId == UserHandle.USER_ALL) store.activeAssociations
        else store.getActiveAssociationsByUser(userId)
    }

    override fun associate(request: AssociationRequest, callback: IAssociationRequestCallback,
        callingPackage: String, userId: Int) {
        owner(callingPackage, userId)
        if (request.isSelfManaged) java.util.Objects.requireNonNull(request.displayName,
            "AssociationRequest.displayName MUST NOT be null.")
        val uid = context.packageManager.getPackageUidAsUser(callingPackage, userId)
        PermissionsUtils.enforcePermissionForCreatingAssociation(context, request, uid)
        // PackageUtils.enforceUsesCompanionDeviceFeature. Its other entry points
        // require enhanced confirmation and device-discovery infrastructure.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            val info = context.packageManager.getPackageInfoAsUser(callingPackage,
                PackageManager.GET_CONFIGURATIONS, userId)
            check(info.reqFeatures?.any { it.name == PackageManager.FEATURE_COMPANION_DEVICE_SETUP } == true) {
                "Must declare uses-feature ${PackageManager.FEATURE_COMPANION_DEVICE_SETUP} in manifest to use this API"
            }
        }
        if (!request.isSelfManaged || request.isForceConfirmation || request.deviceProfile != null ||
            request.isSkipRoleGrant) {
            unsupported<ICompanionDeviceManager>().associate(request, callback, callingPackage, userId)
            return
        }
        val association = synchronized(mutationLock) {
            AssociationInfo.Builder(store.nextId, userId, callingPackage)
                .setDisplayName(request.displayName).setSelfManaged(true)
                .setTimeApproved(System.currentTimeMillis()).setLastTimeConnected(Long.MAX_VALUE)
                .setDeviceIcon(request.deviceIcon).build().also { store.addAssociation(it) }
        }
        // A dead callback does not undo a completed association in AOSP.
        try { callback.onAssociationCreated(association) } catch (_: RemoteException) {}
    }

    override fun disassociate(associationId: Int) {
        synchronized(mutationLock) {
            val association = store.getAssociationWithCallerChecks(associationId)
            if (association.deviceProfile != null || !association.isSelfManaged) {
                unsupported<ICompanionDeviceManager>().disassociate(associationId)
                return
            }
            store.removeAssociation(associationId, "api")
        }
    }

    override fun addOnAssociationsChangedListener(listener: IOnAssociationsChangedListener, userId: Int) {
        manager(userId)
        store.registerRemoteListener(listener, userId)
    }

    override fun removeOnAssociationsChangedListener(listener: IOnAssociationsChangedListener, userId: Int) {
        manager(userId)
        store.unregisterRemoteListener(listener)
    }

    override fun enableSystemDataSync(associationId: Int, flags: Int) = updateSync(associationId, flags, true)
    override fun disableSystemDataSync(associationId: Int, flags: Int) = updateSync(associationId, flags, false)

    private fun updateSync(associationId: Int, flags: Int, enabled: Boolean) {
        synchronized(mutationLock) {
            val association = store.getAssociationWithCallerChecks(associationId)
            val current = association.systemDataSyncFlags
            store.updateAssociation(AssociationInfo.Builder(association)
                .setSystemDataSyncFlags(if (enabled) current or flags else current and flags.inv()).build())
        }
    }

    override fun setDeviceId(associationId: Int, deviceId: android.companion.DeviceId?) {
        synchronized(mutationLock) {
            store.updateAssociation(AssociationInfo.Builder(store.getAssociationWithCallerChecks(associationId))
                .setDeviceId(deviceId).build())
        }
    }

    override fun canPairWithoutPrompt(packageName: String, deviceMacAddress: String, userId: Int): Boolean {
        val association = store.getFirstAssociationByAddress(userId, packageName, deviceMacAddress) ?: return false
        return System.currentTimeMillis() - association.timeApprovedMs < 10 * 60 * 1000
    }
}
