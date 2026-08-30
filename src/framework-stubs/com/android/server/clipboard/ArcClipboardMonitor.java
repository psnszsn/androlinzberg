/* artzig stub: the ChromeOS clipboard bridge does not exist on this host;
 * Build.IS_ARC is false so this is never constructed — it exists only so the
 * reused ClipboardService compiles. */
package com.android.server.clipboard;
import android.content.ClipData;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
public class ArcClipboardMonitor implements Consumer<ClipData> {
    ArcClipboardMonitor(BiConsumer<ClipData, Integer> setAndroidClipboard) {}
    @Override
    public void accept(ClipData clip) {}
}
