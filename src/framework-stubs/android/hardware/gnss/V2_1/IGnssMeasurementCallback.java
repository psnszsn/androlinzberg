/* artzig stub of the HIDL-generated android.hardware.gnss.V2_1.IGnssMeasurementCallback:
 * hardware/interfaces is not vendored; GnssMeasurement imports the measurement flag bits. */
package android.hardware.gnss.V2_1;

public interface IGnssMeasurementCallback {
    final class GnssMeasurementFlags {
        public static final int HAS_SNR = 1;
        public static final int HAS_CARRIER_FREQUENCY = 512;
        public static final int HAS_CARRIER_CYCLES = 1024;
        public static final int HAS_CARRIER_PHASE = 2048;
        public static final int HAS_CARRIER_PHASE_UNCERTAINTY = 4096;
        public static final int HAS_AUTOMATIC_GAIN_CONTROL = 8192;
        public static final int HAS_FULL_ISB = 65536;
        public static final int HAS_FULL_ISB_UNCERTAINTY = 131072;
        public static final int HAS_SATELLITE_ISB = 262144;
        public static final int HAS_SATELLITE_ISB_UNCERTAINTY = 524288;
        private GnssMeasurementFlags() {}
    }
}
