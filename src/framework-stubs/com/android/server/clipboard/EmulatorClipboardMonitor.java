/* artzig host seam: replaces AOSP com.android.server.clipboard
 * .EmulatorClipboardMonitor (qemu goldfish pipe) with the Wayland clipboard.
 * The reused ClipboardService constructs this under Build.IS_EMULATOR — which
 * the hosted system process turns on — and calls accept() whenever the primary
 * clip changes; the watcher thread injects host clipboard changes back through
 * the constructor's consumer. Text only; wl-copy/wl-paste (wl-clipboard) do
 * the Wayland work, and their absence just disables host sync. */
package com.android.server.clipboard;

import android.content.ClipData;
import android.util.Slog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

class EmulatorClipboardMonitor implements Consumer<ClipData> {
    private static final String TAG = "HostedClipboardMonitor";
    /** Last text either direction touched; breaks the wl-copy -> watch echo loop. */
    private volatile String mLastText;
    private volatile boolean mHostUnavailable;

    EmulatorClipboardMonitor(Consumer<ClipData> setAndroidClipboard) {
        Thread watcher = new Thread(() -> watch(setAndroidClipboard), "wl-paste-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watch(Consumer<ClipData> setAndroidClipboard) {
        Process process;
        try {
            process = new ProcessBuilder("wl-paste", "--watch", "echo", "changed")
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            mHostUnavailable = true;
            Slog.i(TAG, "host clipboard watch unavailable: " + e);
            return;
        }
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[64];
            while (in.read(buffer) > 0) {
                String text = readHostClipboard();
                if (text == null || text.equals(mLastText)) continue;
                mLastText = text;
                setAndroidClipboard.accept(ClipData.newPlainText("host", text));
            }
        } catch (Exception e) {
            Slog.w(TAG, "host clipboard watch stopped: " + e);
        } finally {
            process.destroy();
        }
    }

    private static String readHostClipboard() {
        try {
            Process paste = new ProcessBuilder("wl-paste", "--no-newline", "--type", "text")
                    .start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = paste.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void accept(ClipData clip) {
        if (mHostUnavailable) return;
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null) return;
        String value = text.toString();
        if (value.equals(mLastText)) return;
        mLastText = value;
        try {
            Process copy = new ProcessBuilder("wl-copy", "--type", "text/plain").start();
            copy.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));
            copy.getOutputStream().close();
        } catch (Exception e) {
            mHostUnavailable = true;
            Slog.i(TAG, "host clipboard copy unavailable: " + e);
        }
    }
}
