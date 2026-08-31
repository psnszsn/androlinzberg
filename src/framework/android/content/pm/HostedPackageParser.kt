package android.content.pm

import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.os.Bundle

/**
 * Parsed state needed to host one APK process without PackageManagerService.
 * All component names and process names are normalized before this leaves the
 * package module.
 */
class HostedPackage internal constructor(
    val packageName: String,
    val versionCode: Int,
    val versionName: String?,
    val applicationClassName: String?,
    val launcherActivityName: String?,
    val applicationTheme: Int,
    val activities: Map<String, ActivityInfo>,
    val providers: List<ProviderInfo>,
    val services: List<ServiceInfo>,
    val serviceActions: Map<String, Set<String>>,
    val requestedPermissions: Set<String>,
    val declaredPermissionProtectionLevels: Map<String, Int>,
) {
    fun providersForProcess(processName: String): List<ProviderInfo> =
        providers
            .asSequence()
            .filter { it.enabled && it.processName == processName }
            .sortedByDescending { it.initOrder }
            .map(::ProviderInfo)
            .toList()
}

/**
 * In-process adaptation of AOSP PackageParser component parsing.
 *
 * Source: PackageParser.parseBaseApk(), parseBaseApplication(),
 * parseUsesPermission(), parsePermission(), parseActivity(), parseProvider(),
 * and buildClassName() in the vendored AOSP tree. The complete PackageParser
 * cannot be compiled here because it depends on PackageManagerService parsing
 * state, split/package caches, and platform certificate infrastructure that do
 * not exist in the hosted process.
 */
