/* artzig stub: no input-device sensors on host. */
package android.hardware.input;
public class InputDeviceSensorManager {
    public InputDeviceSensorManager(Object global) {}
    public android.hardware.SensorManager getSensorManager(int deviceId) { return new android.hardware.SensorManager(); }
    public void onInputDeviceAdded(int deviceId) {}
    public void onInputDeviceRemoved(int deviceId) {}
    public void onInputDeviceChanged(int deviceId) {}
}
