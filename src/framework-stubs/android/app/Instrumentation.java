/* artzig stub: android.app.Instrumentation type surface for
 * PropertyInvalidatedCache's test gate. The hosted runtime never instruments;
 * ActivityThread.getInstrumentation() returns null and callers treat that as
 * the non-test path. */
package android.app;

public class Instrumentation {
    public boolean isInstrumenting() {
        return false;
    }
}
