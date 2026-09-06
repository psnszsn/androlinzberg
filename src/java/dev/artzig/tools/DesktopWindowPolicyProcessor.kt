package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement

/** Stock policy interface adapter retaining Java default window-layer methods. */
class DesktopWindowPolicyProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "com.android.server.policy.WindowManagerPolicy"
    override val adapterName = "com.android.server.policy.HostedDesktopWindowManagerPolicy"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final dev.artzig.window.HostedDesktopWindowPolicy host;")
        writer.appendLine("    public HostedDesktopWindowManagerPolicy(dev.artzig.window.HostedDesktopWindowPolicy host) {")
        writer.appendLine("        this.host = host;")
        writer.appendLine("    }")
    }

    private fun signature(method: ExecutableElement): String =
        method.simpleName.toString() + method.parameters.joinToString(",", "(", ")") {
            processingEnv.typeUtils.erasure(it.asType()).toString()
        }

    override fun bodyFor(method: ExecutableElement): String {
        val target = processingEnv.elementUtils.getTypeElement(
            "dev.artzig.window.HostedDesktopWindowPolicy")
        val hostMethod = processingEnv.elementUtils.getAllMembers(target)
            .filterIsInstance<ExecutableElement>()
            .firstOrNull { javax.lang.model.element.Modifier.PUBLIC in it.modifiers &&
                signature(it) == signature(method) }
        val name = method.simpleName.toString()
        if (hostMethod != null) {
            check(processingEnv.typeUtils.isSameType(method.returnType, hostMethod.returnType)) {
                "Host return type differs from WindowManagerPolicy.$name"
            }
            val args = method.parameters.indices.joinToString(", ") { "p$it" }
            return (if (method.returnType.kind == javax.lang.model.type.TypeKind.VOID) "" else "return ") +
                "host.$name($args);"
        }
        return "throw dev.artzig.ArtzigTestDiagnostics.unsupported(" +
            "\"WindowManagerPolicy\", \"$name\", \"not hosted\");"
    }
}
