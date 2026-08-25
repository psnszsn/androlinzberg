/* artzig stub (auto-generated placeholder; members added on demand). */
package android.hardware.lights;
public class LightsManager {
    public java.util.List<Light> getLights() { return new java.util.ArrayList<>(); }
    public LightState getLightState(Light light) { return new LightState(); }
    public LightsSession openSession() { return new LightsSession(); }
    public static class LightsSession implements AutoCloseable {
        public void requestLights(LightsRequest request) {}
        @Override public void close() {}
    }
}
