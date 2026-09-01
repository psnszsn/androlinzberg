/* artzig stub: window-manager service interface — no WM service in-process. */
package android.view;
public interface IWindowManager {
    final class Stub {
        public static IWindowManager asInterface(android.os.IBinder b) {
            return android.view.WindowManagerGlobal.getWindowManagerService();
        }
        private Stub() {}
    }
    default boolean isKeyguardLocked() throws android.os.RemoteException { return false; }
    default void showStrictModeViolation(boolean on) throws android.os.RemoteException {}
    default boolean isKeyguardSecure(int userId) throws android.os.RemoteException { return false; }
    default int getInitialDisplayDensity(int displayId) throws android.os.RemoteException { return 160; }
    default int getBaseDisplayDensity(int displayId) throws android.os.RemoteException { return 160; }
    default boolean hasNavigationBar(int displayId) throws android.os.RemoteException { return false; }
    default int getPreferredOptionsPanelGravity(int displayId) throws android.os.RemoteException { return 81; }
    default int watchRotation(android.view.IRotationWatcher watcher, int displayId) throws android.os.RemoteException { return 0; }
    default void removeRotationWatcher(android.view.IRotationWatcher watcher) throws android.os.RemoteException {}
    default int getRemoveContentMode(int displayId) throws android.os.RemoteException { return 0; }
    default void thawRotation(String caller) throws android.os.RemoteException {}
    default void freezeRotation(int rotation, String caller) throws android.os.RemoteException {}
    default boolean registerCrossWindowBlurEnabledListener(Object listener) throws android.os.RemoteException { return false; }
    default void unregisterCrossWindowBlurEnabledListener(Object listener) throws android.os.RemoteException {}
    default void setDisplayHashThrottlingEnabled(boolean enable) throws android.os.RemoteException {}
    default String[] getSupportedDisplayHashAlgorithms() throws android.os.RemoteException { return new String[0]; }
    default android.view.displayhash.VerifiedDisplayHash verifyDisplayHash(android.view.displayhash.DisplayHash displayHash) throws android.os.RemoteException { return null; }
    default boolean registerScreenRecordingCallback(Object callback) throws android.os.RemoteException { return false; }
    default void unregisterScreenRecordingCallback(Object callback) throws android.os.RemoteException {}
    default boolean getWindowInsets(int displayId, android.os.IBinder token, android.view.InsetsState outInsetsState) throws android.os.RemoteException { return false; }
    default java.util.List<android.view.DisplayInfo> getPossibleDisplayInfo(int displayId) throws android.os.RemoteException { return new java.util.ArrayList<>(); }
}
