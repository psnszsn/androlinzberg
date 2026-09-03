/* artzig host adaptation of com.android.internal.content.om.OverlayManagerImpl: the AOSP
 * class writes self-targeting (fabricated) overlays as frro files through idmap2 and
 * reads them back from the app's overlay directory. This host runs no idmap2, so a
 * self-targeting transaction fails the way a file error fails in AOSP (IOException) and
 * no self-targeting overlay is ever registered. */
package com.android.internal.content.om;

import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OverlayManagerImpl {
    public OverlayManagerImpl(android.content.Context context) {}
    public void ensureBaseDir() {}
    public static String checkOverlayNameValid(String name) { return name; }

    /** AOSP commit: fabricated overlays are compiled by idmap2, which is not hosted. */
    public void commit(OverlayManagerTransaction transaction) throws PackageManager.NameNotFoundException, IOException {
        throw new IOException("self-targeting overlays are not supported on this host (no idmap2)");
    }

    public List<OverlayInfo> getOverlayInfosForTarget(String targetPackage) {
        return new ArrayList<>();
    }
}
