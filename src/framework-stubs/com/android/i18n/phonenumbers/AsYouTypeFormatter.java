/* artzig stub of libphonenumber's AsYouTypeFormatter: external/libphonenumber (and its
 * metadata resources) is not vendored. Without metadata the formatter echoes the digits
 * typed so far, which is libphonenumber's own fallback when no pattern matches;
 * PhoneNumberFormattingTextWatcher then leaves the field text as typed. */
package com.android.i18n.phonenumbers;

public class AsYouTypeFormatter {
    private final StringBuilder accrued = new StringBuilder();

    AsYouTypeFormatter(String regionCode) {}

    public String inputDigit(char nextChar) {
        accrued.append(nextChar);
        return accrued.toString();
    }

    public String inputDigitAndRememberPosition(char nextChar) {
        return inputDigit(nextChar);
    }

    public int getRememberedPosition() { return accrued.length(); }

    public void clear() { accrued.setLength(0); }
}
