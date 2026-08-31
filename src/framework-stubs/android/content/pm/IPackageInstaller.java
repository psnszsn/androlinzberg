/* artzig stub: the installer session surface is not hosted; the generated
 * IPackageManager only needs the type (getPackageInstaller returns null). */
package android.content.pm;
public interface IPackageInstaller extends android.os.IInterface {
    abstract class Stub extends android.os.Binder implements IPackageInstaller {
        public static IPackageInstaller asInterface(android.os.IBinder binder) { return null; }
        @Override public android.os.IBinder asBinder() { return this; }
    }
}
