package dev.artzig

import android.app.AlarmManager
import android.app.IAlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.icu.lang.UCharacter
import android.icu.text.Collator
import android.icu.text.NumberFormat
import android.icu.util.LocaleData
import android.icu.util.TimeZone
import android.icu.util.ULocale
import android.icu.util.VersionInfo
import android.view.InputDevice
import android.view.inputmethod.InputMethodSubtype
import com.android.icu.util.Icu4cMetadata
import com.android.i18n.timezone.ZoneInfoDb
import java.lang.reflect.Proxy

/** Verify the pinned Java, JNI and data stack together, then its stock clients. */
internal object StockIcuSmoke {
    fun run(context: Context) {
        check(VersionInfo.ICU_VERSION == VersionInfo.getInstance("76.1"))
        check(VersionInfo.getInstance(Icu4cMetadata.getIcuVersion()) == VersionInfo.ICU_VERSION)
        check(VersionInfo.getInstance(Icu4cMetadata.getUnicodeVersion()) == UCharacter.getUnicodeVersion())
        check(UCharacter.getUnicodeVersion() == VersionInfo.getInstance("16.0"))
        check(VersionInfo.getInstance(Icu4cMetadata.getCldrVersion()) == LocaleData.getCLDRVersion())
        // Cyrillic Tje was added in Unicode 16; an older data file cannot satisfy this.
        check(UCharacter.getName(0x1c89) == "CYRILLIC CAPITAL LETTER TJE")
        check(UCharacter.toLowerCase(0x1c89) == 0x1c8a)
        check(Collator.getInstance(ULocale.GERMAN).compare("a", "b") < 0)
        check(NumberFormat.getInstance(ULocale.US).format(1234.5) == "1,234.5")
        // Exercise the original JNI converter and regular-expression implementations too.
        val text = "Grüße 東京"
        check(String(text.toByteArray(charset("UTF-16LE")), charset("UTF-16LE")) == text)
        check(java.util.regex.Pattern.compile("\\p{L}+").matcher("東京").matches())

        val db = ZoneInfoDb.getInstance()
        check(db.version == "2025a")
        check(Icu4cMetadata.getTzdbVersion() == db.version)
        check(TimeZone.getTZDataVersion() == db.version)
        val finder = com.android.i18n.timezone.TimeZoneFinder.getInstance()
        check(finder.ianaVersion == db.version)
        check(finder.lookupCountryTimeZones("de")!!.defaultTimeZoneId == "Europe/Berlin")
        val telephony = com.android.i18n.timezone.TelephonyLookup.getInstance().telephonyNetworkFinder!!
        check(telephony.findNetworkByMccMnc("310", "370")!!.countryIsoCode == "gu")
        check(telephony.findCountriesByMcc("262")!!.countryIsoCodes == setOf("de"))
        check(db.hasTimeZone("Europe/Berlin"))
        check(!db.hasTimeZone("Artzig/Unknown"))
        val winter = java.time.Instant.parse("2025-01-15T12:00:00Z").toEpochMilli()
        val summer = java.time.Instant.parse("2025-07-15T12:00:00Z").toEpochMilli()
        val javaZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val icuZone = TimeZone.getTimeZone("Europe/Berlin")
        for ((instant, expected) in listOf(winter to 3600000, summer to 7200000)) {
            check(javaZone.getOffset(instant) == expected)
            check(icuZone.getOffset(instant) == expected)
        }
        for ((alias, canonical) in listOf("iw-IL" to "he-IL", "sh" to "sr-Latn")) {
            check(ULocale.createCanonical(ULocale.forLanguageTag(alias)).toLanguageTag() == canonical)
            val device = InputDevice.Builder().setKeyboardLanguageTag(alias).build()
            check(device.keyboardLanguageTag == canonical)
            val subtype = InputMethodSubtype.InputMethodSubtypeBuilder().setLanguageTag(alias).build()
            check(subtype.canonicalizedLanguageTag == canonical)
        }
        check(InputDevice.Builder().setKeyboardLanguageTag("und").build().keyboardLanguageTag == null)
        alarmValidation(context)
        println("StockIcuSmoke: PASS ICU 76.1 / Unicode 16 / tzdata 2025a and stock clients")
    }

    private fun alarmValidation(base: Context) {
        val calls = mutableListOf<String>()
        val service = Proxy.newProxyInstance(IAlarmManager::class.java.classLoader,
            arrayOf(IAlarmManager::class.java)) { _, method, args ->
            check(method.name == "setTimeZone") { "Unexpected alarm call ${method.name}" }
            calls.add(args!![0] as String)
            null
        } as IAlarmManager
        val ctor = AlarmManager::class.java.getDeclaredConstructor(IAlarmManager::class.java, Context::class.java)
        ctor.isAccessible = true
        fun manager(targetSdk: Int): AlarmManager {
            val context = object : ContextWrapper(base) {
                override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
                    targetSdkVersion = targetSdk
                }
            }
            return ctor.newInstance(service, context)
        }
        val modern = manager(36)
        modern.setTimeZone("")
        check(calls.isEmpty())
        try {
            modern.setTimeZone("Artzig/Unknown")
            error("Invalid Olson ID accepted")
        } catch (expected: IllegalArgumentException) {
            check(expected.message == "Timezone: Artzig/Unknown is not an Olson ID")
        }
        check(calls.isEmpty())
        modern.setTimeZone("Europe/Berlin")
        check(calls == listOf("Europe/Berlin"))
        // Stock pre-M behavior passes legacy IDs to the service unchanged.
        manager(22).setTimeZone("GMT+3")
        check(calls == listOf("Europe/Berlin", "GMT+3"))
    }
}
