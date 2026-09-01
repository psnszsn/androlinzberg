/* artzig host seam: replaces AOSP com.android.server.clipboard
 * .EmulatorClipboardMonitor, whose qemu goldfish pipe is unavailable on the
 * host. ClipboardService still needs the type at linkage time, but hosted
 * sessions deliberately keep host clipboard synchronization disabled. The
 * real AOSP ClipboardService continues to provide the session-local Android
 * clipboard; this adapter performs no external reads or writes. */
package com.android.server.clipboard;

import android.content.ClipData;
import java.util.function.Consumer;

class EmulatorClipboardMonitor implements Consumer<ClipData> {
    EmulatorClipboardMonitor(Consumer<ClipData> ignored) {}

    @Override
    public void accept(ClipData clip) {}
}
