/* artzig stub of AOSP android.hardware.input.InputDeviceVibrator: input devices expose no
 * vibrators on this host (no InputManagerService vibrator control); the abstract Vibrator
 * surface answers as a device without one. Pending the input family. */
package android.hardware.input;

import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class InputDeviceVibrator extends Vibrator {
    public InputDeviceVibrator(int deviceId, int vibratorId) {}
    @Override public boolean hasVibrator() { return false; }
    @Override public boolean hasAmplitudeControl() { return false; }
    @Override public void vibrate(int uid, String opPkg, VibrationEffect vibe, String reason, VibrationAttributes attributes) {}
    @Override public void cancel() {}
    @Override public void cancel(int usageFilter) {}
}
