package dev.artzig.uitest

import android.graphics.Color
import org.junit.Test

class XapkUiTest {
    @Test fun loadsSplitCodeAssetsAndExpansionData() {
        UiApp.expectVisible("Split code loaded")
        UiApp.expectVisible("Split native library loaded")
        UiApp.expectVisible("Asset from feature split")
        UiApp.expectVisible("Expansion data from XAPK")
        UiApp.assertColorArea(Color.CYAN, 5000)
        val directory = java.io.File(checkNotNull(System.getenv("AZK_TEST_SESSION_LOG"))).parentFile
        java.io.File(directory, "screenshot.png").outputStream().use {
            check(UiApp.screenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
