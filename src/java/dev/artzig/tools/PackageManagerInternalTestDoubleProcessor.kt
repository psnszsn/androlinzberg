package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.TypeKind

/**
 * In-process test doubles of the stock PackageManagerInternal (the role
 * Mockito mocks play in AOSP's server tests), like java.lang.reflect.Proxy for
 * this abstract class: every method passes its name and arguments to a
 * handler, which answers the calls its subject makes and reports others as
 * unsupported. Kotlin tests construct it reflectively, as generated classes
 * are compiled after Kotlin.
 */
class PackageManagerInternalTestDoubleProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "android.content.pm.PackageManagerInternal"
    override val adapterName = "dev.artzig.testing.PackageManagerInternalDouble"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final java.util.function.BiFunction<String, Object[], Object> handler;")
        writer.appendLine("    public PackageManagerInternalDouble(java.util.function.BiFunction<String, Object[], Object> handler) {")
        writer.appendLine("        this.handler = handler;")
        writer.appendLine("    }")
    }

    override fun bodyFor(method: ExecutableElement): String {
        val arguments = method.parameters.indices.joinToString(", ", "new Object[] {", "}") { "p$it" }
        val call = "handler.apply(\"${method.simpleName}\", $arguments)"
        val returnType = method.returnType
        return when {
            returnType.kind == TypeKind.VOID -> "$call;"
            returnType.kind.isPrimitive -> "return (${processingEnv.typeUtils.boxedClass(processingEnv.typeUtils.getPrimitiveType(returnType.kind)).qualifiedName}) $call;"
            else -> "return (${returnType}) $call;"
        }
    }
}
