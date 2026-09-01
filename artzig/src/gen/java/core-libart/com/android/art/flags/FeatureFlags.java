package com.android.art.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public interface FeatureFlags {

    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean alwaysEnableProfileCode();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean artServiceV3();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean executableMethodFileOffsets();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean executableMethodFileOffsetsV2();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean fastBaselineCompiler();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean test();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean useGenerationalCmc();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean virtualThreadImplV1();
}
