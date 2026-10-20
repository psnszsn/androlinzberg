package dev.artzig.uitest

import android.app.LocaleManager
import android.os.LocaleList
import android.os.SystemProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BootServicesUiTest {
    @Test
    fun audioModeIsNormalWithoutACallOwner() {
        UiApp.expectVisible("Counter: 0")
        val audio = UiTest.instrumentation.context.getSystemService(android.media.AudioManager::class.java)
        assertNotNull("audio service", audio)
        assertEquals("No application owns a call mode", android.media.AudioManager.MODE_NORMAL, audio.mode)
        UiApp.click("INCREMENT COUNTER")
        UiApp.expectVisible("Counter: 1")
        UiTest.instrumentation.uiAutomation.syncInputTransactions()
        val artifacts = java.io.File(checkNotNull(System.getenv("AZK_TEST_ARTIFACTS")))
        java.io.File(artifacts, "screen.png").outputStream().use {
            UiApp.screenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun roleAndRestrictionsServicesFollowStockContracts() {
        UiApp.expectVisible("Counter: 0")
        // The inspector has Android's shell UID. Restrictions are readable by
        // their own package, not by borrowing the system context's package name.
        val context = UiTest.instrumentation.context.createPackageContext("com.android.shell", 0)
        val roles = context.getSystemService(android.app.role.RoleManager::class.java)
        assertNotNull("stock role service", roles)
        org.junit.Assert.assertTrue(roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER))
        org.junit.Assert.assertFalse(roles.isRoleAvailable("dev.artzig.role.DOES_NOT_EXIST"))
        val restrictions = context.getSystemService(android.content.RestrictionsManager::class.java)
        assertNotNull("stock restrictions service", restrictions)
        org.junit.Assert.assertFalse(restrictions.hasRestrictionsProvider())
        assertNotNull(restrictions.applicationRestrictions)
    }

    @Test
    fun homeResolvesAndAppCanResume() {
        UiApp.expectVisible("Counter: 0")
        val context = UiTest.instrumentation.context
        // Like `am start`, this inspector uses the stock shell caller contract;
        // it has no ActivityThread registered as an application with AMS.
        fun start(intent: android.content.Intent) {
            val result = android.app.ActivityTaskManager.getService().startActivityAsUser(
                null, "com.android.shell", null, intent, null, null, null, 0, 0, null, null, 0)
            org.junit.Assert.assertTrue("Activity start rejected: $result", result >= 0)
        }
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull("Android HOME target", context.packageManager.resolveActivity(home, 0))
        start(home)
        UiApp.expectVisible("Framework Fallback Home")
        start(checkNotNull(context.packageManager.getLaunchIntentForPackage(
            "dev.artzig.fixtures.windows")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        UiApp.expectVisible("Counter: 0")
        UiApp.click("INCREMENT COUNTER")
        UiApp.expectVisible("Counter: 1")
    }

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
            org.junit.Assert.assertTrue("Locale change recreates the activity",
                UiTest.device.performActionAndWait({
                    locales.setApplicationLocales(packageName, LocaleList.forLanguageTags("cs-CZ"))
                }, androidx.test.uiautomator.Until.newWindow(), 30_000))
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
