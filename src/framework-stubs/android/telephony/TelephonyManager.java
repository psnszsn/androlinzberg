/* artzig stub. */
package android.telephony;
public class TelephonyManager {
    public static final int CALL_STATE_IDLE = 0, CALL_STATE_RINGING = 1, CALL_STATE_OFFHOOK = 2;
    public int getCallState() { return CALL_STATE_IDLE; }
    public boolean isVoiceCapable() { return false; }
    public String getSimCountryIso() { return ""; }
    public String getNetworkCountryIso() { return ""; }
    public static final String ACTION_SERVICE_PROVIDERS_UPDATED = "android.telephony.action.SERVICE_PROVIDERS_UPDATED";
}
