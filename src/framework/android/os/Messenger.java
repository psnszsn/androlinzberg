/* artzig shim: in-process android.os.Messenger. */
package android.os;

public final class Messenger implements Parcelable {
    private final Handler mHandler;
    private final IBinder mBinder;

    public Messenger(Handler target) {
        if (target == null) throw new NullPointerException("target");
        mHandler = target;
        mBinder = null;
    }

    public Messenger(IBinder target) {
        mHandler = null;
        mBinder = target;
    }

    public void send(Message message) throws RemoteException {
        if (mHandler != null) {
            mHandler.sendMessage(message);
            return;
        }
        throw new RemoteException("remote Messenger is unavailable on the host");
    }

    public IBinder getBinder() { return mBinder; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel out, int flags) { out.writeStrongBinder(mBinder); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Messenger)) return false;
        Messenger messenger = (Messenger) other;
        return mHandler == messenger.mHandler && mBinder == messenger.mBinder;
    }

    @Override public int hashCode() {
        return mHandler != null ? System.identityHashCode(mHandler)
                : System.identityHashCode(mBinder);
    }

    public static void writeMessengerOrNullToParcel(Messenger messenger, Parcel out) {
        out.writeStrongBinder(messenger != null ? messenger.getBinder() : null);
    }

    public static Messenger readMessengerOrNullFromParcel(Parcel in) {
        IBinder binder = in.readStrongBinder();
        return binder != null ? new Messenger(binder) : null;
    }

    public static final Parcelable.Creator<Messenger> CREATOR =
            new Parcelable.Creator<Messenger>() {
        public Messenger createFromParcel(Parcel in) {
            return readMessengerOrNullFromParcel(in);
        }
        public Messenger[] newArray(int size) { return new Messenger[size]; }
    };
}
