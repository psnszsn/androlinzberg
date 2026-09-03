/* artzig stub of AOSP android.content.pm.LauncherApps: the launcher-facing client of
 * LauncherAppsService (shortcuts, package installer sessions, user profiles), whose value
 * types are still stubs (PackageInstaller sessions, ShortcutInfo). AppWidgetHostView uses it
 * to open a widget's app on tap; no launcher runs in a hosted session. Pending the
 * shortcut/package-installer families (managers over hosted services, phase 6). */
package android.content.pm;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;

import java.util.Collections;
import java.util.List;

public class LauncherApps {
    /** No launcher activities are enumerated for other packages on this host. */
    public List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        return Collections.emptyList();
    }

    public void startMainActivity(ComponentName component, UserHandle user, Rect sourceBounds, Bundle opts) {}
}
