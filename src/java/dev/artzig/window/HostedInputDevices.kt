package dev.artzig.window

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** The pinned KeyLayoutMap and KeyCharacterMap parsers for the seat's keymap
 * compiler (WaylandKeyboardMap). Stock InputReader and the host event hub own
 * the keyboard device itself. */
internal object HostedInputDevices {
    const val HOST_SCAN_COUNT = 768
    /**
     * Creates the character map installed on the hosted physical keyboard.
     *
     * AOSP TextKeyListener.getKeyListener() selects its editing behavior from the character-map
     * type. EventHub normally loads a FULL map from the device configuration, but the host has no
     * EventHub. The native seam uses AOSP KeyCharacterMap::loadContents() to preserve that contract;
     * WaylandKeyboardMap overlays the compositor's XKB layout.
     */
    @JvmStatic
    fun createHostKeyCharacterMap(): KeyCharacterMap =
        nativeLoadKeyCharacterMap(KeyCharacterMap.VIRTUAL_KEYBOARD, "type FULL\n")

    /** EventHub's map loader for the session's XKB-derived layout snapshot. */
    @JvmStatic
    fun createHostKeyCharacterMap(contents: String): KeyCharacterMap =
        nativeLoadKeyCharacterMap(KeyCharacterMap.VIRTUAL_KEYBOARD, contents)

    /** Logical wl_seat keyboard identity, distinct from the virtual fallback. */
    @JvmStatic
    fun createHostKeyCharacterMap(deviceId: Int, contents: String): KeyCharacterMap =
        nativeLoadKeyCharacterMap(deviceId, contents)

    @JvmStatic
    private external fun nativeLoadKeyCharacterMap(
        deviceId: Int,
        contents: String,
    ): KeyCharacterMap

    /** EventHub loads Generic.kl through KeyLayoutMap. Retain its parser,
     * usage/key syntax and flags; XKB remaps are overlaid by WaylandKeyboardMap. */
    private val genericKeyLayout: IntArray by lazy {
        val contents = checkNotNull(HostedInputDevices::class.java.getResourceAsStream(
            "/dev/artzig/input/Generic.kl")) { "Missing pinned Generic.kl" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        nativeLoadKeyLayout(contents).also { check(it.size == HOST_SCAN_COUNT * 2) }
    }

    internal fun androidKeyCodeForScanCode(scanCode: Int): Int =
        if (scanCode in 0 until HOST_SCAN_COUNT) genericKeyLayout[scanCode * 2] else KeyEvent.KEYCODE_UNKNOWN

    @JvmStatic private external fun nativeLoadKeyLayout(contents: String): IntArray
}