object HostedPackageParser {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    @JvmStatic
    fun parse(assets: AssetManager): HostedPackage {
        val parser = assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            return parseManifest(parser)
        } finally {
            parser.close()
        }
    }

    /**
     * Parses the permission declarations from a package manifest.
     *
     * The host uses this with framework-res.apk to obtain the protection level
     * of platform permissions instead of maintaining a second permission table.
     */
    @JvmStatic
    fun parsePermissionDefinitions(assets: AssetManager): Map<String, Int> {
        val parser = assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            val permissions = linkedMapOf<String, Int>()
            while (true) {
                when (parser.next()) {
                    XmlResourceParser.END_DOCUMENT -> return permissions
                    XmlResourceParser.START_TAG -> if (parser.name == "permission") {
                        val name = attribute(parser, "name") ?: continue
                        permissions[name] = parser.getAttributeIntValue(
                            ANDROID_NS,
                            "protectionLevel",
                            PROTECTION_NORMAL,
                        )
                    }
                }
            }
        } finally {
            parser.close()
        }
    }

    /** AOSP PackageParser.buildClassName semantics. */
    @JvmStatic
    fun buildClassName(packageName: String, className: String?): String? {
        if (className.isNullOrEmpty()) return null
        return when {
            className[0] == '.' -> packageName + className
            '.' in className -> className
            else -> "$packageName.$className"
        }
    }

    private fun parseManifest(parser: XmlResourceParser): HostedPackage {
        var packageName: String? = null
        var versionCode = 0
        var versionName: String? = null
        var applicationClass: String? = null
        var applicationTheme = 0
        var launcherActivity: String? = null

        val activities = linkedMapOf<String, ActivityInfo>()
        val providers = mutableListOf<ProviderInfo>()
        val services = mutableListOf<ServiceInfo>()
        val serviceActions = linkedMapOf<String, Set<String>>()
        val requestedPermissions = linkedSetOf<String>()
        val declaredPermissionProtectionLevels = linkedMapOf<String, Int>()

        var currentActivity: ActivityInfo? = null
        var currentActivityTag: String? = null
        var currentAliasTarget: String? = null
        var activityHasMainAction = false
        var activityHasLauncherCategory = false
        var currentProvider: ProviderInfo? = null
        var currentService: ServiceInfo? = null
        var currentServiceActions: MutableSet<String>? = null

        while (true) {
            val event = parser.next()
            if (event == XmlResourceParser.END_DOCUMENT) break

            if (event == XmlResourceParser.END_TAG) {
                when (parser.name) {
                    currentActivityTag -> {
                        val activity = currentActivity
                        if (activity != null) {
                            activities[activity.name] = activity
                            if (activity.enabled && activityHasMainAction &&
                                activityHasLauncherCategory && launcherActivity == null
                            ) {
                                launcherActivity = activity.name
                            }
                        }
                        currentActivity = null
                        currentActivityTag = null
                        currentAliasTarget = null
                        activityHasMainAction = false
                        activityHasLauncherCategory = false
                    }
                    "provider" -> {
                        currentProvider?.let(providers::add)
                        currentProvider = null
                    }
                    "service" -> {
                        currentService?.let { service ->
                            services += service
                            serviceActions[service.name] = currentServiceActions?.toSet().orEmpty()
                        }
                        currentService = null
                        currentServiceActions = null
                    }
                }
                continue
            }
            if (event != XmlResourceParser.START_TAG) continue

            when (parser.name) {
                "manifest" -> {
                    packageName = parser.getAttributeValue(null, "package")
                    versionCode = parser.getAttributeIntValue(ANDROID_NS, "versionCode", 0)
                    versionName = attribute(parser, "versionName")
                }
                "application" -> {
                    val pkg = requirePackageName(packageName)
                    applicationClass = buildClassName(pkg, attribute(parser, "name"))
                    applicationTheme = parser.getAttributeResourceValue(ANDROID_NS, "theme", 0)
                }
                "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m" -> {
                    val name = attribute(parser, "name")
                    val maxSdkVersion = parser.getAttributeIntValue(
                        ANDROID_NS,
                        "maxSdkVersion",
                        0,
                    )
                    if (name != null &&
                        (maxSdkVersion == 0 || maxSdkVersion >= android.os.Build.VERSION.RESOURCES_SDK_INT)
                    ) {
                        requestedPermissions += name
                    }
                }
                "permission" -> {
                    val name = attribute(parser, "name")
                    if (name != null) {
                        declaredPermissionProtectionLevels[name] = parser.getAttributeIntValue(
                            ANDROID_NS,
                            "protectionLevel",
                            PROTECTION_NORMAL,
                        )
                    }
                }
                "activity" -> {
                    val pkg = requirePackageName(packageName)
                    currentActivity = ActivityInfo().apply {
                        name = requireNotNull(buildClassName(pkg, attribute(parser, "name"))) {
                            "activity without android:name"
                        }
                        this.packageName = pkg
                        processName = normalizeProcessName(pkg, attribute(parser, "process"))
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        exported = parser.getAttributeBooleanValue(ANDROID_NS, "exported", false)
                        directBootAware = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "directBootAware",
                            false,
                        )
                        theme = parser.getAttributeResourceValue(ANDROID_NS, "theme", 0)
                        configChanges = ActivityInfo.activityInfoConfigNativeToJava(
                            parser.getAttributeIntValue(ANDROID_NS, "configChanges", 0),
                        )
                        screenOrientation = parser.getAttributeIntValue(
                            ANDROID_NS,
                            "screenOrientation",
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        )
                        applicationInfo = applicationInfo(pkg, applicationTheme)
                    }
                    currentActivityTag = "activity"
                }
                "activity-alias" -> {
                    val pkg = requirePackageName(packageName)
                    currentAliasTarget = buildClassName(pkg, attribute(parser, "targetActivity"))
                    currentActivity = ActivityInfo().apply {
                        name = requireNotNull(buildClassName(pkg, attribute(parser, "name"))) {
                            "activity-alias without android:name"
                        }
                        this.packageName = pkg
                        targetActivity = currentAliasTarget
                        processName = pkg
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        exported = parser.getAttributeBooleanValue(ANDROID_NS, "exported", false)
                        applicationInfo = applicationInfo(pkg, applicationTheme)
                    }
                    currentActivityTag = "activity-alias"
                }
                "provider" -> {
                    val pkg = requirePackageName(packageName)
                    currentProvider = ProviderInfo().apply {
                        name = requireNotNull(buildClassName(pkg, attribute(parser, "name"))) {
                            "provider without android:name"
                        }
                        this.packageName = pkg
                        authority = attribute(parser, "authorities")
                        processName = normalizeProcessName(pkg, attribute(parser, "process"))
                        readPermission = attribute(parser, "readPermission")
                        writePermission = attribute(parser, "writePermission")
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        exported = parser.getAttributeBooleanValue(ANDROID_NS, "exported", false)
                        grantUriPermissions = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "grantUriPermissions",
                            false,
                        )
                        multiprocess = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "multiprocess",
                            false,
                        )
                        directBootAware = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "directBootAware",
                            false,
                        )
                        initOrder = parser.getAttributeIntValue(ANDROID_NS, "initOrder", 0)
                        metaData = Bundle()
                        applicationInfo = applicationInfo(pkg, applicationTheme)
                    }
                }
                "service" -> {
                    val pkg = requirePackageName(packageName)
                    currentService = ServiceInfo().apply {
                        name = requireNotNull(buildClassName(pkg, attribute(parser, "name"))) {
                            "service without android:name"
                        }
                        this.packageName = pkg
                        processName = normalizeProcessName(pkg, attribute(parser, "process"))
                        permission = attribute(parser, "permission")
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        exported = parser.getAttributeBooleanValue(ANDROID_NS, "exported", false)
                        directBootAware = parser.getAttributeBooleanValue(
                            ANDROID_NS,
                            "directBootAware",
                            false,
                        )
                        mForegroundServiceType = parser.getAttributeIntValue(
                            ANDROID_NS,
                            "foregroundServiceType",
                            0,
                        )
                        metaData = Bundle()
                        applicationInfo = applicationInfo(pkg, applicationTheme)
                    }
                    currentServiceActions = linkedSetOf()
                }
                "meta-data" -> {
                    val metadata = currentProvider?.metaData ?: currentService?.metaData
                    if (metadata != null) parseMetaData(parser, metadata)
                }
                "action" -> {
                    val action = attribute(parser, "name")
                    if (currentService != null) {
                        if (action != null) currentServiceActions?.add(action)
                    } else if (currentActivity != null && action == "android.intent.action.MAIN") {
                        activityHasMainAction = true
                    }
                }
                "category" -> {
                    if (currentActivity != null &&
                        attribute(parser, "name") == "android.intent.category.LAUNCHER"
                    ) {
                        activityHasLauncherCategory = true
                    }
                }
            }
        }

        val pkg = requirePackageName(packageName)
        val resolvedActivities = activities.mapValues { (_, info) ->
            val target = info.targetActivity?.let(activities::get)
            if (target == null) {
                info
            } else {
                ActivityInfo(target).apply {
                    name = info.name
                    targetActivity = info.targetActivity
                    enabled = info.enabled
                    exported = info.exported
                }
            }
        }
        return HostedPackage(
            packageName = pkg,
            versionCode = versionCode,
            versionName = versionName,
            applicationClassName = applicationClass,
            launcherActivityName = launcherActivity,
            applicationTheme = applicationTheme,
            activities = resolvedActivities,
            providers = providers.toList(),
            services = services.toList(),
            serviceActions = serviceActions.toMap(),
            requestedPermissions = requestedPermissions.toSet(),
            declaredPermissionProtectionLevels = declaredPermissionProtectionLevels.toMap(),
        )
    }

    private fun parseMetaData(parser: XmlResourceParser, metadata: Bundle) {
        val name = attribute(parser, "name") ?: return
        val resource = parser.getAttributeResourceValue(ANDROID_NS, "resource", 0)
        if (resource != 0) {
            metadata.putInt(name, resource)
            return
        }
        val value = attribute(parser, "value") ?: return
        when {
            value.equals("true", ignoreCase = true) -> metadata.putBoolean(name, true)
            value.equals("false", ignoreCase = true) -> metadata.putBoolean(name, false)
            else -> metadata.putString(name, value)
        }
    }

    private fun applicationInfo(packageName: String, applicationTheme: Int) = ApplicationInfo().apply {
        this.packageName = packageName
        theme = applicationTheme
    }

    private fun normalizeProcessName(packageName: String, processName: String?): String = when {
        processName.isNullOrEmpty() -> packageName
        processName[0] == ':' -> packageName + processName
        else -> processName
    }

    private fun requirePackageName(packageName: String?): String =
        requireNotNull(packageName) { "manifest has no package name" }

    private fun attribute(parser: XmlResourceParser, name: String): String? =
        parser.getAttributeValue(ANDROID_NS, name) ?: parser.getAttributeValue(null, name)

    // PermissionInfo is not in the hosted source closure yet. These are the
    // stable AOSP PermissionInfo protection-level values used by the manifest.
    internal const val PROTECTION_NORMAL: Int = 0
    internal const val PROTECTION_MASK_BASE: Int = 0x0f
}
