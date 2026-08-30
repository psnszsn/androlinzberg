/* artzig stub: the subset of android.os.IUserManager the reused system
 * services call. The real AIDL drags UserProperties, RestrictionEntry, and
 * restriction listeners; the hosted system process registers one local Binder
 * implementing this interface under Context.USER_SERVICE. */
package android.os;
public interface IUserManager {
    boolean isUserRunning(int userId) throws RemoteException;
    java.util.List<android.content.pm.UserInfo> getProfiles(int userId, boolean enabledOnly)
            throws RemoteException;
    boolean hasUserRestriction(String restrictionKey, int userId) throws RemoteException;
}
