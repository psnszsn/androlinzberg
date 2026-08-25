/* artzig stub: no night-light/color transforms on host. */
package android.hardware.display;
public final class ColorDisplayManager {
    public boolean isNightDisplayActivated() { return false; }
    public boolean setNightDisplayActivated(boolean activated) { return false; }
    public int getNightDisplayColorTemperature() { return 0; }
    public boolean isDeviceColorManaged() { return false; }
    public boolean setSaturationLevel(int saturationLevel) { return false; }
    public int getNightDisplayAutoMode() { return 0; }
    public java.time.LocalTime getNightDisplayCustomStartTime() { return java.time.LocalTime.of(22, 0); }
    public java.time.LocalTime getNightDisplayCustomEndTime() { return java.time.LocalTime.of(6, 0); }
    public static boolean isNightDisplayAvailable(android.content.Context context) { return false; }
    public static boolean isColorTransformAccelerated(android.content.Context context) { return false; }
}
