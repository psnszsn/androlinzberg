package dev.artzig.window

import android.view.HostedInputDispatcher
import android.view.KeyCharacterMap
import android.view.KeyEvent

/** Converts the seat's XKB character matrix to the pinned KeyCharacterMap parser
 * format. EventHub normally selects a device KCM; that hardware owner is absent
 * here. Stock KeyCharacterMap retains Unicode/dead-key lookup and parceling.
 * Rows are evdev scan codes; columns are Shift, Caps, Level3, Num, Ctrl, Alt bits.
 */
internal object WaylandKeyboardMap {
    const val SCAN_COUNT = 256
    const val STATE_COUNT = 64
    private val modifiers = arrayOf("shift", "capslock", "ralt", "numlock", "ctrl", "lalt")

    // Generic.kl aliases ISO's extra key to BACKSLASH. A device KCM may remap
    // scan codes; keep this independent character position as Android's RO key.
    fun keyCode(scan: Int): Int = if (scan == 86) KeyEvent.KEYCODE_RO else
        HostedInputDispatcher.androidKeyCodeForScanCode(scan)

    fun compile(characters: IntArray): KeyCharacterMap {
        require(characters.size == SCAN_COUNT * STATE_COUNT) { "Invalid XKB character matrix" }
        require(characters.all { it == 0 || it in 1..0xffff && it !in 0xd800..0xdfff }) {
            "A KeyCharacterMap character must be a BMP scalar; supplementary text requires the IME bridge"
        }
        val rows = linkedMapOf<Int, IntArray>()
        for (scan in 0 until SCAN_COUNT) {
            val code = keyCode(scan)
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue
            val row = characters.copyOfRange(scan * STATE_COUNT, (scan + 1) * STATE_COUNT)
            if (row.all { it == 0 }) continue
            val previous = rows[code]
            require(previous == null || previous.contentEquals(row)) {
                "Distinct XKB characters share Android key code $code"
            }
            rows[code] = row
        }
        val contents = buildString {
            append("type FULL\nmap key 86 RO\n")
            for ((code, row) in rows) {
                append("key ").append(KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")).append(" {\n")
                val label = row[1].takeIf { it >= 32 } ?: row[0].takeIf { it >= 32 }
                if (label != null) append("label: ").append(literal(label)).append('\n')
                val number = row.firstOrNull { it in '0'.code..'9'.code }
                if (number != null) append("number: ").append(literal(number)).append('\n')
                // The stock parser prepends each behavior. Ascending masks put
                // every more-specific combination before its subsets at lookup.
                for (state in 0 until STATE_COUNT) {
                    append(if (state == 0) "base" else modifiers.indices
                        .filter { state and (1 shl it) != 0 }.joinToString("+") { modifiers[it] })
                    append(": ").append(if (row[state] == 0) "none" else literal(row[state])).append('\n')
                }
                append("}\n")
            }
        }
        return HostedInputDispatcher.createHostKeyCharacterMap(contents)
    }

    private fun literal(codePoint: Int) = "'\\u${codePoint.toString(16).padStart(4, '0')}'"
}
