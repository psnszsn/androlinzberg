//! Generated RuntimeI18n artifacts, declared against pinned Soong inputs.
pub const icu = .{
    .package = "com.android.icu",
    .container = "com.android.i18n",
    .input = "external/icu/icu.aconfig",
    // Android 16 release values; all three flags are fixed read-only upstream.
    .values = &[_][]const u8{
        "build/release/aconfig/bp2a/com.android.icu/icu_v_api_flag_values.textproto",
        "build/release/aconfig/bp2a/com.android.icu/icu_25q2_api_flag_values.textproto",
        "build/release/aconfig/bp2a/com.android.icu/telephony_lookup_mcc_extension_flag_values.textproto",
    },
    .outputs = &[_][]const u8{
        "com/android/icu/CustomFeatureFlags.java", "com/android/icu/FakeFeatureFlagsImpl.java",
        "com/android/icu/FeatureFlags.java",       "com/android/icu/FeatureFlagsImpl.java",
        "com/android/icu/Flags.java",
    },
};
