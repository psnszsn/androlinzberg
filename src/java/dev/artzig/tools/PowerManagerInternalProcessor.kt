package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement

/** Exact stock power local-interface signatures over the existing session power authority. */
class PowerManagerInternalProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "android.os.PowerManagerInternal"
    override val adapterName = "android.os.HostedPowerManagerInternal"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final dev.artzig.system.HostedPowerManagerService host;")
        writer.appendLine("    public HostedPowerManagerInternal(dev.artzig.system.HostedPowerManagerService host) {")
        writer.appendLine("        this.host = host;")
        writer.appendLine("    }")
    }

    private fun signature(method: ExecutableElement): String =
        method.simpleName.toString() + method.parameters.joinToString(",", "(", ")") {
            processingEnv.typeUtils.erasure(it.asType()).toString()
        }

    override fun bodyFor(method: ExecutableElement): String {
        val target = processingEnv.elementUtils.getTypeElement(
            "dev.artzig.system.HostedPowerManagerService")
        val hostMethod = processingEnv.elementUtils.getAllMembers(target)
            .filterIsInstance<ExecutableElement>()
            .firstOrNull { javax.lang.model.element.Modifier.PUBLIC in it.modifiers &&
                signature(it) == signature(method) }
        val name = method.simpleName.toString()
        if (hostMethod != null) {
            check(processingEnv.typeUtils.isSameType(method.returnType, hostMethod.returnType)) {
                "Host return type differs from PowerManagerInternal.$name"
            }
            val args = method.parameters.indices.joinToString(", ") { "p$it" }
            return (if (method.returnType.kind == javax.lang.model.type.TypeKind.VOID) "" else "return ") +
                "host.$name($args);"
        }
        return "throw dev.artzig.ArtzigTestDiagnostics.unsupported(" +
            "\"PowerManagerInternal\", \"$name\", \"not hosted\");"
    }
}
