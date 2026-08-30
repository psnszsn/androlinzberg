package dev.artzig

/** Verifies that a hosted Runtime.exit reaches ART's fast-exit path without a native crash. */
internal object JvmExitSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().exit(23)
    }
}
