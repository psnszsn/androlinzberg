/* artzig stub of the AIDL-generated android.hardware.gnss.measurement_corrections.SingleSatCorrection:
 * hardware/interfaces is not vendored; GnssSingleSatCorrection imports the flag bits. */
package android.hardware.gnss.measurement_corrections;

public final class SingleSatCorrection {
    public static final int SINGLE_SAT_CORRECTION_HAS_SAT_IS_LOS_PROBABILITY = 0x0001;
    public static final int SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH = 0x0002;
    public static final int SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH_UNC = 0x0004;
    public static final int SINGLE_SAT_CORRECTION_HAS_COMBINED_ATTENUATION = 0x0010;
    private SingleSatCorrection() {}

    /** The nested ExcessPathInfo flag bits GnssExcessPathInfo imports. */
    public static final class ExcessPathInfo {
        public static final int EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH = 0x0001;
        public static final int EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH_UNC = 0x0002;
        public static final int EXCESS_PATH_INFO_HAS_REFLECTING_PLANE = 0x0004;
        public static final int EXCESS_PATH_INFO_HAS_ATTENUATION = 0x0008;
        private ExcessPathInfo() {}
    }
}
