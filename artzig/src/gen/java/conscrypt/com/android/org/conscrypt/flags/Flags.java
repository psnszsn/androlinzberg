package com.android.org.conscrypt.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;

/** @hide */
public final class Flags {
    /** @hide */
    public static final String FLAG_CERTIFICATE_TRANSPARENCY_CHECKSERVERTRUSTED_API = "com.android.org.conscrypt.flags.certificate_transparency_checkservertrusted_api";
    /** @hide */
    public static final String FLAG_CERTIFICATE_TRANSPARENCY_PLATFORM = "com.android.org.conscrypt.flags.certificate_transparency_platform";
    /** @hide */
    public static final String FLAG_SPAKE2PLUS_API = "com.android.org.conscrypt.flags.spake2plus_api";
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean certificateTransparencyCheckservertrustedApi() {
        
        return FEATURE_FLAGS.certificateTransparencyCheckservertrustedApi();
    }
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean certificateTransparencyPlatform() {
        
        return FEATURE_FLAGS.certificateTransparencyPlatform();
    }
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean spake2plusApi() {
        
        return FEATURE_FLAGS.spake2plusApi();
    }

    private static FeatureFlags FEATURE_FLAGS = new FeatureFlagsImpl();

}
