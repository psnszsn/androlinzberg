package com.android.libcore;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;

/** @hide */
public final class Flags {
    /** @hide */
    public static final String FLAG_APPINFO = "com.android.libcore.appinfo";
    /** @hide */
    public static final String FLAG_HPKE_PUBLIC_API = "com.android.libcore.hpke_public_api";
    /** @hide */
    public static final String FLAG_HPKE_V_APIS = "com.android.libcore.hpke_v_apis";
    /** @hide */
    public static final String FLAG_MADVISE_API = "com.android.libcore.madvise_api";
    /** @hide */
    public static final String FLAG_NATIVE_METRICS = "com.android.libcore.native_metrics";
    /** @hide */
    public static final String FLAG_OPENJDK21_STRINGCONCAT = "com.android.libcore.openjdk21_stringconcat";
    /** @hide */
    public static final String FLAG_OPENJDK_21_V1_APIS = "com.android.libcore.openjdk_21_v1_apis";
    /** @hide */
    public static final String FLAG_OPENJDK_21_V2_APIS = "com.android.libcore.openjdk_21_v2_apis";
    /** @hide */
    public static final String FLAG_POST_CLEANUP_APIS = "com.android.libcore.post_cleanup_apis";
    /** @hide */
    public static final String FLAG_READ_ONLY_DYNAMIC_CODE_LOAD = "com.android.libcore.read_only_dynamic_code_load";
    /** @hide */
    public static final String FLAG_SCHEDULE_AT_FIXED_RATE_NEW_BEHAVIOR = "com.android.libcore.schedule_at_fixed_rate_new_behavior";
    /** @hide */
    public static final String FLAG_V_APIS = "com.android.libcore.v_apis";
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean appinfo() {
        
        return FEATURE_FLAGS.appinfo();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean hpkePublicApi() {
        
        return FEATURE_FLAGS.hpkePublicApi();
    }
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean hpkeVApis() {
        
        return FEATURE_FLAGS.hpkeVApis();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean madviseApi() {
        
        return FEATURE_FLAGS.madviseApi();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean nativeMetrics() {
        
        return FEATURE_FLAGS.nativeMetrics();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean openjdk21Stringconcat() {
        
        return FEATURE_FLAGS.openjdk21Stringconcat();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean openjdk21V1Apis() {
        
        return FEATURE_FLAGS.openjdk21V1Apis();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean openjdk21V2Apis() {
        
        return FEATURE_FLAGS.openjdk21V2Apis();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean postCleanupApis() {
        
        return FEATURE_FLAGS.postCleanupApis();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean readOnlyDynamicCodeLoad() {
        
        return FEATURE_FLAGS.readOnlyDynamicCodeLoad();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean scheduleAtFixedRateNewBehavior() {
        
        return FEATURE_FLAGS.scheduleAtFixedRateNewBehavior();
    }
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean vApis() {
        
        return FEATURE_FLAGS.vApis();
    }

    private static FeatureFlags FEATURE_FLAGS = new FeatureFlagsImpl();

}
