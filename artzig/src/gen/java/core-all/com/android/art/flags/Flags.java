package com.android.art.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;

/** @hide */
public final class Flags {
    /** @hide */
    public static final String FLAG_ALWAYS_ENABLE_PROFILE_CODE = "com.android.art.flags.always_enable_profile_code";
    /** @hide */
    public static final String FLAG_ART_SERVICE_V3 = "com.android.art.flags.art_service_v3";
    /** @hide */
    public static final String FLAG_EXECUTABLE_METHOD_FILE_OFFSETS = "com.android.art.flags.executable_method_file_offsets";
    /** @hide */
    public static final String FLAG_EXECUTABLE_METHOD_FILE_OFFSETS_V2 = "com.android.art.flags.executable_method_file_offsets_v2";
    /** @hide */
    public static final String FLAG_FAST_BASELINE_COMPILER = "com.android.art.flags.fast_baseline_compiler";
    /** @hide */
    public static final String FLAG_TEST = "com.android.art.flags.test";
    /** @hide */
    public static final String FLAG_USE_GENERATIONAL_CMC = "com.android.art.flags.use_generational_cmc";
    /** @hide */
    public static final String FLAG_VIRTUAL_THREAD_IMPL_V1 = "com.android.art.flags.virtual_thread_impl_v1";
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean alwaysEnableProfileCode() {
        
        return FEATURE_FLAGS.alwaysEnableProfileCode();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean artServiceV3() {
        
        return FEATURE_FLAGS.artServiceV3();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean executableMethodFileOffsets() {
        
        return FEATURE_FLAGS.executableMethodFileOffsets();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean executableMethodFileOffsetsV2() {
        
        return FEATURE_FLAGS.executableMethodFileOffsetsV2();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean fastBaselineCompiler() {
        
        return FEATURE_FLAGS.fastBaselineCompiler();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean test() {
        
        return FEATURE_FLAGS.test();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean useGenerationalCmc() {
        
        return FEATURE_FLAGS.useGenerationalCmc();
    }
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public static boolean virtualThreadImplV1() {
        
        return FEATURE_FLAGS.virtualThreadImplV1();
    }

    private static FeatureFlags FEATURE_FLAGS = new FeatureFlagsImpl();

}
