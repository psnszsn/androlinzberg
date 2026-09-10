package dev.artzig.system

import android.net.Credentials
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Pinned ZygoteConnection/ZygoteArguments over host exec. Android's fork loop
 * cannot run inside the hosted system process. Only framing and bounded native
 * parser ownership live here; the original parser owns Android argument rules. */
internal object HostedZygoteProtocol {
    private const val MAX_BYTES = 32768 // Original native command buffer.
    private val parserLock = Any()
    private val bufferType = Class.forName("com.android.internal.os.ZygoteCommandBuffer")
    private val argumentType = Class.forName("com.android.internal.os.ZygoteArguments")
    private val constructor = bufferType.getDeclaredConstructor(Array<String>::class.java)
        .apply { isAccessible = true }
    private val parse = argumentType.getDeclaredMethod("getInstance", bufferType)
        .apply { isAccessible = true }
    private val close = bufferType.getDeclaredMethod("close").apply { isAccessible = true }
    private val fields = argumentType.declaredFields.associateBy { it.name }
        .onEach { (_, field) -> field.isAccessible = true }
    private val zygote = Class.forName("com.android.internal.os.Zygote")

    class Arguments internal constructor(private val value: Any) {
        fun int(name: String) = checkNotNull(fields[name]).getInt(value)
        fun long(name: String) = checkNotNull(fields[name]).getLong(value)
        fun flag(name: String) = checkNotNull(fields[name]).getBoolean(value)
        @Suppress("UNCHECKED_CAST") fun <T> value(name: String): T? = checkNotNull(fields[name]).get(value) as T?

        fun applyPolicy(peer: Credentials) {
            invokePolicy("applyUidSecurityPolicy", peer)
            invokePolicy("applyInvokeWithSecurityPolicy", peer)
            invokePolicy("applyDebuggerSystemProperty")
            invokePolicy("applyInvokeWithSystemProperty")
        }

        private fun invokePolicy(name: String, peer: Credentials? = null) {
            val types = if (peer == null) arrayOf(argumentType) else arrayOf(argumentType, Credentials::class.java)
            val args = if (peer == null) arrayOf(value) else arrayOf(value, peer)
            try { zygote.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(null, *args) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    /** One bounded command, with no native allocation held during socket reads. */
    fun read(input: InputStream): Arguments? {
        var remaining = MAX_BYTES
        fun line(eofAllowed: Boolean): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) {
                    if (eofAllowed && bytes.size() == 0) return null
                    throw EOFException("Incomplete process launch command")
                }
                if (--remaining < 0) throw IOException("Process launch command exceeds native buffer")
                if (byte == 10) break
                if (byte == 0 || byte == 13) throw IOException("Invalid process launch line")
                bytes.write(byte)
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
        val countLine = line(true) ?: return null
        val count = countLine.toIntOrNull() ?: throw IOException("Invalid process launch argument count")
        if (count <= 0 || count >= MAX_BYTES / 2) throw IOException("Invalid process launch argument count")
        return parse(Array(count) { checkNotNull(line(false)) })
    }

    fun parse(arguments: Array<String>): Arguments {
        require(arguments.isNotEmpty() && arguments.size < MAX_BYTES / 2)
        // JNI's String[] constructor encodes modified UTF-8, which can be larger
        // than the wire UTF-8 (notably supplementary characters). Check the exact
        // native allocation bound before its fatal CHECK rather than afterward.
        var bytes = arguments.size.toString().length + 1
        for (argument in arguments) {
            require(argument.length <= MAX_BYTES)
            require(argument.none { it == '\n' || it == '\r' || it == '\u0000' })
            for (char in argument) bytes += when {
                char.code in 1..127 -> 1
                char.code <= 2047 -> 2
                else -> 3
            }
            bytes++
            require(bytes <= MAX_BYTES) { "Process launch command exceeds native buffer" }
        }
        synchronized(parserLock) {
            val buffer = constructor.newInstance(arguments as Any)
            try { return Arguments(checkNotNull(parse.invoke(null, buffer))) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
            finally { close.invoke(buffer) }
        }
    }
}
