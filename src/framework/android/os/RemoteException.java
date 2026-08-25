/* artzig shim: android.os.RemoteException. */
package android.os;
import android.util.AndroidException;
public class RemoteException extends AndroidException {
    public RemoteException() {}
    public RemoteException(String message) { super(message); }
    public RemoteException(Throwable cause) { super(cause != null ? cause.getMessage() : null); }
    public RemoteException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
    public RuntimeException rethrowAsRuntimeException() { throw new RuntimeException(this); }
    public RuntimeException rethrowFromSystemServer() { throw new RuntimeException(this); }
}
