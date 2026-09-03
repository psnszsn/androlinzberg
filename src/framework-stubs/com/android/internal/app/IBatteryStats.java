/* artzig stub of the AIDL-generated com.android.internal.app.IBatteryStats: the interface
 * parcels Bluetooth and Wi-Fi activity energy info from unvendored module trees, so it cannot be
 * generated. BatteryManager binds three methods (isCharging, computeChargeTimeRemaining,
 * setChargingStateUpdateDelayMillis); this hand-written proxy/stub pair carries exactly those
 * transactions. The codes are private to this pair (only the hosted battery service and the
 * hosted framework's BatteryManager speak them); they are not the AOSP ordinals. */
package com.android.internal.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IBatteryStats extends IInterface {
    String DESCRIPTOR = "com.android.internal.app.IBatteryStats";

    boolean isCharging() throws RemoteException;
    long computeChargeTimeRemaining() throws RemoteException;
    boolean setChargingStateUpdateDelayMillis(int delay) throws RemoteException;

    abstract class Stub extends Binder implements IBatteryStats {
        static final int TRANSACTION_isCharging = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_computeChargeTimeRemaining = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_setChargingStateUpdateDelayMillis = IBinder.FIRST_CALL_TRANSACTION + 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IBatteryStats asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IBatteryStats) return (IBatteryStats) local;
            return new Proxy(obj);
        }

        @Override public IBinder asBinder() { return this; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_isCharging: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean result = isCharging();
                    reply.writeNoException();
                    reply.writeBoolean(result);
                    return true;
                }
                case TRANSACTION_computeChargeTimeRemaining: {
                    data.enforceInterface(DESCRIPTOR);
                    long result = computeChargeTimeRemaining();
                    reply.writeNoException();
                    reply.writeLong(result);
                    return true;
                }
                case TRANSACTION_setChargingStateUpdateDelayMillis: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean result = setChargingStateUpdateDelayMillis(data.readInt());
                    reply.writeNoException();
                    reply.writeBoolean(result);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements IBatteryStats {
            private final IBinder mRemote;
            Proxy(IBinder remote) { mRemote = remote; }
            @Override public IBinder asBinder() { return mRemote; }

            @Override public boolean isCharging() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isCharging, data, reply, 0);
                    reply.readException();
                    return reply.readBoolean();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public long computeChargeTimeRemaining() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_computeChargeTimeRemaining, data, reply, 0);
                    reply.readException();
                    return reply.readLong();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override public boolean setChargingStateUpdateDelayMillis(int delay) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(delay);
                    mRemote.transact(TRANSACTION_setChargingStateUpdateDelayMillis, data, reply, 0);
                    reply.readException();
                    return reply.readBoolean();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
