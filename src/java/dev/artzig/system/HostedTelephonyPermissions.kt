package dev.artzig.system

import android.Manifest
import android.os.Binder

/**
 * The permission checks the hosted telephony and telecom services share.
 *
 * Source note: `com.android.internal.telephony.TelephonyPermissions` reduced to the
 * hosted permission model (install-time grants, no app ops, no carrier privileges:
 * there is no SIM to carry certificates). The AOSP class itself is not compiled
 * because its closure reaches the legacy permission manager and app-ops server
 * paths. Each check keeps AOSP's outcome for a caller that never declared the
 * permission: a SecurityException where AOSP enforces, a false where AOSP asks
 * the no-throw variant.
 */
class HostedTelephonyPermissions(packages: HostedPackageManagerService) : HostedPermissions(packages) {
    /** TelephonyPermissions.checkCallingOrSelfReadPhoneStateNoThrow. */
    fun canReadPhoneState(): Boolean = has(Manifest.permission.READ_PRIVILEGED_PHONE_STATE) || has(Manifest.permission.READ_PHONE_STATE)

    /** TelephonyPermissions.checkCallingOrSelfReadPhoneState: enforces READ_PHONE_STATE. */
    fun enforceReadPhoneState(message: String) {
        if (!canReadPhoneState()) {
            throw SecurityException("$message: Neither user ${Binder.getCallingUid()} nor current process has android.permission.READ_PHONE_STATE.")
        }
    }

    /** TelephonyPermissions.checkCallingOrSelfReadPhoneNumber: any of the phone-number permissions. */
    fun enforceReadPhoneNumber(message: String) {
        if (canReadPhoneState() || has(Manifest.permission.READ_SMS) || has(Manifest.permission.READ_PHONE_NUMBERS)) return
        throw SecurityException("$message: Neither user ${Binder.getCallingUid()} nor current process has android.permission.READ_PHONE_STATE, android.permission.READ_SMS, or android.permission.READ_PHONE_NUMBERS.")
    }

    /** TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers: privileged callers only (Android 10+). */
    fun enforceDeviceIdentifiers(callingPackage: String?, message: String) {
        if (!has(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)) {
            throw SecurityException("$callingPackage does not meet the requirements to access device identifiers ($message).")
        }
    }

    fun enforcePrivilegedPhoneState(message: String) = enforce(Manifest.permission.READ_PRIVILEGED_PHONE_STATE, message)
    fun enforceModifyPhoneState(message: String) = enforce(Manifest.permission.MODIFY_PHONE_STATE, message)
}
