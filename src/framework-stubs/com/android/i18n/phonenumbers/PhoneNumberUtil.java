/* artzig stub of libphonenumber's PhoneNumberUtil: external/libphonenumber (and its
 * metadata resources) is not vendored. Parsing fails with NOT_A_NUMBER, so
 * PhoneNumberUtils and Linkify take their documented no-match paths: formatting
 * returns the input unchanged, comparisons report no match, no numbers are linkified. */
package com.android.i18n.phonenumbers;

import com.android.i18n.phonenumbers.Phonenumber.PhoneNumber;

public class PhoneNumberUtil {
    public enum Leniency { POSSIBLE, VALID, STRICT_GROUPING, EXACT_GROUPING }
    public enum PhoneNumberFormat { E164, INTERNATIONAL, NATIONAL, RFC3966 }
    public enum MatchType { NOT_A_NUMBER, NO_MATCH, SHORT_NSN_MATCH, NSN_MATCH, EXACT_MATCH }

    private static final PhoneNumberUtil sInstance = new PhoneNumberUtil();

    public static PhoneNumberUtil getInstance() { return sInstance; }

    public Iterable<PhoneNumberMatch> findNumbers(CharSequence text, String defaultRegion,
            Leniency leniency, long maxTries) {
        return java.util.Collections.emptyList();
    }

    public PhoneNumber parse(CharSequence numberToParse, String defaultRegion) throws NumberParseException {
        throw new NumberParseException(NumberParseException.ErrorType.NOT_A_NUMBER,
                "libphonenumber metadata is not available on this host");
    }

    public PhoneNumber parseAndKeepRawInput(CharSequence numberToParse, String defaultRegion)
            throws NumberParseException {
        return parse(numberToParse, defaultRegion);
    }

    public boolean isValidNumber(PhoneNumber number) { return false; }
    public String format(PhoneNumber number, PhoneNumberFormat format) { return number.getRawInput(); }
    public String formatInOriginalFormat(PhoneNumber number, String regionCallingFrom) { return number.getRawInput(); }
    public String getRegionCodeForNumber(PhoneNumber number) { return null; }
    public int getCountryCodeForRegion(String regionCode) { return 0; }
    public MatchType isNumberMatch(PhoneNumber firstNumber, PhoneNumber secondNumber) { return MatchType.NOT_A_NUMBER; }
    public AsYouTypeFormatter getAsYouTypeFormatter(String regionCode) { return new AsYouTypeFormatter(regionCode); }
}
