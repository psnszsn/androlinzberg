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
        openAccountSetup("mail-test@example.com")
    }

    @Test
    fun checksUnknownProviderWithoutResolverTransportErrors() = capture {
        // .invalid never reaches a mail server; this tests discovery without
        // credentials or an authentication attempt against someone's account.
        openAccountSetup("mail-test@androlinzberg.invalid")
        UiApp.waitFor(By.desc("Password").clazz("android.widget.EditText")).text = "unused-test-password"
        UiApp.click(By.res("eu.faircode.email:id/btnCheck"))
        val failure = UiApp.waitFor(By.res("eu.faircode.email:id/tvError"), 90_000)
        check(failure.text.contains("androlinzberg.invalid")) { failure.text }
        UiApp.waitFor(By.res("eu.faircode.email:id/btnManual"))
        UiApp.assertNoLog("resNetworkSend failed")
        UiApp.assertNoLog("resNetworkQuery failed")
        UiApp.assertNoLog("resNetworkResult failed")
    }

    private fun openAccountSetup(email: String) {
        UiApp.click("I agree")
        UiApp.expectVisible("Add or change accounts")
        UiApp.waitUntilGone(By.res("eu.faircode.email:id/tvNoInternet"))
        UiApp.click("Wizard")
        // PopupMenuLifecycle prefixes each title with an icon span and spacing.
        UiApp.click(By.textEndsWith("Other provider"))
        UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text = "Mail test"
        UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text = email
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text == "Mail test")
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text == email)
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
