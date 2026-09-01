package com.android.org.conscrypt.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public interface FeatureFlags {

    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean certificateTransparencyCheckservertrustedApi();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean certificateTransparencyPlatform();
    @com.android.aconfig.annotations.AssumeFalseForR8
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    boolean spake2plusApi();
}
