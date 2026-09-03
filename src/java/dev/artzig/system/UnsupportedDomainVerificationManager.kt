@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IDomainVerificationManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedDomainVerificationManager : android.content.pm.verify.domain.IDomainVerificationManager.Stub() {
    override fun queryValidVerificationPackageNames(): MutableList<String?>? = throw UnsupportedOperationException("IDomainVerificationManager.queryValidVerificationPackageNames is not hosted")
    override fun getDomainVerificationInfo(packageName: String?): android.content.pm.verify.domain.DomainVerificationInfo? = throw UnsupportedOperationException("IDomainVerificationManager.getDomainVerificationInfo is not hosted")
    override fun getDomainVerificationUserState(packageName: String?, userId: Int): android.content.pm.verify.domain.DomainVerificationUserState? = throw UnsupportedOperationException("IDomainVerificationManager.getDomainVerificationUserState is not hosted")
    override fun getOwnersForDomain(domain: String?, userId: Int): MutableList<android.content.pm.verify.domain.DomainOwner?>? = throw UnsupportedOperationException("IDomainVerificationManager.getOwnersForDomain is not hosted")
    override fun setDomainVerificationStatus(domainSetId: String?, domains: android.content.pm.verify.domain.DomainSet?, state: Int): Int = throw UnsupportedOperationException("IDomainVerificationManager.setDomainVerificationStatus is not hosted")
    override fun setDomainVerificationLinkHandlingAllowed(packageName: String?, allowed: Boolean, userId: Int) { throw UnsupportedOperationException("IDomainVerificationManager.setDomainVerificationLinkHandlingAllowed is not hosted") }
    override fun setDomainVerificationUserSelection(domainSetId: String?, domains: android.content.pm.verify.domain.DomainSet?, enabled: Boolean, userId: Int): Int = throw UnsupportedOperationException("IDomainVerificationManager.setDomainVerificationUserSelection is not hosted")
    override fun setUriRelativeFilterGroups(packageName: String?, domainToGroupsBundle: android.os.Bundle?) { throw UnsupportedOperationException("IDomainVerificationManager.setUriRelativeFilterGroups is not hosted") }
    override fun getUriRelativeFilterGroups(packageName: String?, domains: MutableList<String?>?): android.os.Bundle? = throw UnsupportedOperationException("IDomainVerificationManager.getUriRelativeFilterGroups is not hosted")
}
