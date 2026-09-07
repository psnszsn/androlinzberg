package dev.artzig.tools

import java.io.Writer
import javax.lang.model.element.ExecutableElement

/** Implements the hosted PM operations; remaining stock methods diagnose unsupported calls. */
class PackageManagerInternalProcessor : LocalServiceAdapterProcessor() {
    override val targetName = "android.content.pm.PackageManagerInternal"
    override val adapterName = "android.content.pm.HostedPackageManagerInternal"

    override fun writeFieldsAndConstructor(writer: Writer) {
        writer.appendLine("    private final dev.artzig.system.HostedPackageManagerService packages;")
        writer.appendLine("    public HostedPackageManagerInternal(dev.artzig.system.HostedPackageManagerService packages) {")
        writer.appendLine("        this.packages = packages;")
        writer.appendLine("    }")
    }

    override fun bodyFor(method: ExecutableElement): String {
        val name = method.simpleName.toString()
        val params = method.parameters.map { processingEnv.typeUtils.erasure(it.asType()).toString() }
        return when {
            name == "resolveIntent" && params == listOf("android.content.Intent", "java.lang.String", "long",
                "long", "int", "boolean", "int", "int") ->
                "return packages.resolveIntentInternal(p0, p1, p2, p3, p4, p5, p6, p7);"
            name == "getPackageList" && params == listOf("android.content.pm.PackageManagerInternal.PackageListObserver") ->
                "return packages.getPackageList(p0);"
            name == "removePackageListObserver" && params == listOf("android.content.pm.PackageManagerInternal.PackageListObserver") ->
                "packages.removePackageListObserver(p0);"
            name == "getSystemUiServiceComponent" && params.isEmpty() ->
                "return packages.getSystemUiServiceComponent();"
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
            name == "resolveContentProvider" && params == listOf("java.lang.String", "long", "int", "int") ->
                "return packages.resolveContentProviderForUid(p0, p1, p2, p3);"
            // The host currently has no package-visibility filtering (see the
            // filterAppAccess/canQueryPackage cases below). An implicit query
            // visibility grant adds no access under that policy; URI access
            // itself is enforced by the stock UriGrantsManagerService.
            name == "grantImplicitAccess" -> "return;"
            name == "getPackage" && params == listOf("java.lang.String") -> "return packages.getPackage(p0);"
            name == "getPackageStateInternal" && params == listOf("java.lang.String") -> "return packages.getPackageStateInternal(p0);"
            name == "isSystemPackage" -> "var state = packages.getPackageStateInternal(p0); return state != null && state.isSystem();"
            name == "isPackageSuspended" -> "var state = packages.getPackageStateInternal(p0); return state != null && state.getUserStateOrDefault(p1).isSuspended();"
            name == "getPackageList" -> "return packages.getPackageList(p0);"
            name == "removePackageListObserver" -> "packages.removePackageListObserver(p0);"
            name == "isInstantApp" -> "return false;" // No instant-app install path in this session.
            name == "setExternalSourcesPolicy" -> "packages.setExternalSourcesPolicy(p0);"
            name == "checkUidSignaturesForAllUsers" && params == listOf("int", "int") ->
                "return packages.checkUidSignatures(p0, p1);"
            name == "isUidPrivileged" && params == listOf("int") ->
                "return packages.isUidPrivileged(p0);"
            name == "snapshot" && params.isEmpty() -> "return packages;"
            name == "filterAppAccess" -> "return false;"
            name == "canQueryPackage" -> "return true;"
            else -> "throw dev.artzig.ArtzigTestDiagnostics.unsupported(" +
                "\"PackageManagerInternal\", \"$name\", \"not hosted\");"
        }
    }
}
