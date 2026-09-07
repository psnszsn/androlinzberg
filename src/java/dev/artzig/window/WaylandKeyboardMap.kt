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

    // xkbcommon-keysyms.h names are stable protocol symbols. Keep Android's
    // Generic.kl positions for character keys; a compositor may explicitly
    // remap a physical position to a modifier or editing/navigation key.
    fun keyCode(scan: Int, symbol: Int = 0): Int = when (symbol) {
        0xffe1 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0xffe2 -> KeyEvent.KEYCODE_SHIFT_RIGHT
        0xffe3 -> KeyEvent.KEYCODE_CTRL_LEFT
        0xffe4 -> KeyEvent.KEYCODE_CTRL_RIGHT
        0xffe5 -> KeyEvent.KEYCODE_CAPS_LOCK
        0xffe6 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0xffe7, 0xffeb, 0xffed -> KeyEvent.KEYCODE_META_LEFT
        0xffe8, 0xffec, 0xffee -> KeyEvent.KEYCODE_META_RIGHT
        0xffe9 -> KeyEvent.KEYCODE_ALT_LEFT
        0xffea, 0xfe03, 0xff7e -> KeyEvent.KEYCODE_ALT_RIGHT
        0xff7f -> KeyEvent.KEYCODE_NUM_LOCK
        0xff14 -> KeyEvent.KEYCODE_SCROLL_LOCK
        0xff1b -> KeyEvent.KEYCODE_ESCAPE
        0xff08 -> KeyEvent.KEYCODE_DEL
        0xff09, 0xfe20 -> KeyEvent.KEYCODE_TAB
        0xff0d -> KeyEvent.KEYCODE_ENTER
        0xffff -> KeyEvent.KEYCODE_FORWARD_DEL
        0xff50 -> KeyEvent.KEYCODE_MOVE_HOME
        0xff51 -> KeyEvent.KEYCODE_DPAD_LEFT
        0xff52 -> KeyEvent.KEYCODE_DPAD_UP
        0xff53 -> KeyEvent.KEYCODE_DPAD_RIGHT
        0xff54 -> KeyEvent.KEYCODE_DPAD_DOWN
        0xff55 -> KeyEvent.KEYCODE_PAGE_UP
        0xff56 -> KeyEvent.KEYCODE_PAGE_DOWN
        0xff57 -> KeyEvent.KEYCODE_MOVE_END
        0xff63 -> KeyEvent.KEYCODE_INSERT
        0xff67 -> KeyEvent.KEYCODE_MENU
        in 0xffca..0xffd5 -> KeyEvent.KEYCODE_F13 + symbol - 0xffca
        in 0xffbe..0xffc9 -> KeyEvent.KEYCODE_F1 + symbol - 0xffbe
        // Generic.kl aliases ISO's extra key to BACKSLASH. Keep the extra
        // character position independent through a device KCM scan-code remap.
        else -> if (scan == 86) KeyEvent.KEYCODE_RO else HostedInputDispatcher.androidKeyCodeForScanCode(scan)
    }

    fun compile(characters: IntArray, symbols: IntArray = IntArray(SCAN_COUNT)): KeyCharacterMap {
        require(symbols.size == SCAN_COUNT && symbols.all { it >= 0 }) { "Invalid XKB symbol matrix" }
        require(characters.size == SCAN_COUNT * STATE_COUNT) { "Invalid XKB character matrix" }
        require(characters.all { it == 0 || it in 1..0xffff && it !in 0xd800..0xdfff }) {
            "A KeyCharacterMap character must be a BMP scalar; supplementary text requires the IME bridge"
        }
        val rows = linkedMapOf<Int, IntArray>()
        for (scan in 0 until SCAN_COUNT) {
            val code = keyCode(scan, symbols[scan])
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue
            val row = characters.copyOfRange(scan * STATE_COUNT, (scan + 1) * STATE_COUNT)
            // Pinned Generic.kcm gives DEL/ESCAPE no text and ENTER a newline.
            // XKB returns terminal control scalars (BS, CR, DEL, Ctrl+A ...).
            // QwertyKeyListener inserts any nonzero character before reaching
            // BaseKeyListener's editing-key handling, so those are not KCM text.
            for (state in row.indices) row[state] = when {
                code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL ||
                    code == KeyEvent.KEYCODE_ESCAPE -> 0
                (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    row[state] in 10..13 -> 10
                code == KeyEvent.KEYCODE_TAB && row[state] == 9 -> 9
                row[state] < 32 || row[state] == 127 -> 0
                else -> row[state]
            }
            if (row.all { it == 0 }) continue
            val previous = rows[code]
            require(previous == null || previous.contentEquals(row)) {
                "Distinct XKB characters share Android key code $code"
            }
            rows[code] = row
        }
        val contents = buildString {
            append("type FULL\n")
            for (scan in 0 until SCAN_COUNT) {
                val code = keyCode(scan, symbols[scan])
                if (code != HostedInputDispatcher.androidKeyCodeForScanCode(scan)) {
                    append("map key ").append(scan).append(' ')
                        .append(KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")).append('\n')
                }
            }
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

    /** Only positions present in the XKB layout are advertised. Modifier and
     * editing keys may have no text; their symbols still establish capability. */
    fun supportedKeyCodes(characters: IntArray, symbols: IntArray): IntArray {
        require(symbols.size == SCAN_COUNT && characters.size == SCAN_COUNT * STATE_COUNT)
        return (0 until SCAN_COUNT).filter { scan ->
            symbols[scan] != 0 || (0 until STATE_COUNT).any { characters[scan * STATE_COUNT + it] != 0 }
        }.map { keyCode(it, symbols[it]) }.filter { it != KeyEvent.KEYCODE_UNKNOWN }.distinct().toIntArray()
    }

    private fun literal(codePoint: Int) = "'\\u${codePoint.toString(16).padStart(4, '0')}'"
}
