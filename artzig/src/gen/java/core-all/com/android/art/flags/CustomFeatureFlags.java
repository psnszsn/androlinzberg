package com.android.art.flags;

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
    public boolean alwaysEnableProfileCode() {
        return getValue(Flags.FLAG_ALWAYS_ENABLE_PROFILE_CODE,
            FeatureFlags::alwaysEnableProfileCode);
    }

    @Override
    @UnsupportedAppUsage
    public boolean artServiceV3() {
        return getValue(Flags.FLAG_ART_SERVICE_V3,
            FeatureFlags::artServiceV3);
    }

    @Override
    @UnsupportedAppUsage
    public boolean executableMethodFileOffsets() {
        return getValue(Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS,
            FeatureFlags::executableMethodFileOffsets);
    }

    @Override
    @UnsupportedAppUsage
    public boolean executableMethodFileOffsetsV2() {
        return getValue(Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS_V2,
            FeatureFlags::executableMethodFileOffsetsV2);
    }

    @Override
    @UnsupportedAppUsage
    public boolean fastBaselineCompiler() {
        return getValue(Flags.FLAG_FAST_BASELINE_COMPILER,
            FeatureFlags::fastBaselineCompiler);
    }

    @Override
    @UnsupportedAppUsage
    public boolean test() {
        return getValue(Flags.FLAG_TEST,
            FeatureFlags::test);
    }

    @Override
    @UnsupportedAppUsage
    public boolean useGenerationalCmc() {
        return getValue(Flags.FLAG_USE_GENERATIONAL_CMC,
            FeatureFlags::useGenerationalCmc);
    }

    @Override
    @UnsupportedAppUsage
    public boolean virtualThreadImplV1() {
        return getValue(Flags.FLAG_VIRTUAL_THREAD_IMPL_V1,
            FeatureFlags::virtualThreadImplV1);
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
            Flags.FLAG_ALWAYS_ENABLE_PROFILE_CODE,
            Flags.FLAG_ART_SERVICE_V3,
            Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS,
            Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS_V2,
            Flags.FLAG_FAST_BASELINE_COMPILER,
            Flags.FLAG_TEST,
            Flags.FLAG_USE_GENERATIONAL_CMC,
            Flags.FLAG_VIRTUAL_THREAD_IMPL_V1
        );
    }

    private Set<String> mReadOnlyFlagsSet = new HashSet<>(
        Arrays.asList(
            Flags.FLAG_ALWAYS_ENABLE_PROFILE_CODE,
            Flags.FLAG_ART_SERVICE_V3,
            Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS,
            Flags.FLAG_EXECUTABLE_METHOD_FILE_OFFSETS_V2,
            Flags.FLAG_FAST_BASELINE_COMPILER,
            Flags.FLAG_TEST,
            Flags.FLAG_USE_GENERATIONAL_CMC,
            Flags.FLAG_VIRTUAL_THREAD_IMPL_V1,
            ""
        )
    );
}
