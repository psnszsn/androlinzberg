/* artzig stub of the AIDL-generated android.hardware.vibrator.IVibratorManager: hardware/interfaces
 * is not vendored; only the manager capability bits the system vibrator client checks. */
package android.hardware.vibrator;

public interface IVibratorManager {
    int CAP_SYNC = 1 << 0;
    int CAP_PREPARE_ON = 1 << 1;
    int CAP_PREPARE_PERFORM = 1 << 2;
    int CAP_PREPARE_COMPOSE = 1 << 3;
    int CAP_MIXED_TRIGGER_ON = 1 << 4;
    int CAP_MIXED_TRIGGER_PERFORM = 1 << 5;
    int CAP_MIXED_TRIGGER_COMPOSE = 1 << 6;
    int CAP_TRIGGER_CALLBACK = 1 << 7;
    int CAP_START_SESSIONS = 1 << 8;
}
