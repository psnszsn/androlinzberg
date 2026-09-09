package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement

/** Generates the in-memory wallpaper test collaborator against the stock local API. */
class WindowManagerInternalProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "com.android.server.wm.WindowManagerInternal"
    override val adapterName = "com.android.server.wm.HostedTestWindowManagerInternal"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final java.util.function.IntPredicate focus;")
        writer.appendLine("    public HostedTestWindowManagerInternal(java.util.function.IntPredicate focus) {")
        writer.appendLine("        this.focus = java.util.Objects.requireNonNull(focus);")
        writer.appendLine("    }")
    }

    override fun bodyFor(method: ExecutableElement): String {
        val name = method.simpleName.toString()
        val params = method.parameters.map { processingEnv.typeUtils.erasure(it.asType()).toString() }
        return when {
            name == "isUidFocused" && params == listOf("int") -> "return focus.test(p0);"
            name == "getTopFocusedDisplayId" && params.isEmpty() -> "return android.view.Display.DEFAULT_DISPLAY;"
            name == "isHomeSupportedOnDisplay" && params == listOf("int") -> "return p0 == android.view.Display.DEFAULT_DISPLAY;"
            else -> "throw dev.artzig.ArtzigTestDiagnostics.unsupported(" +
                "\"WindowManagerInternal\", \"$name\", \"the in-memory window test adapter does not provide this operation\");"
        }
    }
}
