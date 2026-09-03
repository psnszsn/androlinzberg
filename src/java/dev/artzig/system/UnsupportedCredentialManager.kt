@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ICredentialManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedCredentialManager : android.credentials.ICredentialManager.Stub() {
    override fun executeGetCredential(request: android.credentials.GetCredentialRequest?, callback: android.credentials.IGetCredentialCallback?, callingPackage: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ICredentialManager.executeGetCredential is not hosted")
    override fun executePrepareGetCredential(request: android.credentials.GetCredentialRequest?, prepareGetCredentialCallback: android.credentials.IPrepareGetCredentialCallback?, getCredentialCallback: android.credentials.IGetCredentialCallback?, callingPackage: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ICredentialManager.executePrepareGetCredential is not hosted")
    override fun executeCreateCredential(request: android.credentials.CreateCredentialRequest?, callback: android.credentials.ICreateCredentialCallback?, callingPackage: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ICredentialManager.executeCreateCredential is not hosted")
    override fun getCandidateCredentials(request: android.credentials.GetCredentialRequest?, callback: android.credentials.IGetCandidateCredentialsCallback?, clientCallback: android.os.IBinder?, callingPackage: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ICredentialManager.getCandidateCredentials is not hosted")
    override fun clearCredentialState(request: android.credentials.ClearCredentialStateRequest?, callback: android.credentials.IClearCredentialStateCallback?, callingPackage: String?): android.os.ICancellationSignal? = throw UnsupportedOperationException("ICredentialManager.clearCredentialState is not hosted")
    override fun setEnabledProviders(primaryProviders: MutableList<String?>?, providers: MutableList<String?>?, userId: Int, callback: android.credentials.ISetEnabledProvidersCallback?) { throw UnsupportedOperationException("ICredentialManager.setEnabledProviders is not hosted") }
    override fun registerCredentialDescription(request: android.credentials.RegisterCredentialDescriptionRequest?, callingPackage: String?) { throw UnsupportedOperationException("ICredentialManager.registerCredentialDescription is not hosted") }
    override fun unregisterCredentialDescription(request: android.credentials.UnregisterCredentialDescriptionRequest?, callingPackage: String?) { throw UnsupportedOperationException("ICredentialManager.unregisterCredentialDescription is not hosted") }
    override fun isEnabledCredentialProviderService(componentName: android.content.ComponentName?, callingPackage: String?): Boolean = throw UnsupportedOperationException("ICredentialManager.isEnabledCredentialProviderService is not hosted")
    override fun getCredentialProviderServices(userId: Int, providerFilter: Int): MutableList<android.credentials.CredentialProviderInfo?>? = throw UnsupportedOperationException("ICredentialManager.getCredentialProviderServices is not hosted")
    override fun getCredentialProviderServicesForTesting(providerFilter: Int): MutableList<android.credentials.CredentialProviderInfo?>? = throw UnsupportedOperationException("ICredentialManager.getCredentialProviderServicesForTesting is not hosted")
    override fun isServiceEnabled(): Boolean = throw UnsupportedOperationException("ICredentialManager.isServiceEnabled is not hosted")
}
