package com.android.libcore;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public interface FeatureFlags {

    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean appinfo();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean hpkePublicApi();
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean hpkeVApis();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean madviseApi();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean nativeMetrics();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean openjdk21Stringconcat();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean openjdk21V1Apis();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean openjdk21V2Apis();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean postCleanupApis();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean readOnlyDynamicCodeLoad();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean scheduleAtFixedRateNewBehavior();
    @com.android.aconfig.annotations.AssumeTrueForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean vApis();
}
