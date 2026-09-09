package dev.artzig.window

/** Shared by system_server and its app processes during the staged WM migration. */
object WindowBackend {
    @JvmField val stock: Boolean = when (val mode = System.getenv("AZK_WINDOW_BACKEND")) {
        null -> android.view.HostedRootFrames.stockClients
        "legacy" -> false
        "stock" -> true
        else -> throw IllegalArgumentException("Unknown AZK_WINDOW_BACKEND: $mode")
    }
}
