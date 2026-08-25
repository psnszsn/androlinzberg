package com.android.org.conscrypt.flags;

// TODO(b/303773055): Remove the annotation after access issue is resolved.
import android.compat.annotation.UnsupportedAppUsage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
/** @hide */
public class CustomFeatureFlags implements FeatureFlags {

    private BiPredicate<String, Predicate<FeatureFlags>> mGetValueImpl;

    public CustomFeatureFlags(BiPredicate<String, Predicate<FeatureFlags>> getValueImpl) {
        mGetValueImpl = getValueImpl;
    }
    @Override
    @UnsupportedAppUsage
    public boolean certificateTransparencyCheckservertrustedApi() {
        return getValue(Flags.FLAG_CERTIFICATE_TRANSPARENCY_CHECKSERVERTRUSTED_API,
            FeatureFlags::certificateTransparencyCheckservertrustedApi);
    }

    @Override
    @UnsupportedAppUsage
    public boolean certificateTransparencyPlatform() {
        return getValue(Flags.FLAG_CERTIFICATE_TRANSPARENCY_PLATFORM,
            FeatureFlags::certificateTransparencyPlatform);
    }

    @Override
    @UnsupportedAppUsage
    public boolean spake2plusApi() {
        return getValue(Flags.FLAG_SPAKE2PLUS_API,
            FeatureFlags::spake2plusApi);
    }

    public boolean isFlagReadOnlyOptimized(String flagName) {
        if (mReadOnlyFlagsSet.contains(flagName) &&
            isOptimizationEnabled()) {
                return true;
        }
        return false;
    }

    @com.android.aconfig.annotations.AssumeTrueForR8
    private boolean isOptimizationEnabled() {
        return false;
    }

    protected boolean getValue(String flagName, Predicate<FeatureFlags> getter) {
        return mGetValueImpl.test(flagName, getter);
    }

    public List<String> getFlagNames() {
        return Arrays.asList(
            Flags.FLAG_CERTIFICATE_TRANSPARENCY_CHECKSERVERTRUSTED_API,
            Flags.FLAG_CERTIFICATE_TRANSPARENCY_PLATFORM,
            Flags.FLAG_SPAKE2PLUS_API
        );
    }

    private Set<String> mReadOnlyFlagsSet = new HashSet<>(
        Arrays.asList(
            Flags.FLAG_CERTIFICATE_TRANSPARENCY_CHECKSERVERTRUSTED_API,
            Flags.FLAG_CERTIFICATE_TRANSPARENCY_PLATFORM,
            Flags.FLAG_SPAKE2PLUS_API,
            ""
        )
    );
}
