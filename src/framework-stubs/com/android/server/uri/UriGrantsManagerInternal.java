/* artzig stub: the subset of com.android.server.uri.UriGrantsManagerInternal
 * the reused ClipboardService calls. Hosted processes in one session share a
 * uid-per-package model without URI grant bookkeeping, so the system process
 * registers a permissive implementation in LocalServices. */
package com.android.server.uri;
import android.net.Uri;
import android.os.IBinder;
public interface UriGrantsManagerInternal {
    IBinder newUriPermissionOwner(String name);
    /** AOSP throws SecurityException when the grant is not allowed; returns the target uid. */
    int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags,
            int userId);
    void revokeUriPermissionFromOwner(IBinder owner, Uri uri, int mode, int userId);
}
