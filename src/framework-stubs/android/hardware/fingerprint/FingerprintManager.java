/* artzig stub (auto-generated placeholder; members added on demand). */
package android.hardware.fingerprint;

import android.content.Context;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.util.Slog;

public class FingerprintManager implements BiometricFingerprintConstants {
    private static final String TAG = "FingerprintManager";

    public boolean isHardwareDetected() { return false; }
    public boolean hasEnrolledFingerprints() { return false; }

    /** AOSP FingerprintManager#getErrorString compatibility for BiometricPrompt. */
    public static String getErrorString(Context context, int errMsg, int vendorCode) {
        switch (errMsg) {
            case FINGERPRINT_ERROR_HW_UNAVAILABLE:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_hw_not_available);
            case FINGERPRINT_ERROR_UNABLE_TO_PROCESS:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_unable_to_process);
            case FINGERPRINT_ERROR_TIMEOUT:
                return context.getString(com.android.internal.R.string.fingerprint_error_timeout);
            case FINGERPRINT_ERROR_NO_SPACE:
                return context.getString(com.android.internal.R.string.fingerprint_error_no_space);
            case FINGERPRINT_ERROR_CANCELED:
                return context.getString(com.android.internal.R.string.fingerprint_error_canceled);
            case FINGERPRINT_ERROR_LOCKOUT:
                return context.getString(com.android.internal.R.string.fingerprint_error_lockout);
            case FINGERPRINT_ERROR_LOCKOUT_PERMANENT:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_lockout_permanent);
            case FINGERPRINT_ERROR_USER_CANCELED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_user_canceled);
            case FINGERPRINT_ERROR_NO_FINGERPRINTS:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_no_fingerprints);
            case FINGERPRINT_ERROR_HW_NOT_PRESENT:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_hw_not_present);
            case BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_security_update_required);
            case FINGERPRINT_ERROR_BAD_CALIBRATION:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_bad_calibration);
            case BIOMETRIC_ERROR_POWER_PRESSED:
                return context.getString(
                        com.android.internal.R.string.fingerprint_error_power_pressed);
            case FINGERPRINT_ERROR_VENDOR:
                String[] messages = context.getResources().getStringArray(
                        com.android.internal.R.array.fingerprint_error_vendor);
                if (vendorCode < messages.length) {
                    return messages[vendorCode];
                }
                break;
            default:
                break;
        }
        Slog.w(TAG, "Invalid error message: " + errMsg + ", " + vendorCode);
        return context.getString(
                com.android.internal.R.string.fingerprint_error_vendor_unknown);
    }
}
