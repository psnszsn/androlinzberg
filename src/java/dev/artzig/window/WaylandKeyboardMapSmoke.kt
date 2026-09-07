package dev.artzig.window

import android.hardware.input.IInputDevicesChangedListener
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.Parcel
import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.artzig.system.HostedInputManagerService

object WaylandKeyboardMapSmoke {
    fun run() {
        val layout = android.view.HostedInputDispatcher
        check(layout.androidKeyCodeForScanCode(120) == KeyEvent.KEYCODE_RECENT_APPS)
        check(layout.androidKeyCodeForScanCode(164) == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        check(layout.androidKeyCodeForScanCode(582) == KeyEvent.KEYCODE_VOICE_ASSIST)
        check(layout.androidKeyCodeForScanCode(659) == KeyEvent.KEYCODE_MACRO_4)
        check(layout.androidKeyCodeForScanCode(466) == KeyEvent.KEYCODE_F1)
        check(layout.androidKeyFlagsForScanCode(466, KeyEvent.KEYCODE_F1) == 4)
        check(layout.androidKeyFlagsForScanCode(466, KeyEvent.KEYCODE_ESCAPE) == 0)
        check(layout.androidKeyCodeForScanCode(-1) == KeyEvent.KEYCODE_UNKNOWN)
        check(layout.androidKeyCodeForScanCode(768) == KeyEvent.KEYCODE_UNKNOWN)
        val characters = IntArray(WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT)
        for (state in 0 until WaylandKeyboardMap.STATE_COUNT) {
            val upper = (state and 1 != 0) xor (state and 2 != 0)
            characters[30 * 64 + state] = if (state and 4 != 0) 'ä'.code else if (upper) 'Q'.code else 'q'.code
            characters[16 * 64 + state] = 'w'.code
            characters[86 * 64 + state] = '<'.code
            characters[43 * 64 + state] = '\\'.code
            characters[40 * 64 + state] = 0x0301 // Stock combining-accent/dead-key convention.
            characters[14 * 64 + state] = 8 // XKB BackSpace is an editing key, not text.
            characters[111 * 64 + state] = 127
            characters[1 * 64 + state] = 27
            characters[28 * 64 + state] = 13
            characters[15 * 64 + state] = 9
        }
        val symbols = IntArray(WaylandKeyboardMap.SCAN_COUNT)
        symbols[582] = 0x1008ff69 // XF86VoiceCommand establishes high-scan capability.
        symbols[58] = 0xffe3 // Caps remapped to Control_L.
        check(WaylandKeyboardMap.keyCode(58, symbols[58]) == KeyEvent.KEYCODE_CTRL_LEFT)
        check(WaylandKeyboardMap.keyCode(58, 0xff1b) == KeyEvent.KEYCODE_ESCAPE)
        check(WaylandKeyboardMap.keyCode(58, 0xffe6) == KeyEvent.KEYCODE_SHIFT_LEFT)
        check(WaylandKeyboardMap.keyCode(58, 0xffca) == KeyEvent.KEYCODE_F13)
        check(WaylandKeyboardMap.keyCode(58, 0xffd5) == KeyEvent.KEYCODE_F24)
        check(WaylandKeyboardMap.keyCode(30, 'q'.code) == KeyEvent.KEYCODE_A)
        check(WaylandKeyboardMap.keyCode(100, 0xfe03) == KeyEvent.KEYCODE_ALT_RIGHT)
        check(WaylandKeyboardMap.keyCode(125, 0xffeb) == KeyEvent.KEYCODE_META_LEFT)
        check(runCatching { WaylandKeyboardMap.compile(characters, IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        val map = WaylandKeyboardMap.compile(characters, symbols)
        val keyboardMap = WaylandKeyboardMap.compile(characters, symbols, 1)
        check(checkNotNull(map.getEvents(charArrayOf('q'))).all { it.deviceId == -1 })
        check(checkNotNull(keyboardMap.getEvents(charArrayOf('q'))).all { it.deviceId == 1 })
        val supported = WaylandKeyboardMap.supportedKeyCodes(characters, symbols)
        check(KeyEvent.KEYCODE_CTRL_LEFT in supported && KeyEvent.KEYCODE_CAPS_LOCK !in supported)
        check(KeyEvent.KEYCODE_VOICE_ASSIST in supported)
        check(582 in WaylandKeyboardMap.supportedScanCodes(characters, symbols))
        check(KeyEvent.KEYCODE_DEL in supported && KeyEvent.KEYCODE_UNKNOWN !in supported)
        check(map.get(KeyEvent.KEYCODE_DEL, 0) == 0)
        check(map.get(KeyEvent.KEYCODE_FORWARD_DEL, 0) == 0)
        check(map.get(KeyEvent.KEYCODE_ESCAPE, 0) == 0)
        check(map.get(KeyEvent.KEYCODE_ENTER, 0) == 10)
        check(map.get(KeyEvent.KEYCODE_TAB, 0) == 9)
        check(map.keyboardType == KeyCharacterMap.FULL)
        check(map.get(KeyEvent.KEYCODE_A, 0) == 'q'.code)
        check(map.get(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON) == 'Q'.code)
        check(map.get(KeyEvent.KEYCODE_A, KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_SHIFT_ON) == 'q'.code)
        check(map.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON) == 'ä'.code)
        check(map.get(KeyEvent.KEYCODE_RO, 0) == '<'.code)
        check(map.get(KeyEvent.KEYCODE_BACKSLASH, 0) == '\\'.code)
        val accent = map.get(KeyEvent.KEYCODE_APOSTROPHE, 0)
        check(accent and KeyCharacterMap.COMBINING_ACCENT != 0)
        check(KeyCharacterMap.getDeadChar(accent and KeyCharacterMap.COMBINING_ACCENT_MASK, 'e'.code) == 'é'.code)
        val parcel = Parcel.obtain()
        try {
            map.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = KeyCharacterMap.CREATOR.createFromParcel(parcel)
            check(copy.get(KeyEvent.KEYCODE_A, 0) == 'q'.code)
        } finally { parcel.recycle() }
        val looper = checkNotNull(Looper.myLooper())
        val service = HostedInputManagerService(Handler(looper))
        val previous = checkNotNull(service.getInputDevice(KeyCharacterMap.VIRTUAL_KEYBOARD))
        val changes = mutableListOf<IntArray>()
        val listener = object : IInputDevicesChangedListener.Stub() {
            override fun onInputDevicesChanged(info: IntArray) {
                changes.add(info)
                // Callback can reenter device lookup: no service lock is held.
                check(service.getInputDevice(info[0])!!.generation == info[1])
            }
        }
        service.registerInputDevicesChangedListener(listener)
        check(runCatching { service.registerInputDevicesChangedListener(listener) }.exceptionOrNull() is SecurityException)
        check(runCatching { service.registerInputDevicesChangedListener(null) }.exceptionOrNull() is NullPointerException)
        service.publishKeyboardMap(map, supported, keyboardMap)
        service.publishKeyboardMap(map, supported, keyboardMap)
        val queriedKeys = intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CAPS_LOCK, Int.MAX_VALUE)
        val exists = BooleanArray(5) { true }
        check(service.hasKeys(-1, android.view.InputDevice.SOURCE_KEYBOARD, queriedKeys, exists))
        check(exists.contentEquals(booleanArrayOf(true, true, false, false, true)))
        check(!service.hasKeys(999, android.view.InputDevice.SOURCE_ANY, queriedKeys, exists))
        check(exists.contentEquals(booleanArrayOf(false, false, false, false, true)))
        check(!service.hasKeys(-1, android.view.InputDevice.SOURCE_CLASS_BUTTON, queriedKeys, exists))
        check(runCatching { service.hasKeys(-1, android.view.InputDevice.SOURCE_KEYBOARD, queriedKeys, BooleanArray(1)) }
            .exceptionOrNull() is IllegalArgumentException)
        val current = checkNotNull(service.getInputDevice(KeyCharacterMap.VIRTUAL_KEYBOARD))
        check(current.generation == previous.generation + 2)
        check(previous.keyCharacterMap.get(KeyEvent.KEYCODE_A, 0) == 0)
        check(current.keyCharacterMap.get(KeyEvent.KEYCODE_A, 0) == 'q'.code)
        while (changes.isEmpty()) {
            val message = checkNotNull(looper.queue.`pollDueMessage$artzig`())
            try { message.target.dispatchMessage(message) }
            finally { MessageQueue.`recycleDispatched$artzig`(message) }
        }
        check(changes.single().contentEquals(intArrayOf(KeyCharacterMap.VIRTUAL_KEYBOARD, current.generation)))
        fun awaitDeviceNotice(count: Int) {
            while (changes.size < count) {
                val message = checkNotNull(looper.queue.`pollDueMessage$artzig`())
                try { message.target.dispatchMessage(message) }
                finally { MessageQueue.`recycleDispatched$artzig`(message) }
            }
        }
        service.publishSeatCapabilities(1, 15)
        check(service.inputDeviceIds.contentEquals(intArrayOf(-1, 0, 1, 2)))
        val keyboard = checkNotNull(service.getInputDevice(1))
        check(!keyboard.isVirtual && keyboard.isExternal)
        check(keyboard.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        check(checkNotNull(keyboard.keyCharacterMap.getEvents(charArrayOf('q'))).all { it.deviceId == 1 })
        check(service.hasKeys(1, android.view.InputDevice.SOURCE_KEYBOARD, queriedKeys, exists))
        check(exists.contentEquals(booleanArrayOf(true, true, false, false, true)))
        val pointer = checkNotNull(service.getInputDevice(0))
        check(!service.hasKeys(0, android.view.InputDevice.SOURCE_MOUSE, queriedKeys, exists))
        check(pointer.supportsSource(android.view.InputDevice.SOURCE_MOUSE))
        check(pointer.supportsSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE))
        check(pointer.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_NONE)
        check(checkNotNull(service.getInputDevice(2)).supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN))
        check(service.getInputDevice(99) == null)
        awaitDeviceNotice(2)
        check(changes.last().filterIndexed { index, _ -> index % 2 == 0 } == listOf(-1, 0, 1, 2))
        service.publishSeatCapabilities(2, 2)
        check(service.getInputDevice(0) == null && service.getInputDevice(2) == null)
        check(service.inputDeviceIds.contentEquals(intArrayOf(-1, 1)))
        awaitDeviceNotice(3)
        service.publishSeatCapabilities(3, 3)
        val reappeared = checkNotNull(service.getInputDevice(0))
        check(reappeared.generation > pointer.generation)
        check(!reappeared.supportsSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE))
        service.publishSeatCapabilities(1, 15) // Late callback from an older seat state.
        check(service.getInputDevice(0) === reappeared && service.getInputDevice(2) == null)
        check(runCatching { service.publishSeatCapabilities(3, 7) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { service.publishSeatCapabilities(4, 16) }.exceptionOrNull() is IllegalArgumentException)
        awaitDeviceNotice(4)
        service.publishKeyboardMap(map, supported.filter { it != KeyEvent.KEYCODE_Q }.toIntArray(), keyboardMap)
        check(checkNotNull(service.getInputDevice(1)).keyboardType == android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
        check(keyboard.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) // Immutable old snapshot.
        awaitDeviceNotice(5)
        service.publishSeatCapabilities(4, 0)
        check(service.inputDeviceIds.contentEquals(intArrayOf(-1)))
        check(!service.hasKeys(1, android.view.InputDevice.SOURCE_KEYBOARD, queriedKeys, exists))
        check(service.hasKeys(-1, android.view.InputDevice.SOURCE_KEYBOARD, queriedKeys, exists))
        awaitDeviceNotice(6)
        check(runCatching { WaylandKeyboardMap.compile(IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        characters[30 * 64] = 0x1f600
        check(runCatching { WaylandKeyboardMap.compile(characters) }.exceptionOrNull() is IllegalArgumentException)
        println("Wayland keyboard map: layout, modifiers, ISO key, dead accents, parcel and seat device lifecycle passed")
    }
}
