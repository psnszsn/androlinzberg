package dev.artzig.window

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/** Assertions at the stock InputEvent boundary, independent of app/root internals. */
object WaylandTaskInputSmoke {
    fun run() {
        val events = mutableListOf<InputEvent>()
        val order = mutableListOf<String>()
        val focus = mutableListOf<Int>()
        val repeats = mutableListOf<Triple<Int, Int, Boolean>>()
        var keyboardActive = false
        var layouts = 0
        var time = 100L
        val seat = WaylandTaskInput(object : WaylandTaskInput.Android {
            override fun keyboardActive(active: Boolean) { keyboardActive = active }
            override fun focusTask(taskId: Int) { focus.add(taskId); order.add("focus:$taskId") }
            override fun keyboardLayout(map: android.view.KeyCharacterMap) { layouts++ }
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) {
                repeats.add(Triple(timeoutMillis, delayMillis, enabled))
            }
            override fun dispatch(event: InputEvent, repeatable: Boolean) {
                check(event.displayId == 0)
                events.add(event.copy())
                order.add(when (event) {
                    is KeyEvent -> "key:${event.action}:${event.keyCode}:${event.isCanceled}"
                    is MotionEvent -> "motion:${event.actionMasked}"
                    else -> error("Unexpected input type")
                })
            }
        }, { ++time })
        fun clear() { events.forEach { it.recycle() }; events.clear(); order.clear() }
        val first = seat.open(TaskGeometry.Task(1, 1))
        val second = seat.open(TaskGeometry.Task(2, 2))
        try {
            seat.repeatConfiguration(50, 300)
            seat.repeatConfiguration(50, 300)
            check(repeats == listOf(Triple(300, 20, true)))
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 10f, 20f)
            check(events.isEmpty()) // No input before a committed projection.
            first.keyboardFocus(true)
            check(focus.isEmpty() && keyboardActive)
            first.committed(TaskGeometry.Bounds(100, 200, 400, 300), WaylandTaskShell.Size(200, 150))
            second.committed(TaskGeometry.Bounds(1000, 400, 400, 300), WaylandTaskShell.Size(200, 150))
            check(focus == listOf(1))
            first.pointer(WaylandTaskInput.POINTER_ENTER, 0, false, 10f, 20f)
            second.pointer(WaylandTaskInput.POINTER_ENTER, 0, false, 20f, 30f)
            check(focus == listOf(1)) // Pointer hover cannot steal keyboard focus.
            check((events[0] as MotionEvent).x == 120f && (events[0] as MotionEvent).y == 240f)
            check((events[1] as MotionEvent).x == 1040f && (events[1] as MotionEvent).rawY == 460f)
            clear()
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 10f, 20f)
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, false, 12f, 22f)
            check(events.map { (it as MotionEvent).actionMasked } == listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP, MotionEvent.ACTION_HOVER_MOVE))
            check((events[1] as MotionEvent).actionButton == MotionEvent.BUTTON_PRIMARY)
            check((events[2] as MotionEvent).buttonState == 0)
            val downTime = (events.first() as MotionEvent).downTime
            check(events.all { (it as MotionEvent).downTime == downTime })
            clear()
            first.captureChanged(true)
            check((events.single() as MotionEvent).action == MotionEvent.ACTION_HOVER_EXIT)
            clear()
            first.relativePointer(3f, -4f)
            val relative = events.single() as MotionEvent
            check(relative.source == android.view.InputDevice.SOURCE_MOUSE_RELATIVE)
            check(relative.action == MotionEvent.ACTION_MOVE && relative.x == 6f && relative.y == -8f)
            check(relative.getAxisValue(MotionEvent.AXIS_RELATIVE_X) == 6f)
            check(relative.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) == -8f)
            clear()
            first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 99f, 99f)
            check(events.isEmpty()) // wl_pointer absolute motion is irrelevant while locked.
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 99f, 99f)
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, false, 99f, 99f)
            check(events.map { (it as MotionEvent).actionMasked } == listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP))
            check(events.all { (it as MotionEvent).source == android.view.InputDevice.SOURCE_MOUSE_RELATIVE && it.x == 0f && it.y == 0f })
            clear()
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 99f, 99f)
            clear()
            first.captureChanged(false)
            check((events.single() as MotionEvent).action == MotionEvent.ACTION_CANCEL)
            check((events.single() as MotionEvent).source == android.view.InputDevice.SOURCE_MOUSE_RELATIVE)
            clear()
            first.relativePointer(5f, 5f)
            check(events.isEmpty())
            first.key(42, true) // Left shift.
            first.key(30, true) // A.
            first.key(30, true) // Repeats retain the initial down time.
            val key = events[1] as KeyEvent
            check(key.keyCode == KeyEvent.KEYCODE_A && key.isShiftPressed)
            check((events[2] as KeyEvent).downTime == key.downTime)
            clear()
            second.scroll(3f, 4f, 0f, 1f)
            check((events.single() as MotionEvent).metaState and KeyEvent.META_SHIFT_ON != 0)
            clear()
            second.keyboardFocus(true)
            check(order == listOf("key:1:${KeyEvent.KEYCODE_A}:true",
                "key:1:${KeyEvent.KEYCODE_SHIFT_LEFT}:true", "focus:2"))
            clear()
            first.key(30, true); first.key(30, false); second.key(30, false)
            check(events.isEmpty())
            second.scroll(3f, 4f, -2f, 1.5f)
            val scroll = events.single() as MotionEvent
            check(scroll.x == 1006f && scroll.y == 408f && scroll.action == MotionEvent.ACTION_SCROLL)
            check(scroll.getAxisValue(MotionEvent.AXIS_HSCROLL) == -2f &&
                scroll.getAxisValue(MotionEvent.AXIS_VSCROLL) == 1.5f)
            clear()
            first.touch(0, 1234, 10f, 20f, 8f, 4f, 0f)
            second.touch(0, 9000, 30f, 40f, 8f, 4f, 0f)
            val multi = events.last() as MotionEvent
            check(multi.actionMasked == MotionEvent.ACTION_POINTER_DOWN && multi.actionIndex == 1)
            check(multi.pointerCount == 2 && multi.getPointerId(0) == 0 && multi.getPointerId(1) == 1)
            check(multi.getX(0) == 120f && multi.getX(1) == 1060f)
            check(multi.getTouchMajor(0) == 16f && multi.getTouchMinor(0) == 8f)
            first.touch(1, 1234, 11f, 21f, 8f, 4f, 0f)
            check((events.last() as MotionEvent).actionMasked == MotionEvent.ACTION_POINTER_UP)
            second.touch(1, 9000, 31f, 41f, 8f, 4f, 0f)
            check((events.last() as MotionEvent).actionMasked == MotionEvent.ACTION_UP)
            clear()
            first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 10f, 20f)
            first.touch(0, 5, 10f, 20f, 4f, 2f, 0f)
            clear()
            first.committed(TaskGeometry.Bounds(500, 800, 200, 100), WaylandTaskShell.Size(200, 100))
            check(events.filterIsInstance<MotionEvent>().count { it.action == MotionEvent.ACTION_CANCEL } == 2)
            clear()
            first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 10f, 20f)
            check((events.single() as MotionEvent).x == 510f && (events.single() as MotionEvent).y == 820f)
            clear()
            second.key(30, true)
            clear()
            second.close()
            check(events.filterIsInstance<KeyEvent>().single().isCanceled)
            clear()
            second.keyboardFocus(true); second.key(30, true)
            second.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 10f, 20f)
            check(events.isEmpty())
            val replacement = seat.open(TaskGeometry.Task(2, 3))
            replacement.committed(TaskGeometry.Bounds(1000, 0, 400, 300), WaylandTaskShell.Size(200, 150))
            replacement.keyboardFocus(true)
            second.keyboardFocus(false)
            replacement.key(30, true)
            check(events.filterIsInstance<KeyEvent>().single().action == KeyEvent.ACTION_DOWN)
            clear()
            seat.repeatConfiguration(0, 400)
            check(events.filterIsInstance<KeyEvent>().single().isCanceled)
            check(repeats.last() == Triple(400, 1, false))
            clear()
            seat.modifiers(KeyEvent.META_SHIFT_ON or KeyEvent.META_CAPS_LOCK_ON)
            replacement.key(48, true) // Shift/Caps were held before this task entered.
            check((events.single() as KeyEvent).isShiftPressed && (events.single() as KeyEvent).isCapsLockOn)
            clear()
            replacement.scroll(2f, 3f, 0f, 1f)
            check((events.single() as MotionEvent).metaState and KeyEvent.META_SHIFT_ON != 0)
            clear()
            seat.keyboardLayout(1, IntArray(WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT))
            check(events.filterIsInstance<KeyEvent>().single().isCanceled)
            check(layouts == 1)
            seat.keyboardLayout(1, IntArray(0)); seat.keyboardLayout(0, IntArray(0))
            check(layouts == 1) // Duplicate/stale snapshots neither parse nor cancel again.
            clear()
            seat.modifiers(0)
            replacement.key(86, true)
            check((events.single() as KeyEvent).keyCode == KeyEvent.KEYCODE_RO)
            check(!(events.single() as KeyEvent).isShiftPressed)
            val symbols = IntArray(WaylandKeyboardMap.SCAN_COUNT).apply { this[58] = 0xffe3 }
            seat.keyboardLayout(2, IntArray(WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT), symbols)
            check((events.last() as KeyEvent).isCanceled) // Old layout's held ISO key is released.
            symbols[58] = 0xffe5 // Caller mutation cannot change the installed layout.
            clear()
            replacement.key(58, true)
            replacement.key(30, true)
            check((events[0] as KeyEvent).keyCode == KeyEvent.KEYCODE_CTRL_LEFT)
            check((events[1] as KeyEvent).isCtrlPressed && !(events[1] as KeyEvent).isCapsLockOn)
            clear()
            replacement.key(29, true) // Both physical keys now mean Control_L.
            replacement.key(58, false)
            check((events.last() as KeyEvent).isCtrlPressed)
            replacement.key(29, false)
            check(!(events.last() as KeyEvent).isCtrlPressed)
            replacement.close()
            check(!keyboardActive)
        } finally { first.close(); second.close(); clear() }
        println("Wayland task input: projection, button ordering, focus, key cancellation, touch IDs and stale generations passed")
    }
}
