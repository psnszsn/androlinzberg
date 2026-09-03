package dev.artzig.system

import android.Manifest
import android.content.pm.verify.domain.DomainOwner
import android.content.pm.verify.domain.DomainSet
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Bundle
import android.os.ServiceSpecificException
import android.os.UserHandle

/**
 * The session's IDomainVerificationManager over the real AIDL surface, published
 * as `Context.DOMAIN_VERIFICATION_SERVICE`.
 *
 * Source note: `com.android.server.pm.verify.domain.DomainVerificationService`
 * with no verification agent and no package that declared autoVerify web
 * domains: there is no verification info or user state for any package (null,
 * or a name-not-found for a package that is not installed), no domain owners,
 * state writes need the DOMAIN_VERIFICATION_AGENT permission and report an
 * invalid domain-set id, and the per-package link-handling and URI filter-group
 * settings are stored for the owning package.
 */
class HostedDomainVerificationService(
    private val packages: HostedPackageManagerService,
    private val permissions: HostedPermissions,
) : UnsupportedDomainVerificationManager() {
    private val lock = Any()
    private val linkHandlingAllowed = HashMap<String, Boolean>()
    private val filterGroups = HashMap<String, Bundle>()

    private fun requireInstalled(packageName: String?) {
        if (packageName == null || packages.getApplicationInfo(packageName, 0L, UserHandle.USER_SYSTEM) == null) {
            throw ServiceSpecificException(DomainVerificationManager.INTERNAL_ERROR_NAME_NOT_FOUND, packageName)
        }
    }

    override fun queryValidVerificationPackageNames(): MutableList<String?>? {
        permissions.enforce(Manifest.permission.DOMAIN_VERIFICATION_AGENT, "queryValidVerificationPackageNames")
        return ArrayList()
    }
    override fun getDomainVerificationInfo(packageName: String?): DomainVerificationInfo? {
        requireInstalled(packageName)
        return null
    }
    override fun getDomainVerificationUserState(packageName: String?, userId: Int): DomainVerificationUserState? {
        requireInstalled(packageName)
        return null
    }
    override fun getOwnersForDomain(domain: String?, userId: Int): MutableList<DomainOwner?>? = ArrayList()
    override fun setDomainVerificationStatus(domainSetId: String?, domains: DomainSet?, state: Int): Int {
        permissions.enforce(Manifest.permission.DOMAIN_VERIFICATION_AGENT, "setDomainVerificationStatus")
        return DomainVerificationManager.ERROR_DOMAIN_SET_ID_INVALID
    }
    override fun setDomainVerificationLinkHandlingAllowed(packageName: String?, allowed: Boolean, userId: Int) {
        if (!permissions.isCallerPackage(packageName)) permissions.enforce(Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION, "setDomainVerificationLinkHandlingAllowed")
        requireInstalled(packageName)
        synchronized(lock) { linkHandlingAllowed[packageName!!] = allowed }
    }
    override fun setDomainVerificationUserSelection(domainSetId: String?, domains: DomainSet?, enabled: Boolean, userId: Int): Int {
        permissions.enforce(Manifest.permission.UPDATE_DOMAIN_VERIFICATION_USER_SELECTION, "setDomainVerificationUserSelection")
        return DomainVerificationManager.ERROR_DOMAIN_SET_ID_INVALID
    }
    override fun setUriRelativeFilterGroups(packageName: String?, domainToGroupsBundle: Bundle?) {
        permissions.enforceCallerPackage(packageName, "setUriRelativeFilterGroups")
        synchronized(lock) { filterGroups[packageName!!] = Bundle(domainToGroupsBundle ?: Bundle()) }
    }
    override fun getUriRelativeFilterGroups(packageName: String?, domains: MutableList<String?>?): Bundle? {
        requireInstalled(packageName)
        val stored = synchronized(lock) { filterGroups[packageName] } ?: return Bundle()
        val result = Bundle()
        for (domain in domains ?: return Bundle()) {
            if (domain != null && stored.containsKey(domain)) result.putParcelableArrayList(domain, stored.getParcelableArrayList(domain))
        }
        return result
    }

}
