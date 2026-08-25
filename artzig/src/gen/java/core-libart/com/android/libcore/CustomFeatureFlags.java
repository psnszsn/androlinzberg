package com.android.libcore;

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
    public boolean appinfo() {
        return getValue(Flags.FLAG_APPINFO,
            FeatureFlags::appinfo);
    }

    @Override
    @UnsupportedAppUsage
    public boolean hpkePublicApi() {
        return getValue(Flags.FLAG_HPKE_PUBLIC_API,
            FeatureFlags::hpkePublicApi);
    }

    @Override
    @UnsupportedAppUsage
    public boolean hpkeVApis() {
        return getValue(Flags.FLAG_HPKE_V_APIS,
            FeatureFlags::hpkeVApis);
    }

    @Override
    @UnsupportedAppUsage
    public boolean madviseApi() {
        return getValue(Flags.FLAG_MADVISE_API,
            FeatureFlags::madviseApi);
    }

    @Override
    @UnsupportedAppUsage
    public boolean nativeMetrics() {
        return getValue(Flags.FLAG_NATIVE_METRICS,
            FeatureFlags::nativeMetrics);
    }

    @Override
    @UnsupportedAppUsage
    public boolean openjdk21Stringconcat() {
        return getValue(Flags.FLAG_OPENJDK21_STRINGCONCAT,
            FeatureFlags::openjdk21Stringconcat);
    }

    @Override
    @UnsupportedAppUsage
    public boolean openjdk21V1Apis() {
        return getValue(Flags.FLAG_OPENJDK_21_V1_APIS,
            FeatureFlags::openjdk21V1Apis);
    }

    @Override
    @UnsupportedAppUsage
    public boolean openjdk21V2Apis() {
        return getValue(Flags.FLAG_OPENJDK_21_V2_APIS,
            FeatureFlags::openjdk21V2Apis);
    }

    @Override
    @UnsupportedAppUsage
    public boolean postCleanupApis() {
        return getValue(Flags.FLAG_POST_CLEANUP_APIS,
            FeatureFlags::postCleanupApis);
    }

    @Override
    @UnsupportedAppUsage
    public boolean readOnlyDynamicCodeLoad() {
        return getValue(Flags.FLAG_READ_ONLY_DYNAMIC_CODE_LOAD,
            FeatureFlags::readOnlyDynamicCodeLoad);
    }

    @Override
    @UnsupportedAppUsage
    public boolean scheduleAtFixedRateNewBehavior() {
        return getValue(Flags.FLAG_SCHEDULE_AT_FIXED_RATE_NEW_BEHAVIOR,
            FeatureFlags::scheduleAtFixedRateNewBehavior);
    }

    @Override
    @UnsupportedAppUsage
    public boolean vApis() {
        return getValue(Flags.FLAG_V_APIS,
            FeatureFlags::vApis);
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
            Flags.FLAG_APPINFO,
            Flags.FLAG_HPKE_PUBLIC_API,
            Flags.FLAG_HPKE_V_APIS,
            Flags.FLAG_MADVISE_API,
            Flags.FLAG_NATIVE_METRICS,
            Flags.FLAG_OPENJDK21_STRINGCONCAT,
            Flags.FLAG_OPENJDK_21_V1_APIS,
            Flags.FLAG_OPENJDK_21_V2_APIS,
            Flags.FLAG_POST_CLEANUP_APIS,
            Flags.FLAG_READ_ONLY_DYNAMIC_CODE_LOAD,
            Flags.FLAG_SCHEDULE_AT_FIXED_RATE_NEW_BEHAVIOR,
            Flags.FLAG_V_APIS
        );
    }

    private Set<String> mReadOnlyFlagsSet = new HashSet<>(
        Arrays.asList(
            Flags.FLAG_APPINFO,
            Flags.FLAG_HPKE_PUBLIC_API,
            Flags.FLAG_HPKE_V_APIS,
            Flags.FLAG_MADVISE_API,
            Flags.FLAG_NATIVE_METRICS,
            Flags.FLAG_OPENJDK21_STRINGCONCAT,
            Flags.FLAG_OPENJDK_21_V1_APIS,
            Flags.FLAG_OPENJDK_21_V2_APIS,
            Flags.FLAG_POST_CLEANUP_APIS,
            Flags.FLAG_READ_ONLY_DYNAMIC_CODE_LOAD,
            Flags.FLAG_SCHEDULE_AT_FIXED_RATE_NEW_BEHAVIOR,
            Flags.FLAG_V_APIS,
            ""
        )
    );
}
