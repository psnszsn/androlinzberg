/* artzig stub: no libphonenumber on host. */
package com.android.i18n.phonenumbers;
public class PhoneNumberUtil {
    public enum Leniency { POSSIBLE, VALID, STRICT_GROUPING, EXACT_GROUPING }
    public enum PhoneNumberFormat { E164, INTERNATIONAL, NATIONAL, RFC3966 }
    private static final PhoneNumberUtil sInstance = new PhoneNumberUtil();
    public static PhoneNumberUtil getInstance() { return sInstance; }
    public Iterable<PhoneNumberMatch> findNumbers(CharSequence text, String defaultRegion,
            Leniency leniency, long maxTries) {
        return java.util.Collections.emptyList();
    }
    public String format(Object number, PhoneNumberFormat format) { return ""; }
}
