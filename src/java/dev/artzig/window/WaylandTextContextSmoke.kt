package dev.artzig.window

object WaylandTextContextSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val unicode = "aé中😀z"
        check(WaylandTextContext.byteOffset(unicode, 5) == 10)
        check(WaylandTextContext.utf16Offset(unicode, 10) == 5)
        check(WaylandTextContext.byteOffset(unicode, 4) == null)
        check(WaylandTextContext.utf16Offset(unicode, 8) == null)
        for (invalid in listOf("\uD800", "\uDC00", "a\u0000b")) {
            check(WaylandTextContext.surrounding(WaylandTextContext.Android(invalid, 0, 0)) == null)
        }
        val reversed = checkNotNull(WaylandTextContext.surrounding(
            WaylandTextContext.Android(unicode, 5, 2)))
        check(reversed == WaylandTextContext.Wire(unicode, 3, 10))
        check(WaylandTextContext.surrounding(WaylandTextContext.Android(unicode, 5, 2, -1)) == reversed)
        val composing = WaylandTextContext.Android("ab拼😀cd", 5, 3, 100, 102, 105)
        check(WaylandTextContext.withoutPreedit(composing) == WaylandTextContext.Android("abcd", 2, 2, 100))
        check(WaylandTextContext.surrounding(composing) == WaylandTextContext.Wire("abcd", 2, 2))
        check(WaylandTextContext.withoutPreedit(composing.copy(composingStart = 99)) == null)
        check(WaylandTextContext.withoutPreedit(composing.copy(composingEnd = 104)) == null)
        val selection = WaylandTextContext.Android("a😀选éz", 3, 4)
        check(WaylandTextContext.deletion(selection, 4, 2) == WaylandTextContext.Deletion(2, 1))
        check(WaylandTextContext.deletion(selection.copy(anchor = 4, cursor = 3), 4, 2) == WaylandTextContext.Deletion(2, 1))
        check(WaylandTextContext.deletion(selection, 3, 2) == null)
        check(WaylandTextContext.deletion(selection, 4, 1) == null)
        check(WaylandTextContext.deletion(selection, Int.MAX_VALUE, 0) == null)
        check(WaylandTextContext.deletion(composing, 1, 1) == WaylandTextContext.Deletion(1, 1))
        val long = "😀".repeat(2000) + "SELECT" + "é".repeat(3000)
        val wire = checkNotNull(WaylandTextContext.surrounding(WaylandTextContext.Android(long, 4000, 4006)))
        check(wire.text.toByteArray(Charsets.UTF_8).size <= WaylandTextContext.MAX_BYTES)
        val anchor = checkNotNull(WaylandTextContext.utf16Offset(wire.text, wire.anchor))
        val cursor = checkNotNull(WaylandTextContext.utf16Offset(wire.text, wire.cursor))
        check(wire.text.substring(anchor, cursor) == "SELECT")
        check(WaylandTextContext.surrounding(WaylandTextContext.Android(long, 0, 4000)) == null)
        val exact = "😀".repeat(1000)
        check(WaylandTextContext.surrounding(WaylandTextContext.Android(exact, 2000, 0)) ==
            WaylandTextContext.Wire(exact, 0, 4000))
        println("WaylandTextContext: Unicode boundaries, preedit removal, selection, byte deletion and bounded surrounding text passed")
    }
}
