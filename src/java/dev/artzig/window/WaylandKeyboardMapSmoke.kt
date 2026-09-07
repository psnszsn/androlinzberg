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
        val characters = IntArray(WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT)
        for (state in 0 until WaylandKeyboardMap.STATE_COUNT) {
            val upper = (state and 1 != 0) xor (state and 2 != 0)
            characters[30 * 64 + state] = if (state and 4 != 0) 'ä'.code else if (upper) 'Q'.code else 'q'.code
            characters[86 * 64 + state] = '<'.code
            characters[43 * 64 + state] = '\\'.code
            characters[40 * 64 + state] = 0x0301 // Stock combining-accent/dead-key convention.
        }
        val symbols = IntArray(WaylandKeyboardMap.SCAN_COUNT)
        symbols[58] = 0xffe3 // Caps remapped to Control_L.
        check(WaylandKeyboardMap.keyCode(58, symbols[58]) == KeyEvent.KEYCODE_CTRL_LEFT)
        check(WaylandKeyboardMap.keyCode(58, 0xff1b) == KeyEvent.KEYCODE_ESCAPE)
        check(WaylandKeyboardMap.keyCode(30, 'q'.code) == KeyEvent.KEYCODE_A)
        check(WaylandKeyboardMap.keyCode(100, 0xfe03) == KeyEvent.KEYCODE_ALT_RIGHT)
        check(WaylandKeyboardMap.keyCode(125, 0xffeb) == KeyEvent.KEYCODE_META_LEFT)
        check(runCatching { WaylandKeyboardMap.compile(characters, IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        val map = WaylandKeyboardMap.compile(characters, symbols)
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
        service.publishKeyboardMap(map)
        service.publishKeyboardMap(map)
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
        check(runCatching { WaylandKeyboardMap.compile(IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        characters[30 * 64] = 0x1f600
        check(runCatching { WaylandKeyboardMap.compile(characters) }.exceptionOrNull() is IllegalArgumentException)
        println("Wayland keyboard map: layout, modifiers, ISO key, dead accents, parcel and device generations passed")
    }
}
