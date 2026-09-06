package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement

/** Exact stock AM local-interface signatures; hosted behavior remains in Kotlin. */
class ActivityManagerInternalProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "android.app.ActivityManagerInternal"
    override val adapterName = "dev.artzig.system.HostedActivityManagerInternal"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final HostedActivityManagerInternalService service;")
        writer.appendLine("    public HostedActivityManagerInternal(HostedActivityManagerInternalService service) {")
        writer.appendLine("        this.service = service;")
        writer.appendLine("    }")
    }

    override fun bodyFor(method: ExecutableElement): String {
        val params = method.parameters.map { processingEnv.typeUtils.erasure(it.asType()).toString() }
        return when {
            method.simpleName.toString() == "isSystemReady" && params.isEmpty() ->
                "return service.getSystemReady();"
            method.simpleName.toString() == "getUidProcessState" && params == listOf("int") ->
                "return service.getUidProcessState(p0);"
            method.simpleName.toString() == "handleIncomingUser" && params ==
                listOf("int", "int", "int", "boolean", "int", "java.lang.String", "java.lang.String") ->
                "return service.handleIncomingUser(p0, p1, p2, p3, p4, p5, p6);"
            method.simpleName.toString() == "enforceCallingPermission" && params ==
                listOf("java.lang.String", "java.lang.String") ->
                "service.enforceCallingPermission(p0, p1);"
            method.simpleName.toString() == "checkContentProviderUriPermission" && params ==
                listOf("android.net.Uri", "int", "int", "int") ->
                "return service.checkContentProviderUriPermission(p0, p1, p2, p3);"
            else -> "throw dev.artzig.ArtzigTestDiagnostics.unsupported(" +
                "\"ActivityManagerInternal\", \"${method.simpleName}\", \"not hosted\");"
        }
    }
}
