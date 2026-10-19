package dev.artzig.uitest

import android.app.LocaleManager
import android.os.LocaleList
import android.os.SystemProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BootServicesUiTest {
    @Test
    fun publishesAndroidBootCompletion() {
        UiApp.expectVisible("Counter: 0")
        assertEquals("Android boot completion", "1", SystemProperties.get("sys.boot_completed"))
        assertEquals("Android boot-complete alias", "1", SystemProperties.get("dev.bootcomplete"))
    }

    @Test
    fun applicationLocalesRoundTrip() {
        UiApp.expectVisible("Counter: 0")
        val context = UiTest.instrumentation.context
        val locales = context.getSystemService(LocaleManager::class.java)
        assertNotNull("stock locale service", locales)
        val packageName = "dev.artzig.fixtures.windows"
        val before = locales.getApplicationLocales(packageName)
        try {
            locales.setApplicationLocales(packageName, LocaleList.forLanguageTags("cs-CZ"))
            assertEquals("cs-CZ", locales.getApplicationLocales(packageName).toLanguageTags())
            UiApp.click("INCREMENT COUNTER")
            UiApp.expectVisible("Counter: 1")
            locales.setApplicationLocales(packageName, LocaleList.getEmptyLocaleList())
            assertEquals("", locales.getApplicationLocales(packageName).toLanguageTags())
            UiApp.expectVisible("Counter: 1")
        } finally {
            locales.setApplicationLocales(packageName, before)
        }
    }
}
