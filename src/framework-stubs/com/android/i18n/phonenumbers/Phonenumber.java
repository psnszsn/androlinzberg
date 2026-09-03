/* artzig stub of libphonenumber's Phonenumber.PhoneNumber: external/libphonenumber is
 * not vendored. Value shape only; PhoneNumberUtils reads the country code, national
 * number and country-code source of parsed numbers (none is ever produced here). */
package com.android.i18n.phonenumbers;

public final class Phonenumber {
    private Phonenumber() {}

    public static class PhoneNumber {
        public enum CountryCodeSource {
            FROM_NUMBER_WITH_PLUS_SIGN, FROM_NUMBER_WITH_IDD, FROM_NUMBER_WITHOUT_PLUS_SIGN, FROM_DEFAULT_COUNTRY, UNSPECIFIED
        }

        private int countryCode;
        private long nationalNumber;
        private String rawInput = "";
        private CountryCodeSource countryCodeSource = CountryCodeSource.UNSPECIFIED;

        public int getCountryCode() { return countryCode; }
        public PhoneNumber setCountryCode(int value) { countryCode = value; return this; }
        public boolean hasCountryCode() { return countryCode != 0; }
        public long getNationalNumber() { return nationalNumber; }
        public PhoneNumber setNationalNumber(long value) { nationalNumber = value; return this; }
        public boolean hasNationalNumber() { return nationalNumber != 0; }
        public String getRawInput() { return rawInput; }
        public PhoneNumber setRawInput(String value) { rawInput = value == null ? "" : value; return this; }
        public boolean hasRawInput() { return !rawInput.isEmpty(); }
        public CountryCodeSource getCountryCodeSource() { return countryCodeSource; }
        public PhoneNumber setCountryCodeSource(CountryCodeSource value) { countryCodeSource = value; return this; }
    }
}
