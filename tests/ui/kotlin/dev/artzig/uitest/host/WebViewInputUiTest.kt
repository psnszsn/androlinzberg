package dev.artzig.uitest.host

import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp
import org.junit.Test

class WebViewInputUiTest {
    @org.junit.After
    fun retainUi() {
        dev.artzig.uitest.UiTest.device.dumpWindowHierarchy(java.io.File(HostSession.artifacts, "window.xml"))
        java.io.File(HostSession.artifacts, "screen.png").outputStream().use {
            UiApp.screenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun keyboardPreservesEmailAndPassword() {
        UiApp.waitFor(By.text("WebView input verified"), 60_000)
        UiApp.click("Open sign-in fixture")
        WaylandSeat.tapThenType(UiApp.waitFor(By.hint("Fixture email")).visibleCenter,
            "fixture+test@example.invalid")
        WaylandSeat.tapThenType(UiApp.waitFor(By.hint("Fixture password")).visibleCenter,
            "aZ9!@#_+-.")
        WaylandSeat.tap(UiApp.waitFor(By.text("Verify form")).visibleCenter)
        UiApp.expectVisible("Form values preserved")
    }
}
