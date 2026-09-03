package dev.artzig.system

import android.Manifest
import android.content.ComponentName
import android.credentials.ClearCredentialStateException
import android.credentials.ClearCredentialStateRequest
import android.credentials.CreateCredentialException
import android.credentials.CreateCredentialRequest
import android.credentials.CredentialProviderInfo
import android.credentials.GetCredentialException
import android.credentials.GetCredentialRequest
import android.credentials.IClearCredentialStateCallback
import android.credentials.ICreateCredentialCallback
import android.credentials.IGetCandidateCredentialsCallback
import android.credentials.IGetCredentialCallback
import android.credentials.IPrepareGetCredentialCallback
import android.credentials.RegisterCredentialDescriptionRequest
import android.credentials.UnregisterCredentialDescriptionRequest
import android.os.CancellationSignal
import android.os.IBinder
import android.os.ICancellationSignal
import android.os.RemoteException

/**
 * The session's ICredentialManager over the real AIDL surface, published as
 * `Context.CREDENTIAL_SERVICE`.
 *
 * Source note: `com.android.server.credentials.CredentialManagerService` for a
 * device with no credential provider services installed: a get request ends with
 * TYPE_NO_CREDENTIAL, a create request with TYPE_NO_CREATE_OPTIONS, a clear
 * request with TYPE_UNKNOWN, the provider queries are empty, and the service
 * itself is enabled (the DeviceConfig default). Each call hands back a
 * cancellation transport as AOSP does.
 */
class HostedCredentialManagerService(private val permissions: HostedPermissions) : android.credentials.ICredentialManager.Stub(), android.credentials.ICredentialManager by unsupported<android.credentials.ICredentialManager>() {
    override fun asBinder(): android.os.IBinder = this

    private fun transport(): ICancellationSignal = CancellationSignal.createTransport()

    override fun executeGetCredential(request: GetCredentialRequest?, callback: IGetCredentialCallback?, callingPackage: String?): ICancellationSignal? {
        permissions.enforceCallerPackage(callingPackage, "executeGetCredential")
        try { callback?.onError(GetCredentialException.TYPE_NO_CREDENTIAL, "No credential available: no credential provider on this host") } catch (e: RemoteException) {}
        return transport()
    }
    override fun executePrepareGetCredential(request: GetCredentialRequest?, prepareGetCredentialCallback: IPrepareGetCredentialCallback?, getCredentialCallback: IGetCredentialCallback?, callingPackage: String?): ICancellationSignal? {
        permissions.enforceCallerPackage(callingPackage, "executePrepareGetCredential")
        try { prepareGetCredentialCallback?.onError(GetCredentialException.TYPE_NO_CREDENTIAL, "No credential available: no credential provider on this host") } catch (e: RemoteException) {}
        return transport()
    }
    override fun executeCreateCredential(request: CreateCredentialRequest?, callback: ICreateCredentialCallback?, callingPackage: String?): ICancellationSignal? {
        permissions.enforceCallerPackage(callingPackage, "executeCreateCredential")
        try { callback?.onError(CreateCredentialException.TYPE_NO_CREATE_OPTIONS, "No create options available: no credential provider on this host") } catch (e: RemoteException) {}
        return transport()
    }
    override fun getCandidateCredentials(request: GetCredentialRequest?, callback: IGetCandidateCredentialsCallback?, clientCallback: IBinder?, callingPackage: String?): ICancellationSignal? {
        permissions.enforceCallerPackage(callingPackage, "getCandidateCredentials")
        try { callback?.onError(GetCredentialException.TYPE_NO_CREDENTIAL, "No credential available: no credential provider on this host") } catch (e: RemoteException) {}
        return transport()
    }
    override fun clearCredentialState(request: ClearCredentialStateRequest?, callback: IClearCredentialStateCallback?, callingPackage: String?): ICancellationSignal? {
        permissions.enforceCallerPackage(callingPackage, "clearCredentialState")
        try { callback?.onError(ClearCredentialStateException.TYPE_UNKNOWN, "No credential provider on this host") } catch (e: RemoteException) {}
        return transport()
    }
    override fun registerCredentialDescription(request: RegisterCredentialDescriptionRequest?, callingPackage: String?) = permissions.enforceCallerPackage(callingPackage, "registerCredentialDescription")
    override fun unregisterCredentialDescription(request: UnregisterCredentialDescriptionRequest?, callingPackage: String?) = permissions.enforceCallerPackage(callingPackage, "unregisterCredentialDescription")
    override fun isEnabledCredentialProviderService(componentName: ComponentName?, callingPackage: String?): Boolean = false
    override fun getCredentialProviderServices(userId: Int, providerFilter: Int): MutableList<CredentialProviderInfo?>? {
        permissions.enforce(Manifest.permission.QUERY_ALL_PACKAGES, "getCredentialProviderServices")
        return ArrayList()
    }
    override fun getCredentialProviderServicesForTesting(providerFilter: Int): MutableList<CredentialProviderInfo?>? {
        permissions.enforce(Manifest.permission.QUERY_ALL_PACKAGES, "getCredentialProviderServicesForTesting")
        return ArrayList()
    }
    override fun isServiceEnabled(): Boolean = true
}
