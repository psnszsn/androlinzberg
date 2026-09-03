/* artzig stub of AOSP android.os.vibrator.persistence.VibrationXmlSerializer: the real
 * serializer walks VibrationEffect segments, compositions and envelopes, which need the
 * real VibrationEffect (hardware/interfaces vibrator HAL constants). NotificationChannel
 * uses it only to back up a channel's vibration effect; the hosted channel table keeps the
 * effect object, so serialization is an empty document. Pending the vibrator family
 * (managers over hosted services, phase 2). */
package android.os.vibrator.persistence;

import android.os.VibrationEffect;

import java.io.IOException;
import java.io.Writer;

public final class VibrationXmlSerializer {
    /** AOSP FLAG_ALLOW_HIDDEN_APIS. */
    public static final int FLAG_ALLOW_HIDDEN_APIS = 1 << 0;

    private VibrationXmlSerializer() {}

    public static void serialize(VibrationEffect vibration, Writer writer) throws IOException {
        serialize(vibration, writer, 0);
    }

    public static void serialize(VibrationEffect vibration, Writer writer, int flags) throws IOException {
        writer.write("");
    }
}
