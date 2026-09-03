/* artzig stub of libphonenumber's PhoneNumberOfflineGeocoder: external/libphonenumber
 * (and its geocoding data) is not vendored. android.telecom.CallerInfo asks it for a
 * number's location description; with no data the description is empty. */
package com.android.i18n.phonenumbers.geocoding;

import com.android.i18n.phonenumbers.Phonenumber.PhoneNumber;

import java.util.Locale;

public class PhoneNumberOfflineGeocoder {
    private static final PhoneNumberOfflineGeocoder sInstance = new PhoneNumberOfflineGeocoder();

    public static synchronized PhoneNumberOfflineGeocoder getInstance() { return sInstance; }

    public String getDescriptionForNumber(PhoneNumber number, Locale languageCode) { return ""; }

    public String getDescriptionForNumber(PhoneNumber number, Locale languageCode, String userRegion) { return ""; }

    public String getDescriptionForValidNumber(PhoneNumber number, Locale languageCode) { return ""; }
}
