/* artzig stub of libphonenumber's NumberParseException: external/libphonenumber is not
 * vendored. PhoneNumberUtils catches it around every parse. */
package com.android.i18n.phonenumbers;

public class NumberParseException extends Exception {
    public enum ErrorType { INVALID_COUNTRY_CODE, NOT_A_NUMBER, TOO_SHORT_AFTER_IDD, TOO_SHORT_NSN, TOO_LONG }

    private final ErrorType errorType;

    public NumberParseException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() { return errorType; }

    @Override public String toString() { return "Error type: " + errorType + ". " + getMessage(); }
}
