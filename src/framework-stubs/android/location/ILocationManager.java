/* artzig stub: the one deprecated Settings.Secure location toggle calls
 * ILocationManager.Stub.asInterface; there is no location service here. */
package android.location;
import android.os.IBinder;
public interface ILocationManager {
    void setLocationEnabledForUser(boolean enabled, int userId) throws android.os.RemoteException;
    boolean isProviderEnabledForUser(String provider, int userId) throws android.os.RemoteException;
    abstract class Stub {
        public static ILocationManager asInterface(IBinder binder) { return null; }
    }
}
