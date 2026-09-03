/* artzig stub of the AIDL-generated android.hardware.radio.data.QosFilter parcelable constants: hardware/interfaces (the radio HAL)
 * is not vendored; QosBearerFilter protocol/direction ids re-exports these values. Values from the
 * android-16 radio HAL as published through the framework API text. */
package android.hardware.radio.data;

public final class QosFilter {
    public static final int PROTOCOL_UNSPECIFIED = -1;
    public static final int DIRECTION_DOWNLINK = 0;
    public static final int DIRECTION_UPLINK = 1;
    public static final int DIRECTION_BIDIRECTIONAL = 2;
    public static final int PROTOCOL_TCP = 6;
    public static final int PROTOCOL_UDP = 17;
    public static final int PROTOCOL_ESP = 50;
    public static final int PROTOCOL_AH = 51;

    private QosFilter() {}
}
