package dev.artzig.system

import android.Manifest
import android.app.Flags
import android.app.GrammaticalInflectionManager
import android.app.IGrammaticalInflectionManager
import android.content.AttributionSource
import android.content.res.Configuration
import android.os.Binder
import android.os.Process

/**
 * The session's grammatical-inflection service on the stock
 * `IGrammaticalInflectionManager` surface.
 *
 * Source note: follows `com.android.server.grammaticalinflection.GrammaticalInflectionService`
 * (`setRequestedApplicationGrammaticalGender`, `setSystemWideGrammaticalGender`,
 * `getSystemGrammaticalGender`, and `canGetSystemGrammaticalGender`). The full service cannot run
 * without ActivityTaskManager's PackageConfigPersister, encrypted per-user storage, backup, and
 * automotive multi-user policy. This single-user host keeps the same session state and delegates
 * configuration precedence, delivery, and process-attach replay to HostedActivityManagerService;
 * backup and persistence across separate host sessions are unavailable.
 */
class HostedGrammaticalInflectionService(
    private val activityManager: HostedActivityManagerService,
    private val permissions: HostedPermissions,
) : IGrammaticalInflectionManager.Stub() {
    private val lock = Any()
    private val systemGenders = HashMap<Int, Int>()

    override fun setRequestedApplicationGrammaticalGender(
        appPackageName: String?,
        userId: Int,
        gender: Int,
    ) {
        val packageName = appPackageName ?: return
        activityManager.setPackageGrammaticalGender(packageName, userId, gender)
    }

    override fun setSystemWideGrammaticalGender(gender: Int, userId: Int) {
        enforceChangeConfiguration()
        if (!Flags.systemTermsOfAddressEnabled()) return
        if (!GrammaticalInflectionManager.VALID_GRAMMATICAL_GENDER_VALUES.contains(gender)) {
            throw IllegalArgumentException("Unknown grammatical gender")
        }
        synchronized(lock) { systemGenders[userId] = gender }
        activityManager.setGlobalGrammaticalGender(userId, gender)
    }

    override fun getSystemGrammaticalGender(attributionSource: AttributionSource?, userId: Int): Int {
        if (!canReadSystemGender(attributionSource)) {
            throw SecurityException(
                "AttributionSource: $attributionSource does not have READ_SYSTEM_GRAMMATICAL_GENDER permission.",
            )
        }
        return if (Flags.systemTermsOfAddressEnabled()) systemGender(userId)
        else Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED
    }

    override fun peekSystemGrammaticalGenderByUserId(
        attributionSource: AttributionSource?,
        userId: Int,
    ): Int = if (Flags.systemTermsOfAddressEnabled() && canReadSystemGender(attributionSource)) {
        systemGender(userId)
    } else {
        Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED
    }

    private fun systemGender(userId: Int): Int = synchronized(lock) {
        systemGenders[userId] ?: Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED
    }

    private fun canReadSystemGender(attributionSource: AttributionSource?): Boolean {
        val source = attributionSource ?: return false
        source.enforceCallingUid()
        return permissions.has(Manifest.permission.READ_SYSTEM_GRAMMATICAL_GENDER, source.uid)
    }

    /** GrammaticalInflectionService.enforceCallerPermissions. */
    private fun enforceChangeConfiguration() {
        if (Binder.getCallingUid() == Process.SHELL_UID) return
        permissions.enforce(
            Manifest.permission.CHANGE_CONFIGURATION,
            "Caller must be system, shell, root or hold CHANGE_CONFIGURATION permission.",
        )
    }
}
