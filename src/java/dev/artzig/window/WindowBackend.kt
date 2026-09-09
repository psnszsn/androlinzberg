package dev.artzig.window

/** Reject obsolete environment selections before using stock window services. */
object WindowBackend {
    @JvmField val stock: Boolean = when (val mode = System.getenv("AZK_WINDOW_BACKEND")) {
        null, "stock" -> true
        else -> throw IllegalArgumentException("Unsupported AZK_WINDOW_BACKEND: $mode; stock is required")
    }
}
