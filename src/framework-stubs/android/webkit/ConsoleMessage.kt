package android.webkit

class ConsoleMessage(
    private val message: String?,
    private val sourceId: String?,
    private val lineNumber: Int,
    private val messageLevel: MessageLevel?,
) {
    enum class MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }

    fun message(): String? = message
    fun sourceId(): String? = sourceId
    fun lineNumber(): Int = lineNumber
    fun messageLevel(): MessageLevel? = messageLevel
}
