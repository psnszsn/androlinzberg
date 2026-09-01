/* artzig stub of AOSP com.android.internal.os.RuntimeInit: the real class is
 * zygote/app_process bootstrap (uncaught-exception crash reporting to AMS,
 * MimeMap/logging config, argv dispatch), none of which runs on this host
 * where the launcher boots ART directly. StrictMode links against the
 * application-object accessor only. */
package com.android.internal.os;
import android.os.IBinder;
public class RuntimeInit {
    private static volatile IBinder mApplicationObject;
    public static void setApplicationObject(IBinder app) { mApplicationObject = app; }
    public static IBinder getApplicationObject() { return mApplicationObject; }
    private RuntimeInit() {}
}
