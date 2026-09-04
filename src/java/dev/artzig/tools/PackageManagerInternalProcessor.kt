package dev.artzig.tools

import java.io.Writer
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.TypeMirror

/** Generates the exhaustive local-service adapter for AOSP's large abstract PM API. */
class PackageManagerInternalProcessor : AbstractProcessor() {
    private var generated = false

    override fun getSupportedAnnotationTypes(): MutableSet<String> = mutableSetOf("*")

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.RELEASE_21

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnv: RoundEnvironment,
    ): Boolean {
        if (generated) return false
        val target = processingEnv.elementUtils
            .getTypeElement("android.content.pm.PackageManagerInternal") ?: return false
        val methods = processingEnv.elementUtils.getAllMembers(target)
            .filterIsInstance<ExecutableElement>()
            .filter { Modifier.ABSTRACT in it.modifiers }
            .distinctBy(::erasedSignature)
            .sortedWith(compareBy({ it.simpleName.toString() }, ::erasedSignature))

        processingEnv.filer.createSourceFile(
            "android.content.pm.HostedPackageManagerInternal",
            target,
        ).openWriter().use { writer -> writeClass(writer, methods) }
        generated = true
        return false
    }

    private fun erasedSignature(method: ExecutableElement): String = buildString {
        append(method.simpleName)
        append('(')
        method.parameters.joinTo(this, ",") {
            processingEnv.typeUtils.erasure(it.asType()).toString()
        }
        append(')')
    }

    private fun writeClass(writer: Writer, methods: List<ExecutableElement>) {
        writer.appendLine("// Generated from the stock PackageManagerInternal abstract API.")
        writer.appendLine("package android.content.pm;")
        writer.appendLine()
        writer.appendLine("public final class HostedPackageManagerInternal extends PackageManagerInternal {")
        writer.appendLine("    private final dev.artzig.system.HostedPackageManagerService packages;")
        writer.appendLine()
        writer.appendLine("    public HostedPackageManagerInternal(")
        writer.appendLine("            dev.artzig.system.HostedPackageManagerService packages) {")
        writer.appendLine("        this.packages = packages;")
        writer.appendLine("    }")
        for (method in methods) {
            writer.appendLine()
            writeMethod(writer, method)
        }
        writer.appendLine("}")
    }

    private fun writeMethod(writer: Writer, method: ExecutableElement) {
        writer.append("    @Override\n    public ")
        writeTypeParameters(writer, method.typeParameters)
        writer.append(method.returnType.toString())
        writer.append(' ')
        writer.append(method.simpleName.toString())
        writer.append('(')
        method.parameters.forEachIndexed { index, parameter ->
            if (index != 0) writer.append(", ")
            val type = if (method.isVarArgs && index == method.parameters.lastIndex) {
                (parameter.asType() as ArrayType).componentType.toString() + "..."
            } else {
                parameter.asType().toString()
            }
            writer.append(type).append(" p").append(index.toString())
        }
        writer.append(')')
        if (method.thrownTypes.isNotEmpty()) {
            writer.append(" throws ")
            method.thrownTypes.forEachIndexed { index, type ->
                if (index != 0) writer.append(", ")
                writer.append(type.toString())
            }
        }
        writer.appendLine(" {")
        writer.append("        ").appendLine(bodyFor(method))
        writer.appendLine("    }")
    }

    private fun writeTypeParameters(writer: Writer, parameters: List<TypeParameterElement>) {
        if (parameters.isEmpty()) return
        writer.append('<')
        parameters.forEachIndexed { index, parameter ->
            if (index != 0) writer.append(", ")
            writer.append(parameter.simpleName.toString())
            val bounds = parameter.bounds.filter { it.toString() != "java.lang.Object" }
            if (bounds.isNotEmpty()) {
                writer.append(" extends ")
                bounds.forEachIndexed { boundIndex, bound ->
                    if (boundIndex != 0) writer.append(" & ")
                    writer.append(bound.toString())
                }
            }
        }
        writer.append("> ")
    }

    private fun bodyFor(method: ExecutableElement): String {
        val name = method.simpleName.toString()
        val params = method.parameters.map { processingEnv.typeUtils.erasure(it.asType()).toString() }
        return when {
            name == "isSameApp" && params == listOf("java.lang.String", "int", "int") ->
                "return packages.isSameAppInternal(p0, p1);"
            name == "isSameApp" && params == listOf("java.lang.String", "long", "int", "int") ->
                "return packages.isSameAppInternal(p0, p2);"
            name == "getPackageUid" && params == listOf("java.lang.String", "long", "int") ->
                "return packages.getPackageUid(p0, p1, p2);"
            name == "getPackageInfo" && params == listOf("java.lang.String", "long", "int", "int") ->
                "return packages.getPackageInfo(p0, p1, p3);"
            name == "getApplicationInfo" && params == listOf("java.lang.String", "long", "int", "int") ->
                "return packages.getApplicationInfo(p0, p1, p3);"
            name == "getNameForUid" && params == listOf("int") ->
                "return packages.getNameForUid(p0);"
            name == "snapshot" && params.isEmpty() -> "return packages;"
            name == "filterAppAccess" -> "return false;"
            name == "canQueryPackage" -> "return true;"
            else -> "throw android.util.ArtzigTestDiagnostics.unsupported(" +
                "\"PackageManagerInternal\", \"$name\", \"not hosted\");"
        }
    }
}
