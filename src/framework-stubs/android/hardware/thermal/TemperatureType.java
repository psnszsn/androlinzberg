/* artzig stub of the AIDL-generated android.hardware.thermal.TemperatureType enum:
 * hardware/interfaces is not vendored; Temperature re-exports these as TYPE_*. */
package android.hardware.thermal;

public @interface TemperatureType {
    int UNKNOWN = -1;
    int CPU = 0;
    int GPU = 1;
    int BATTERY = 2;
    int SKIN = 3;
    int USB_PORT = 4;
    int POWER_AMPLIFIER = 5;
    int BCL_VOLTAGE = 6;
    int BCL_CURRENT = 7;
    int BCL_PERCENTAGE = 8;
    int NPU = 9;
    int TPU = 10;
    int DISPLAY = 11;
    int MODEM = 12;
    int SOC = 13;
    int WIFI = 14;
    int CAMERA = 15;
    int FLASHLIGHT = 16;
    int SPEAKER = 17;
    int AMBIENT = 18;
    int POGO = 19;
}
