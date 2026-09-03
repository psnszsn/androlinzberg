/* artzig stub of AOSP android.os.vibrator.persistence.VibrationXmlParser: the real parser
 * builds VibrationEffect segments, compositions and envelopes, which need the real
 * VibrationEffect (hardware/interfaces vibrator HAL constants). NotificationChannel uses it
 * only to restore a backed-up vibration effect, and the hosted serializer writes none.
 * Pending the vibrator family (managers over hosted services, phase 2). */
package android.os.vibrator.persistence;

import android.os.VibrationEffect;

import java.io.IOException;
import java.io.Reader;

public final class VibrationXmlParser {
    /** AOSP FLAG_ALLOW_HIDDEN_APIS. */
    public static final int FLAG_ALLOW_HIDDEN_APIS = 1 << 0;

    private VibrationXmlParser() {}

    public static VibrationEffect parseVibrationEffect(Reader reader) throws IOException {
        return parseVibrationEffect(reader, 0);
    }

    /** No serialized vibration document is ever produced on this host: nothing parses. */
    public static VibrationEffect parseVibrationEffect(Reader reader, int flags) throws IOException {
        return null;
    }
}
