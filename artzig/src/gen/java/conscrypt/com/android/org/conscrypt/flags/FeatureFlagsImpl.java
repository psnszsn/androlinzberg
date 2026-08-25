package com.android.org.conscrypt.flags;
// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
/** @hide */
public final class FeatureFlagsImpl implements FeatureFlags {
    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean certificateTransparencyCheckservertrustedApi() {
        return true;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean certificateTransparencyPlatform() {
        return true;
    }

    @Override
    @com.android.aconfig.annotations.AconfigFlagAccessor
    @UnsupportedAppUsage
    public boolean spake2plusApi() {
        return true;
    }

}
