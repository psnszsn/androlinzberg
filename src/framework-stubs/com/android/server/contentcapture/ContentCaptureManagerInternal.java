/* artzig stub: content capture does not exist on this host; nothing registers
 * this in LocalServices, so the reused services take their null paths. The
 * vendored AOSP class drags the contentcapture service type graph. */
package com.android.server.contentcapture;
public abstract class ContentCaptureManagerInternal {
    public abstract boolean isContentCaptureServiceForUser(int uid, int userId);
}
