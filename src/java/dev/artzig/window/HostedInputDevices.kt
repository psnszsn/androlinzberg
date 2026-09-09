package dev.artzig.window

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/** Host device descriptions around pinned KeyCharacterMap/KeyLayoutMap and
 * CursorInputMapper event metadata. Native InputDispatcher owns delivery. */
internal object HostedInputDevices {
    const val HOST_KEYBOARD_DEVICE_ID = 1
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

    internal fun createMouseEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        buttonState: Int,
        actionButton: Int,
        x: Float,
        y: Float,
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
        metaState: Int = 0,
    ): MotionEvent = createEvent(
        downTime,
        eventTime,
        action,
        InputDevice.SOURCE_MOUSE,
        MotionEvent.TOOL_TYPE_MOUSE,
        buttonState,
        actionButton,
        x,
        y,
        horizontalScroll,
        verticalScroll,
        metaState,
    )

    private fun createEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        source: Int,
        toolType: Int,
        buttonState: Int,
        actionButton: Int,
        x: Float,
        y: Float,
        horizontalScroll: Float,
        verticalScroll: Float,
        metaState: Int = 0,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            // AOSP CursorInputMapper reports pressure 1 while a primary/secondary/tertiary mouse
            // button holds the pointer down, and 0 once the pointer returns to hover.
            pressure = if (buttonState and POINTER_DOWN_BUTTONS != 0) 1f else 0f
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            metaState,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            0,
        ).apply {
            if (action == MotionEvent.ACTION_BUTTON_PRESS ||
                action == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                setActionButton(actionButton)
            }
        }
    }

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

    /** EventHub clears layout flags when an explicit KCM remap wins. */
    internal fun androidKeyFlagsForScanCode(scanCode: Int, keyCode: Int): Int =
        if (scanCode in 0 until HOST_SCAN_COUNT && keyCode == androidKeyCodeForScanCode(scanCode))
            genericKeyLayout[scanCode * 2 + 1] else 0

    @JvmStatic private external fun nativeLoadKeyLayout(contents: String): IntArray
    private const val POINTER_DOWN_BUTTONS =
        MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY
}
