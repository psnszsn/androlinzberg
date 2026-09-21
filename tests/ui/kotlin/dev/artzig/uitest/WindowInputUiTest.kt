package dev.artzig.uitest

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The window fixture's counter, driven through androidx UiAutomator. */
class WindowInputUiTest {
    private val device get() = UiTest.device

    @Test
    fun clickingButtonUpdatesStatus() {
        assertNotNull("launch state", device.wait(Until.findObject(By.text("Counter: 0")), TIMEOUT))
        device.findObject(By.text("INCREMENT COUNTER")).click()
        assertNotNull("state after click", device.wait(Until.findObject(By.text("Counter: 1")), TIMEOUT))
    }

    @Test
    fun twoClicksCountTwice() {
        assertNotNull("launch state", device.wait(Until.findObject(By.text("Counter: 0")), TIMEOUT))
        repeat(2) { device.findObject(By.text("INCREMENT COUNTER")).click() }
        assertNotNull("state after clicks", device.wait(Until.findObject(By.text("Counter: 2")), TIMEOUT))
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
