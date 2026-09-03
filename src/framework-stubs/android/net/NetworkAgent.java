/* artzig stub of AOSP android.net.NetworkAgent: the Connectivity module tree is not
 * vendored. android.telephony.Annotation re-exports the validation-status ids; no
 * network agent runs on this host. */
package android.net;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class NetworkAgent {
    public static final int VALIDATION_STATUS_VALID = 1;
    public static final int VALIDATION_STATUS_NOT_VALID = 2;

    @Retention(RetentionPolicy.SOURCE)
    public @interface ValidationStatus {}

    private NetworkAgent() {}
}
