/* artzig stub (auto-generated placeholder; members added on demand). */
package android.hardware;
public class SensorManager {
    public static final float GRAVITY_EARTH = 9.80665f;
    public static final int SENSOR_DELAY_FASTEST = 0, SENSOR_DELAY_GAME = 1, SENSOR_DELAY_UI = 2, SENSOR_DELAY_NORMAL = 3;
    public static final float MAGNETIC_FIELD_EARTH_MAX = 60.0f;
    public java.util.List<Sensor> getSensorList(int type) { return new java.util.ArrayList<>(); }
    public Sensor getDefaultSensor(int type) { return null; }
    public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs) { return false; }
    public void unregisterListener(SensorEventListener listener) {}
}
