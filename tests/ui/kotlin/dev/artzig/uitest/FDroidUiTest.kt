package dev.artzig.uitest

import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.assertLog
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.description
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import androidx.test.uiautomator.Until
import org.junit.Test

/** F-Droid 2.0.1 (org.fdroid.fdroid 2000051), a Jetpack Compose client, in
 * its narrow phone layout (412x915). It downloads the f-droid.org index on
 * first launch, so these tests need network access. */
class FDroidUiTest {
    @Test
    fun launchesDiscoverWithoutRuntimeIssues() {
        waitFor(description("Settings"))
        expectVisible("Search")
        expectVisible("My apps")
        FDroid.awaitIndex()
        expectVisible("Discover")
        expectVisible("Categories")
    }

    @Test
    fun searchOpensAppDetails() {
        FDroid.awaitIndex()
        click("Search")
        waitFor(By.clazz("android.widget.EditText")).text = "NewPipe"
        expectVisible("Lightweight YouTube frontend")
        click("NewPipe")
        // Shown once, before the details of the first app with anti-features.
        expectVisible("What are Anti-Features?")
        click("Got it")
        expectVisible("by Team NewPipe")
        expectVisible("Install")
    }

    /** F-Droid downloads an APK and commits a stock PackageInstaller session;
     * without INSTALL_PACKAGES the session waits for the user, and the stock
     * PackageInstaller app asks to allow F-Droid as a source, then confirms. */
    @Test
    fun installsAnAppThroughThePackageInstaller() {
        FDroid.awaitIndex()
        click("Search")
        waitFor(By.clazz("android.widget.EditText")).text = "Simple Keyboard"
        // The search field holds the same text; open the result by its summary.
        click("An app for those who only need a keyboard and nothing more")
        expectVisible("Install")
        click("Install")
        // PackageInstaller: F-Droid may not install unknown apps yet.
        expectVisible("For your security, your phone currently isn’t allowed to install unknown apps from this source. You can change this in Settings.")
        click(By.res("android:id/button1"))
        click("Allow from this source")
        val question = "Do you want to install this app?"
        if (!UiTest.device.wait(Until.hasObject(By.text(question)), 10_000)) UiApp.pressBack()
        expectVisible(question)
        click(By.res("android:id/button1"))
        expectVisible("Open")
        assertLog("PackageManager", "installation completed for package:rkr.simplekeyboard.inputmethod")
    }
}

private object FDroid {
    /** The first repository update runs in WorkManager and fills the search
     * index when it completes; a search issued earlier finds nothing. */
    fun awaitIndex() = assertLog("WM-WorkerWrapper", "Worker result SUCCESS for Work [ id=", timeoutMillis = 180_000)
}
