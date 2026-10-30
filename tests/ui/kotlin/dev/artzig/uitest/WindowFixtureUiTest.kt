package dev.artzig.uitest

import android.view.accessibility.AccessibilityNodeInfo
import dev.artzig.uitest.UiApp.assertColorArea
import dev.artzig.uitest.UiApp.assertLog
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.description
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.text
import dev.artzig.uitest.UiApp.waitFor
import dev.artzig.uitest.UiApp.waitUntilGone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window, dialog, surface and lifecycle contracts of the window fixture APK. */
class WindowFixtureUiTest {
    @Test
    fun launchesAndUpdatesState() {
        expectVisible("Counter: 0")
        click("INCREMENT COUNTER")
        expectVisible("Counter: 1")
        assertLog("WindowFixture", "\"event\":\"increment\"")
    }

    @Test
    fun dialogRemainsAttachedToTheActivity() {
        click("SHOW ATTACHED DIALOG")
        expectVisible("Fixture dialog")
        click("CLOSE DIALOG")
        waitUntilGone(text("Fixture dialog"))
        expectVisible("Counter: 0")
    }

    @Test
    fun dialogLifecycleRestoresStateAndPreservesCallbackOrder() {
        click("SHOW DIALOG CONTRACTS")
        expectVisible("Dialog contracts ready")
        click("CHECK DIALOG LIFECYCLE")
        expectVisible("Dialog lifecycle and saved state verified")
        assertLog("DialogFixture", "create/start/stop; asynchronous show/dismiss; reshow; cancel before dismiss")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun sharedBackCallbackRemainsScopedToEachDialogWindow() {
        click("SHOW DIALOG CONTRACTS")
        click("SHOW SHARED BACK DIALOGS")
        expectVisible("Upper Back dialog")
        UiApp.pressBack()
        waitUntilGone(text("Upper Back dialog"))
        expectVisible("Lower Back dialog")
        assertLog("DialogFixture", "shared Back callback dismissed upper window only")
        UiApp.pressBack()
        waitUntilGone(text("Lower Back dialog"))
        expectVisible("Window-scoped Back callbacks verified")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun activityPendingIntentsLaunchAndDeliverResults() {
        click("SHOW ACTIVITY PENDINGINTENT")
        expectVisible("Activity PendingIntent ready")
        click("CHECK ACTIVITY PENDINGINTENT")
        expectVisible("Activity PendingIntent contracts verified")
        assertLog("PendingIntentFixture", "stock activity record: identity, update, immutable fill-in, launch result, cancellation, one-shot and pending result verified")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun intentServiceHandlesStartsInOrderAndStopsItself() {
        click("SHOW INTENTSERVICE")
        expectVisible("IntentService ready")
        click("CHECK INTENTSERVICE")
        expectVisible("IntentService contracts verified")
        assertLog("IntentServiceFixture", "stock IntentService: ordered starts on one worker, then stopSelf and destroy")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun defaultFontIsStockRobotoWithHostFallback() {
        click("SHOW DEFAULT FONT")
        expectVisible("Default font ready")
        click("CHECK DEFAULT FONT")
        expectVisible("Default font contracts verified")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun appKeysUseAndroidKeyStore() {
        click("SHOW KEYSTORE")
        expectVisible("Keystore ready")
        click("CHECK KEYSTORE")
        expectVisible("Keystore keys verified")
        assertLog("KeyStoreFixture", "app keys: AES-GCM round trip, EC signature over the KeyMint certificate, listing and deletion verified")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun visibleActivityReceivesAudioFocusCallbacks() {
        click("SHOW AUDIO FOCUS")
        expectVisible("Audio focus ready")
        click("CHECK AUDIO FOCUS")
        expectVisible("Audio focus callbacks verified")
        assertLog("AudioFocusFixture", "visible task: granted, transient loss, regain, abandonment; main-thread callback order verified")
        assertColorArea(CYAN, 5_000)
    }

    @Test
    fun recreationRestoresSavedCounter() {
        click("INCREMENT COUNTER")
        click("RECREATE ACTIVITY")
        expectVisible("Counter: 1")
        assertLog("WindowFixture", "\"event\":\"save\"")
        assertLog("WindowFixture", "\"event\":\"restore\"")
    }

    @Test
    fun permissionRequestReturnsConfiguredGrants() {
        click("CHECK PERMISSION RESULTS")
        expectVisible("PERMISSION RESULTS VERIFIED")
        assertLog("WindowFixture", "\"event\":\"permission-result\"")
        click("INCREMENT COUNTER")
        expectVisible("Counter: 1")
    }

    @Test
    fun textureViewRendersResizesAndReleases() {
        click("SHOW TEXTUREVIEW")
        expectVisible("Texture cyan frame verified")
        assertColorArea(CYAN, 8_000)
        click("RESIZE TEXTURE")
        expectVisible("Texture resized magenta frame verified")
        assertColorArea(MAGENTA, 16_000)
        click("REMOVE TEXTURE")
        expectVisible("Texture released")
        assertLog("TextureFixture", "surface released after detach")
    }

    @Test
    fun frameCallbacksFollowSubmissionAndResume() {
        click("SHOW FRAME CALLBACKS")
        expectVisible("Frame callbacks verified")
        assertLog("FrameFixture", "commit order=first,second,fromDraw; unattached callback not submitted")
        assertLog("FrameFixture", "root draw-start precedes draw listener; removal stops callbacks")
        assertColorArea(BLUE, 40_000)
        click("COVER FRAME WINDOW")
        expectVisible("RETURN TO FRAME WINDOW")
        click("RETURN TO FRAME WINDOW")
        expectVisible("Resumed frame committed")
        assertLog("FrameFixture", "stop,resume,commit; callbacks completed once on main")
        assertColorArea(CYAN, 40_000)
    }

    @Test
    fun layoutSchedulingAndNonModalPopupInput() {
        click("SHOW LAYOUT AND POPUPS")
        expectVisible("Layout scheduling verified")
        assertLog("LayoutFixture", "draw-only invalidation; reentrant second pass; post after layout; no duplicate traversal")
        click("SHOW POPUP WINDOWS")
        expectVisible("Two popup windows laid out")
        assertLog("LayoutFixture", "shared main queue: first=80x90,second=120x130")
        assertColorArea(CYAN, 5_000)
        assertColorArea(MAGENTA, 12_000)
        click("INCREMENT UNDERLYING")
        expectVisible("UNDERLYING CLICKS: 1")
        waitFor(description("Close first popup"))
        waitFor(description("Close second popup"))
        click(description("Close first popup"))
        waitUntilGone(description("Close first popup"))
        waitFor(description("Close second popup"))
        assertColorArea(MAGENTA, 12_000)
        click(description("Close second popup"))
        expectVisible("Popup windows dismissed")
        waitUntilGone(description("Close second popup"))
        expectVisible("UNDERLYING CLICKS: 1")
    }

    @Test
    fun surfaceViewFollowsAttachmentVisibilityAndDetach() {
        click("SHOW SURFACEVIEW")
        expectVisible("Invisible attached surface created")
        click("SHOW ATTACHED SURFACE")
        expectVisible("Attached surface blue")
        assertColorArea(BLUE, 25_000)
        click("HIDE ATTACHED SURFACE")
        expectVisible("Hidden attached surface retained")
        assertColorArea(BLUE, 0, 0)
        click("FOLLOW VISIBILITY")
        expectVisible("Hidden surface destroyed")
        click("RECREATE VISIBLE SURFACE")
        expectVisible("Visible surface recreated magenta")
        assertColorArea(MAGENTA, 25_000)
        click("DETACH SURFACE")
        expectVisible("Detached surface destroyed")
        assertColorArea(MAGENTA, 0, 0)
        assertLog("SurfaceLifecycleFixture", "Detached surface destroyed: created,destroyed,created,destroyed")
    }

    /** A virtual AccessibilityNodeProvider child is reachable and actionable
     * through the stock interaction connection, not only real views. */
    @Test
    fun virtualAccessibilityNodeIsReachableAndActionable() {
        click("SHOW VIRTUAL ACCESSIBILITY")
        expectVisible("Virtual provider ready")
        assertColorArea(GREEN, 20_000)
        val root = checkNotNull(UiTest.instrumentation.uiAutomation.rootInActiveWindow)
        val passwords = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isPassword) passwords += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        assertEquals("one virtual password child", 1, passwords.size)
        assertEquals(0, passwords.single().childCount)
        assertTrue(passwords.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        expectVisible("Virtual action 7 clicks=1")
    }

    @Test
    fun windowRegistryPreservesLayoutAndDefersRemoval() {
        click("SHOW WINDOW REGISTRY")
        expectVisible("Window registry ready")
        click("CHECK WINDOW REGISTRY")
        expectVisible("Window registry verified")
        assertLog("WindowRegistryFixture", "listener deduplication, snapshots, token and name lookup, layout preservation, deferred close and panel retention verified")
        assertLog("WindowRegistryFixture", "registered window context: attach, type enforcement, metrics and idempotent release verified")
        assertColorArea(CYAN, 5_000)
    }

    private companion object {
        const val CYAN = 0xff00ffff.toInt()
        const val MAGENTA = 0xffff00ff.toInt()
        const val BLUE = 0xff0000ff.toInt()
        const val GREEN = 0xff00ff00.toInt()
    }
}
