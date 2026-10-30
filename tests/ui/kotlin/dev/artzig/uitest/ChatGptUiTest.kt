package dev.artzig.uitest

import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.expectVisible
import org.junit.Test

class ChatGptUiTest {
    @Test
    fun launchesWelcomeWithoutRuntimeIssues() = capture {
        openGuestChat()
    }

    @Test
    fun sendsGuestMessageWithoutRuntimeIssues() = capture {
        openGuestChat()
        val prompt = "Reply with exactly this single word, no punctuation: pineapple"
        UiApp.waitFor(By.clazz("android.widget.EditText")).text = prompt
        UiApp.click(UiApp.description("Send Message"))
        expectVisible(prompt)
        expectVisible("pineapple")
    }

    @Test
    fun opensSignInWithoutRuntimeIssues() = capture {
        openGuestChat()
        UiApp.click("Log in")
        UiApp.waitUntilGone(UiApp.description("Ask ChatGPT"))
        UiApp.click("Log in another way")
        UiApp.waitFor(By.clazz("android.widget.EditText"), 60_000)
    }

    private fun openGuestChat() {
        expectVisible("Welcome to ChatGPT")
        // The server selects either sign-in onboarding or the guest terms sheet.
        UiApp.click(By.text(java.util.regex.Pattern.compile("Skip|Continue( without logging in)?")))
        UiApp.waitUntilGone(UiApp.text("Welcome to ChatGPT"))
        // Some server-selected onboarding flows insert a notification prompt
        // before guest chat. Dismiss the visible prompt through the same UI.
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (!UiTest.device.hasObject(UiApp.description("Ask ChatGPT"))) {
            if (UiTest.device.hasObject(By.text("Don’t allow"))) {
                UiApp.click("Don’t allow")
                UiApp.waitUntilGone(By.text("Don’t allow"))
            }
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Guest onboarding did not finish" }
            android.os.SystemClock.sleep(50)
        }
        UiApp.waitFor(UiApp.description("Ask ChatGPT"))
        UiApp.waitFor(By.clazz("android.widget.EditText"))
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
                println("ChatGptUiTest: could not capture UI: $failure")
            }
        }
    }
}
