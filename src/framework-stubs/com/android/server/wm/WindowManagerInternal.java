/* artzig stub: the subset of com.android.server.wm.WindowManagerInternal the
 * reused services consult (the AOSP abstract class drags the whole window
 * manager type graph). The hosted system process registers an implementation
 * in LocalServices; every app window on this host is its own focused Wayland
 * toplevel, so focus checks answer true. */
package com.android.server.wm;
public abstract class WindowManagerInternal {
    public abstract boolean isUidFocused(int uid);
    public abstract int getTopFocusedDisplayId();
    public abstract boolean isHomeSupportedOnDisplay(int displayId);
}
