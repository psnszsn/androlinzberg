/* artzig stub. */
package android.telecom;
public class TelecomManager {
    public static final int CALL_STATE_IDLE = 0, CALL_STATE_RINGING = 1, CALL_STATE_OFFHOOK = 2;
    public int getCallState() { return CALL_STATE_IDLE; }
    public boolean isInCall() { return false; }
    public boolean endCall() { return false; }
    public void showInCallScreen(boolean showDialpad) {}
    public void acceptRingingCall() {}
}
