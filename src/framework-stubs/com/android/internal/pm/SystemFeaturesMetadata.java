/* artzig stub: no build-time feature metadata; every lookup falls through to
 * the package manager service. */
package com.android.internal.pm;
public final class SystemFeaturesMetadata {
    /** Sizes ApplicationPackageManager's hasSystemFeature cache; must be
     * positive (the real generated value counts the SDK's features). */
    public static final int SDK_FEATURE_COUNT = 256;
    public static int maybeGetSdkFeatureIndex(String featureName) { return -1; }
    private SystemFeaturesMetadata() {}
}
