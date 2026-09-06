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

/** Renders adapters from javac symbols so every stock abstract method has its exact signature. */
abstract class LocalServiceAdapterProcessor : AbstractProcessor() {
    protected abstract val targetName: String
    protected abstract val adapterName: String
    protected open val isFinal: Boolean = true
    protected abstract fun writeFieldsAndConstructor(writer: Writer)
    private var generated = false

    override fun getSupportedAnnotationTypes(): MutableSet<String> = mutableSetOf("*")

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.RELEASE_21

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnv: RoundEnvironment,
    ): Boolean {
        if (generated) return false
        val target = processingEnv.elementUtils
            .getTypeElement(targetName) ?: return false
        val methods = processingEnv.elementUtils.getAllMembers(target)
            .filterIsInstance<ExecutableElement>()
            .filter { Modifier.ABSTRACT in it.modifiers }
            .distinctBy(::erasedSignature)
            .sortedWith(compareBy({ it.simpleName.toString() }, ::erasedSignature))

        processingEnv.filer.createSourceFile(
            adapterName,
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
        writer.appendLine("// Generated from the stock $targetName abstract API.")
        writer.appendLine("package ${adapterName.substringBeforeLast('.')};")
        val modifier = if (isFinal) "final " else ""
        writer.appendLine("public ${modifier}class ${adapterName.substringAfterLast('.')} extends $targetName {")
        writeFieldsAndConstructor(writer)
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

    protected abstract fun bodyFor(method: ExecutableElement): String
}
