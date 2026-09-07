package dev.artzig

/** Names used by the existing decoder adapter; capability policy lives in stock Java. */
object HostedCodecCatalog {
    @JvmStatic external fun backendNameFor(codecName: String?): String?
    @JvmStatic external fun decoderNameForMime(mime: String?): String?
}
