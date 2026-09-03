/* artzig stub of AOSP android.hardware.input.InputDeviceVibratorManager: input devices expose no
 * vibrators on this host; the abstract VibratorManager surface answers as a device without any.
 * Pending the input family. */
package android.hardware.input;

import android.os.CombinedVibration;
import android.os.NullVibrator;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.VibratorManager;

public class InputDeviceVibratorManager extends VibratorManager {
    public InputDeviceVibratorManager(int deviceId) {}
    @Override public int[] getVibratorIds() { return new int[0]; }
    @Override public Vibrator getVibrator(int vibratorId) { return NullVibrator.getInstance(); }
    @Override public Vibrator getDefaultVibrator() { return NullVibrator.getInstance(); }
    @Override public void vibrate(int uid, String opPkg, CombinedVibration effect, String reason, VibrationAttributes attributes) {}
    @Override public void cancel() {}
    @Override public void cancel(int usageFilter) {}
}
