package dev.artzig.window

/**
 * Android SurroundingText and InputConnection use UTF-16; text-input-v3 uses
 * UTF-8 byte offsets and excludes the composing span from surrounding text.
 * This host seam owns that conversion. It never trims a selection or splits a
 * Unicode scalar, and omits unavailable context instead of inventing offsets.
 */
object WaylandTextContext {
    const val MAX_BYTES = 4000

    data class Android(val text: String, val anchor: Int, val cursor: Int,
        val offset: Int = 0, val composingStart: Int = -1, val composingEnd: Int = -1)
    data class Wire(val text: String, val cursor: Int, val anchor: Int)
    data class Deletion(val before: Int, val after: Int)

    /** Byte offsets for every UTF-16 boundary; -1 denotes a surrogate interior. */
    private fun offsets(text: String): IntArray? {
        val result = IntArray(text.length + 1) { -1 }
        var index = 0
        var bytes = 0
        result[0] = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\u0000' -> return null // Wayland strings cannot carry NUL.
                ch.isHighSurrogate() -> {
                    if (index + 1 == text.length || !text[index + 1].isLowSurrogate()) return null
                    bytes += 4
                    index += 2
                }
                ch.isLowSurrogate() -> return null
                else -> {
                    bytes += if (ch.code < 0x80) 1 else if (ch.code < 0x800) 2 else 3
                    index++
                }
            }
            result[index] = bytes
        }
        return result
    }

    fun utf16Offset(text: String, byteOffset: Int): Int? {
        if (byteOffset < 0) return null
        val offsets = offsets(text) ?: return null
        return offsets.indexOf(byteOffset).takeIf { it >= 0 }
    }

    fun byteOffset(text: String, utf16Offset: Int): Int? {
        val offsets = offsets(text) ?: return null
        return offsets.getOrNull(utf16Offset)?.takeIf { it >= 0 }
    }

    /** text-input-v3 set_surrounding_text replaces existing preedit with its cursor. */
    fun withoutPreedit(value: Android): Android? {
        val positions = offsets(value.text) ?: return null
        if (value.offset < -1 || positions.getOrNull(value.anchor)?.takeIf { it >= 0 } == null ||
            positions.getOrNull(value.cursor)?.takeIf { it >= 0 } == null) return null
        if (value.composingStart == -1 && value.composingEnd == -1) return value
        if (value.offset == -1) return null // Unknown document origin cannot locate an absolute span.
        val start = value.composingStart.toLong() - value.offset
        val end = value.composingEnd.toLong() - value.offset
        // Partial composing context cannot truthfully describe the cursor after removal.
        if (start < 0 || end < start || end > value.text.length) return null
        val from = start.toInt()
        val to = end.toInt()
        if (positions[from] < 0 || positions[to] < 0) return null
        fun relocated(index: Int) = when {
            index <= from -> index
            index < to -> from
            else -> index - (to - from)
        }
        return Android(value.text.removeRange(from, to), relocated(value.anchor),
            relocated(value.cursor), value.offset)
    }

    fun surrounding(value: Android): Wire? {
        val plain = withoutPreedit(value) ?: return null
        val positions = offsets(plain.text) ?: return null
        val low = minOf(plain.anchor, plain.cursor)
        val high = maxOf(plain.anchor, plain.cursor)
        val selectedBytes = positions[high] - positions[low]
        if (selectedBytes > MAX_BYTES) return null
        val total = positions.last()
        val spare = MAX_BYTES - selectedBytes
        val startByte = maxOf(0, minOf(positions[low] - spare / 2, total - MAX_BYTES))
        var start = positions.indexOfFirst { it >= startByte }
        var end = positions.indexOfLast { it >= 0 && it <= positions[start] + MAX_BYTES }
        if (end < high) { start = low; end = positions.indexOfLast { it >= 0 && it <= positions[start] + MAX_BYTES } }
        check(start <= low && high <= end)
        return Wire(plain.text.substring(start, end), positions[plain.cursor] - positions[start],
            positions[plain.anchor] - positions[start])
    }

    /** Deletion excludes selection, just like BaseInputConnection.deleteSurroundingText. */
    fun deletion(value: Android, beforeBytes: Int, afterBytes: Int): Deletion? {
        if (beforeBytes < 0 || afterBytes < 0) return null
        val plain = withoutPreedit(value) ?: return null
        val positions = offsets(plain.text) ?: return null
        val low = minOf(plain.anchor, plain.cursor)
        val high = maxOf(plain.anchor, plain.cursor)
        val before = positions[low].toLong() - beforeBytes
        val after = positions[high].toLong() + afterBytes
        if (before < 0 || after > positions.last()) return null
        val start = positions.indexOf(before.toInt())
        val end = positions.indexOf(after.toInt())
        if (start < 0 || end < 0) return null
        return Deletion(low - start, end - high)
    }
}
