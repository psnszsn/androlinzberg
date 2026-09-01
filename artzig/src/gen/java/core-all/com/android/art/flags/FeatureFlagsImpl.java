package com.android.art.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public final class FeatureFlagsImpl implements FeatureFlags {
    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean alwaysEnableProfileCode() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean artServiceV3() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean executableMethodFileOffsets() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean executableMethodFileOffsetsV2() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean fastBaselineCompiler() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean test() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean useGenerationalCmc() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean virtualThreadImplV1() {
        return false;
    }

}
