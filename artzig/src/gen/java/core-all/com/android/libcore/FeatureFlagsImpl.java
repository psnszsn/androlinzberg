package com.android.libcore;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public final class FeatureFlagsImpl implements FeatureFlags {
    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean appinfo() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean hpkePublicApi() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean hpkeVApis() {
        return true;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean madviseApi() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean nativeMetrics() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean openjdk21Stringconcat() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean openjdk21V1Apis() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean openjdk21V2Apis() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean postCleanupApis() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean readOnlyDynamicCodeLoad() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean scheduleAtFixedRateNewBehavior() {
        return false;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean vApis() {
        return true;
    }

}
