package dev.artzig

/** Verifies that a hosted Runtime.exit reaches ART's fast-exit path without a native crash. */
internal object JvmExitSmoke {
    @JvmStatic
    private external fun installLibcExitTrap()

    @JvmStatic
    fun main(args: Array<String>) {
        installLibcExitTrap()
        Runtime.getRuntime().exit(23)
    }
}
