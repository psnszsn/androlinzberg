/* artzig stub. */
package android.view;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.FlaggedApi;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.accessibility.IAccessibilityEmbeddedConnection;
import android.window.InputTransferToken;
import com.android.window.flags.Flags;
public class SurfaceControlViewHost {
    // Verbatim AOSP SurfaceControlViewHost.SurfacePackage. The enclosing host
    // still requires a WindowlessWindowManager and compositor-backed view root.
    public static final class SurfacePackage implements Parcelable {
        private SurfaceControl mSurfaceControl;
        private final IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;
        private final InputTransferToken mInputTransferToken;
        @NonNull
        private final ISurfaceControlViewHost mRemoteInterface;

        SurfacePackage(SurfaceControl sc, IAccessibilityEmbeddedConnection connection,
                InputTransferToken inputTransferToken,
                @NonNull ISurfaceControlViewHost ri) {
            mSurfaceControl = sc;
            mAccessibilityEmbeddedConnection = connection;
            mInputTransferToken = inputTransferToken;
            mRemoteInterface = ri;
        }

        /**
         * Constructs a copy of {@code SurfacePackage} with an independent lifetime.
         *
         * The caller can use this to create an independent copy in situations where ownership of
         * the {@code SurfacePackage} would be transferred elsewhere, such as attaching to a
         * {@code SurfaceView}, returning as {@code Binder} result value, etc. The caller is
         * responsible for releasing this copy when its done.
         *
         * @param other {@code SurfacePackage} to create a copy of.
         */
        public SurfacePackage(@NonNull SurfacePackage other) {
            SurfaceControl otherSurfaceControl = other.mSurfaceControl;
            if (otherSurfaceControl != null && otherSurfaceControl.isValid()) {
                mSurfaceControl = new SurfaceControl(otherSurfaceControl, "SurfacePackage");
            }
            mAccessibilityEmbeddedConnection = other.mAccessibilityEmbeddedConnection;
            mInputTransferToken = other.mInputTransferToken;
            mRemoteInterface = other.mRemoteInterface;
        }

        private SurfacePackage(Parcel in) {
            mSurfaceControl = new SurfaceControl();
            mSurfaceControl.readFromParcel(in);
            mSurfaceControl.setUnreleasedWarningCallSite("SurfacePackage(Parcel)");
            mAccessibilityEmbeddedConnection = IAccessibilityEmbeddedConnection.Stub.asInterface(
                    in.readStrongBinder());
            mInputTransferToken = InputTransferToken.CREATOR.createFromParcel(in);
            mRemoteInterface = ISurfaceControlViewHost.Stub.asInterface(in.readStrongBinder());
        }

        /**
         * Returns the {@link android.view.SurfaceControl} associated with this SurfacePackage for
         * cases where more control is required.
         *
         * @return the SurfaceControl associated with this SurfacePackage and its containing
         *     SurfaceControlViewHost
         */
        public @NonNull SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }

        /**
         * Gets an accessibility embedded connection interface for this SurfaceControlViewHost.
         *
         * @return {@link IAccessibilityEmbeddedConnection} interface.
         * @hide
         */
        public IAccessibilityEmbeddedConnection getAccessibilityEmbeddedConnection() {
            return mAccessibilityEmbeddedConnection;
        }

        /**
         * @hide
         */
        @NonNull
        public ISurfaceControlViewHost getRemoteInterface() {
            return mRemoteInterface;
        }

        /**
         * Forward a configuration to the remote SurfaceControlViewHost.
         * This will cause View#onConfigurationChanged to be invoked on the remote
         * end. This does not automatically cause the SurfaceControlViewHost
         * to be resized. The root View of a SurfaceControlViewHost
         * is more akin to a PopupWindow in that the size is user specified
         * independent of configuration width and height.
         *
         * In order to receive the configuration change via
         * {@link View#onConfigurationChanged}, the context used with the
         * SurfaceControlViewHost and it's embedded view hierarchy must
         * be a WindowContext obtained from {@link Context#createWindowContext}.
         *
         * If a regular service context is used, then your embedded view hierarchy
         * will always perceive the global configuration.
         *
         * @param c The configuration to forward
         */
        public void notifyConfigurationChanged(@NonNull Configuration c) {
            try {
                getRemoteInterface().onConfigurationChanged(c);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        /**
         * Tear down the remote SurfaceControlViewHost and cause
         * View#onDetachedFromWindow to be invoked on the other side.
         */
        public void notifyDetachedFromWindow() {
            try {
                getRemoteInterface().onDispatchDetachedFromWindow();
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            mSurfaceControl.writeToParcel(out, flags);
            out.writeStrongBinder(mAccessibilityEmbeddedConnection.asBinder());
            mInputTransferToken.writeToParcel(out, flags);
            out.writeStrongBinder(mRemoteInterface.asBinder());
        }

        /**
         * Release the {@link SurfaceControl} associated with this package.
         * It's not necessary to call this if you pass the package to
         * {@link SurfaceView#setChildSurfacePackage} as {@link SurfaceView} will
         * take ownership in that case.
         */
        public void release() {
            if (mSurfaceControl != null) {
                mSurfaceControl.release();
             }
             mSurfaceControl = null;
        }

        /**
         * Gets an {@link InputTransferToken} which can be used to request focus on the embedded
         * surface or to transfer touch gesture to the embedded surface.
         *
         * @return the InputTransferToken associated with {@link SurfacePackage} or {@code null} if
         * the embedded hasn't set up its view or doesn't have input.
         * @see WindowManager#transferTouchGesture(InputTransferToken, InputTransferToken)
         */
        @Nullable
        @FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
        public InputTransferToken getInputTransferToken() {
            return mInputTransferToken;
        }

        @Override
        public String toString() {
            return "{inputTransferToken=" + getInputTransferToken() + " remoteInterface="
                    + getRemoteInterface() + "}";
        }

        public static final @NonNull Creator<SurfacePackage> CREATOR
             = new Creator<SurfacePackage>() {
                     public SurfacePackage createFromParcel(Parcel in) {
                         return new SurfacePackage(in);
                     }
                     public SurfacePackage[] newArray(int size) {
                         return new SurfacePackage[size];
                     }
             };
    }
    public View getView() { return null; }
    public void release() {}
}
