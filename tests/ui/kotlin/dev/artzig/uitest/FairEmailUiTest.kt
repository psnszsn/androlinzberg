package dev.artzig.uitest

import androidx.test.uiautomator.By
import org.junit.Test

/** The original, signed FairEmail 1.2338a GitHub release. */
class FairEmailUiTest {
    @Test
    fun launchesWelcomeWithoutRuntimeIssues() = capture {
        UiApp.expectVisible("Welcome")
        UiApp.expectVisible("I agree")
    }

    @Test
    fun opensAccountSetupWithoutRuntimeIssues() = capture {
        UiApp.click("I agree")
        UiApp.expectVisible("Add or change accounts")
        UiApp.waitUntilGone(By.res("eu.faircode.email:id/tvNoInternet"))
        UiApp.click("Wizard")
        // PopupMenuLifecycle prefixes each title with an icon span and spacing.
        UiApp.click(By.textEndsWith("Other provider"))
        UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text = "Mail test"
        UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text = "mail-test@example.com"
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text == "Mail test")
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text == "mail-test@example.com")
        UiApp.waitFor(By.res("eu.faircode.email:id/btnCheck"))
    }

    private fun capture(action: () -> Unit) {
        try {
            action()
        } finally {
            try {
                val artifacts = java.io.File(checkNotNull(System.getenv("AZK_TEST_ARTIFACTS")))
                UiTest.device.dumpWindowHierarchy(java.io.File(artifacts, "window.xml"))
                java.io.File(artifacts, "screen.png").outputStream().use {
                    UiApp.screenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (failure: Throwable) {
                println("FairEmailUiTest: could not capture UI: $failure")
            }
        }
    }
}
