@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SigningDetails
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.android.internal.pm.parsing.PackageParser2
import com.android.internal.pm.parsing.pkg.AndroidPackageInternal
import com.android.internal.pm.parsing.pkg.PackageImpl
import com.android.internal.pm.pkg.component.ParsedPermission
import com.android.internal.pm.pkg.component.ParsedPermissionGroup
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils
import android.content.pm.parsing.result.ParseTypeImpl
import com.android.server.pm.Computer
import com.android.server.pm.UserManagerService
import com.android.server.pm.UserNeedsBadgingCache
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.pm.resolution.ComponentResolver
import java.io.File

/**
 * The session-wide package manager on the real `IPackageManager` surface:
 * APKs are parsed only here (AOSP `PackageParser2` -> `ParsedPackage` ->
 * `AndroidPackageInternal`), components resolve through the real
 * `ComponentResolver`/`IntentResolver`, and every Info object comes from
 * `PackageInfoUtils` over [HostedPackageState].
 *
 * Source note: follows `com.android.server.pm.PackageManagerService` /
 * `ComputerEngine` (install -> `addAllComponents`, `queryIntentActivitiesInternal`,
 * `getPackageInfo`) and `PermissionManagerServiceImpl` (install-time grant of
 * normal protection, signature grant on SigningDetails match) — the real
 * classes are inseparable from the full system_server graph (spike result).
 * Anything not overridden here fails loudly through
 * [PackageManagerServiceBase].
 */
class HostedPackageManagerService : PackageManagerServiceBase(), Computer {
    private val lock = Any()
    private val packageMonitors = com.android.server.pm.PackageMonitorCallbackHelper()

    // PMS.registerPackageMonitorCallback and BroadcastHelper's post-mutation
    // notifications. The stock helper owns Binder death, user filtering, and
    // the PackageMonitor callback Bundle protocol.
    override fun registerPackageMonitorCallback(callback: android.os.IRemoteCallback?, userId: Int) {
        val uid = Binder.getCallingUid()
        val target = android.app.ActivityManager.handleIncomingUser(Binder.getCallingPid(), uid,
            userId, true, true, "registerPackageMonitorCallback", "android")
        packageMonitors.registerPackageMonitorCallback(callback, target, uid)
    }

    override fun unregisterPackageMonitorCallback(callback: android.os.IRemoteCallback?) {
        packageMonitors.unregisterPackageMonitorCallback(callback)
    }

    private fun notifyPackageChanged(packageName: String, components: ArrayList<String>, flags: Int, userId: Int) {
        val uid = synchronized(lock) { packages[packageName]?.appId } ?: return
        packageMonitors.notifyPackageChanged(packageName,
            flags and PackageManager.DONT_KILL_APP != 0, components, uid, null,
            intArrayOf(userId), intArrayOf(), null, com.android.internal.os.BackgroundThread.getHandler())
    }
    private val packages = LinkedHashMap<String, HostedPackageState>()
    /** Broker uid -> package state, bound at process attach. */
    private val packagesByUid = HashMap<Int, HostedPackageState>()
    private val componentResolver =
        ComponentResolver(UserManagerService.getInstance(), UserNeedsBadgingCache(UserManagerService.getInstance()))
    /** Permission name -> (definition, defining package); first definition wins. */
    private class PermissionDef(val permission: ParsedPermission, val owner: HostedPackageState)
    private val permissionDefs = HashMap<String, PermissionDef>()
    private val permissionGroups = HashMap<String, ParsedPermissionGroup>()

    // ------------------------------------------------------------- install

    private val parserCallback = object : PackageParser2.Callback() {
        /**
         * The no-platform-compat fallback (source note:
         * ParseTypeImpl.forDefaultParsing): a deferred parse error becomes
         * fatal only when the package's target SDK is past the SDK the change
         * is gated on. Returning true unconditionally would reject otherwise
         * valid APKs (e.g. an API-30 target with an implicit intent-filter
         * and no android:exported trips MISSING_EXPORTED_FLAG, gated at R).
         */
        override fun isChangeEnabled(changeId: Long, appInfo: ApplicationInfo): Boolean {
            val gate = android.content.pm.parsing.result.ParseInput.DeferredError
                .getTargetSdkForChange(changeId)
            return gate != -1 && appInfo.targetSdkVersion > gate
        }
        override fun hasFeature(feature: String?): Boolean = false
        override fun getHiddenApiWhitelistedApps(): Set<String> = emptySet()
        override fun getInstallConstraintsAllowlist(): Set<String> = emptySet()
    }

    /**
     * Parses and installs an APK; idempotent per package name (a re-install
     * from the same path returns the existing record). The parse happens here,
     * so a caller can only feed the service a manifest its APK carries, and a
     * non-system caller is bound to the package it installs (one package per
     * broker uid — the same interim identity gate as attach; per-connection
     * package authentication is the standing follow-up). A system caller may
     * install any number (respawn, platform).
     */
    fun installPackage(apkPath: String, nativeLibraryDir: String? = null): String =
        installPackageInternal(apkPath, nativeLibraryDir, false)

    /** PMS system-image scan: only boot code selects these packaged, signed APKs.
     * Privileged package status does not assign the system UID; the broker assigns
     * each package its own UID when its process attaches. */
    internal fun installSystemPackage(apkPath: String): String {
        check(Binder.getCallingUid() == Process.SYSTEM_UID || Binder.getCallingUid() == Process.ROOT_UID)
        return installPackageInternal(apkPath, null, true)
    }

    private fun installPackageInternal(apkPath: String, nativeLibraryDir: String?, system: Boolean): String {
        val callingUid = Binder.getCallingUid()
        val file = File(apkPath)
        require(file.isFile) { "no APK at $apkPath" }
        // Parse outside the lock (I/O + signature verification).
        val parsed = PackageParser2(null, null, null, parserCallback).use { parser ->
            parser.parsePackage(file, 0, false)
        }
        val signing = ParsingPackageUtils.getSigningDetails(
            ParseTypeImpl.forDefaultParsing().reset(), parsed, false,
        )
        if (signing.isError) {
            throw SecurityException("cannot verify " + apkPath + ": " + signing.errorMessage)
        }
        parsed.setSigningDetails(signing.result)
        if (system) parsed.setSystem(true).setPrivileged(true)
        // Host seam (source note: installd creates the app data directories at
        // install; ContextImpl only creates subdirectories underneath). The
        // framework rides the app classpath here rather than the boot
        // classpath, so an APK's bundled implementation libraries must win
        // over the host's copies: packages load through HostedApkClassLoader unless the
        // manifest chose a class loader (ClassLoaderFactory knows the name).
        if (parsed.classLoaderName == null) {
            (parsed as com.android.internal.pm.parsing.pkg.PackageImpl).setClassLoaderName("dev.artzig.HostedApkClassLoader")
        }
        // PackageAbiHelperImpl derives a non-null library search directory even
        // for an APK without JNI libraries. App installs supply the launcher's
        // extraction directory; image APKs use the bundle's per-package location.
        // LoadedApk passes this entry to DexPathList.addNativePath.
        (parsed as com.android.internal.pm.parsing.pkg.PackageImpl).setNativeLibraryDir(
            nativeLibraryDir ?: File(file.parentFile, "lib/${parsed.packageName}").absolutePath)
        val pkg = parsed.hideAsFinal()
        createDataDirectories(pkg as PackageImpl, system)
        val app = callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID
        var alreadyInstalled = false
        var newlyBound = false
        synchronized(lock) {
            val existing = packages[pkg.packageName]
            if (existing != null) {
                if (existing.pathString != apkPath) {
                    throw SecurityException(pkg.packageName + " is already installed from " + existing.pathString)
                }
                alreadyInstalled = true
                newlyBound = app && existing.appId == -1
                if (app) bindPackageUidLocked(pkg.packageName, callingUid)
            } else {
                // Reject a second package before registering anything for this UID.
                if (app) {
                    val owned = packagesByUid[callingUid]
                    if (owned != null) throw SecurityException(
                        "uid " + callingUid + " already owns " + owned.packageName + " and may not install " + pkg.packageName)
                }
                installLocked(pkg, apkPath, system = system)
                if (app) bindPackageUidLocked(pkg.packageName, callingUid)
                newlyBound = app
            }
        }
        invalidateClientCaches()
        val uid = synchronized(lock) { packages.getValue(pkg.packageName).appId }
        if (newlyBound) notifyPackageUidBound(pkg.packageName, uid)
        if (alreadyInstalled) return pkg.packageName
        Log.i(TAG, "installed " + pkg.packageName + " from " + apkPath)
        packageMonitors.notifyPackageAddedForNewUsers(pkg.packageName, uid, intArrayOf(0),
            intArrayOf(), false, 0, null, com.android.internal.os.BackgroundThread.getHandler())
        return pkg.packageName
    }

    /** Installs framework-res.apk as package "android" (boot, system process). */
    fun installPlatform(frameworkResPath: String) {
        val parsed = PackageParser2(null, null, null, parserCallback).use { parser ->
            parser.parsePackage(File(frameworkResPath), 0, false)
        }
        val signing = ParsingPackageUtils.getSigningDetails(
            ParseTypeImpl.forDefaultParsing().reset(), parsed, false,
        )
        if (signing.isError) {
            throw IllegalStateException("cannot verify framework-res: " + signing.errorMessage)
        }
        parsed.setSigningDetails(signing.result)
        // PackageInfoUtils reads FLAG_SYSTEM / PRIVATE_FLAG_PRIVILEGED from the
        // parsed package (AndroidPackageLegacyUtils.isSystem/isPrivileged), not
        // from PackageStateInternal, so the scan-derived flags must be set on
        // the package itself before it is frozen (AOSP sets them during scan).
        parsed.setSystem(true).setPrivileged(true)
        (parsed as PackageImpl).addActivity(HostedPermissionController.activity())
        val pkg = parsed.hideAsFinal()
        check(pkg.packageName == "android") { "framework-res parsed as " + pkg.packageName }
        synchronized(lock) {
            installLocked(pkg, frameworkResPath, system = true)
            packagesByUid[Process.SYSTEM_UID] = packages.getValue("android")
        }
        invalidateClientCaches()
        Log.i(
            TAG,
            "platform installed: " + permissionDefs.size + " permissions, " +
                permissionGroups.size + " groups",
        )
    }

    /** Pinned PMS.getRequiredSdkSandboxPackageName resolves the system service component.
     * A full Android image requires exactly one; the hosted image has no SDK sandbox module.
     * Report its absence here so stock activity-intent classification does not invent an
     * installed sandbox package. Starting a sandbox still requires that unavailable module. */
    override fun getSdkSandboxPackageName(): String? {
        val matches = queryServicesInternal(Intent("com.android.sdksandbox.SdkSandboxService"), null,
            PackageManager.MATCH_SYSTEM_ONLY.toLong() or PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong(), android.os.UserHandle.USER_SYSTEM)
        check(matches.size <= 1) { "More than one system SDK sandbox service: $matches" }
        return matches.singleOrNull()?.serviceInfo?.packageName
    }

    override fun getPermissionControllerPackageName(): String = synchronized(lock) {
        check(packages.containsKey(HostedPermissionController.PACKAGE)) {
            "PermissionController is not found"
        }
        HostedPermissionController.PACKAGE
    }

    /** PackageImpl.assignDerivedFields2 supplies the prefixes used by every
     * PackageInfoUtils application/component query. System APK databases contain
     * session-assigned UIDs, so their data must have the same lifetime as those
     * identities. Set the parsed package's two derived prefixes at this host
     * install seam; ordinary app data keeps Environment's persistent layout.
     * AOSP installd and durable package UID allocation are absent here. */
    private fun createDataDirectories(pkg: PackageImpl, system: Boolean) {
        if (system) {
            val root = File(checkNotNull(System.getenv("AZK_BINDER_SESSION_DIR")) {
                "System APK installation requires a Binder session"
            }, "service-app-data")
            for ((fieldName, directory) in listOf(
                "mBaseAppDataCredentialProtectedDirForSystemUser" to "user",
                "mBaseAppDataDeviceProtectedDirForSystemUser" to "user_de",
            )) {
                val prefix = File(root, "$directory/0").absolutePath + File.separator
                PackageImpl::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                    .set(pkg, prefix)
            }
        }
        for (prefix in listOf(pkg.baseAppDataCredentialProtectedDirForSystemUser,
            pkg.baseAppDataDeviceProtectedDirForSystemUser)) {
            val dir = File(prefix, pkg.packageName)
            check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "cannot create $dir" }
        }
    }

    private fun installLocked(pkg: AndroidPackageInternal, apkPath: String, system: Boolean) {
        // Register this package's permission definitions before computing its
        // own grants (a package may request a permission it declares).
        val state = HostedPackageState(
            pkg, apkPath, system,
            pkg.usesPermissions.mapTo(LinkedHashSet()) { it.name },
            LinkedHashSet(),
        )
        for (permission in pkg.permissions) {
            val existing = permissionDefs[permission.name]
            if (existing != null && existing.owner.packageName != pkg.packageName) {
                Log.w(
                    TAG,
                    pkg.packageName + " may not redefine " + permission.name +
                        " owned by " + existing.owner.packageName,
                )
                continue
            }
            permissionDefs[permission.name] = PermissionDef(permission, state)
        }
        for (group in pkg.permissionGroups) {
            permissionGroups.putIfAbsent(group.name, group)
        }
        state.grantedPermissions.addAll(computeInstallGrantsLocked(state))
        state.userState.setInstalled(true)
        state.userState.setFirstInstallTimeMillis(System.currentTimeMillis())
        packages[pkg.packageName] = state
        // ScanPackageUtils.applyPolicy marks packages whose certificates match
        // the platform. Recompute when the platform arrives after an app too.
        val platformSigning = packages["android"]?.signingDetails
        for (installed in packages.values) {
            (installed.pkg as PackageImpl).setSignedWithPlatformKey(
                installed.packageName == "android" ||
                    (platformSigning != null && platformSigning.signaturesMatchExactly(installed.signingDetails)))
        }
        componentResolver.addAllComponents(pkg, false, null, this)
        // A package installed before its permission's definer was denied that
        // permission at its own install (the definition did not exist yet).
        // Now that this package may contribute new definitions, reconsider
        // existing requesters so grants no longer depend on install order.
        // Install grants only ever add, so a union is safe (it cannot resurrect
        // a runtime-revoked dangerous permission — those are never install-granted).
        val newlyDefined = pkg.permissions.mapTo(HashSet()) { it.name }
        if (newlyDefined.isNotEmpty()) {
            for (other in packages.values) {
                if (other === state) continue
                if (other.requestedPermissions.none { it in newlyDefined }) continue
                other.grantedPermissions.addAll(computeInstallGrantsLocked(other))
            }
        }
    }

    /**
     * Install-time grant (source note: `PermissionManagerServiceImpl`
     * `grantRequestedPermissionsInternal`/`shouldGrantSignaturePermission`,
     * reduced): normal protection is granted outright; signature protection
     * is granted on a SigningDetails match with the defining package (the
     * platform's definitions match packages signed with the azk-platform
     * key). Dangerous protection is never granted at install — it is runtime
     * state, mutable through [grantRuntimePermission].
     */
    private fun computeInstallGrantsLocked(state: HostedPackageState): List<String> {
        val granted = ArrayList<String>()
        for (name in state.requestedPermissions) {
            val def = permissionDefs[name] ?: continue
            when (def.permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
                PermissionInfo.PROTECTION_NORMAL -> granted.add(name)
                PermissionInfo.PROTECTION_SIGNATURE -> {
                    if (state.packageName == def.owner.packageName ||
                        state.signingDetails.hasCommonSignerWithCapability(
                            def.owner.signingDetails,
                            SigningDetails.CertCapabilities.PERMISSION,
                        )
                    ) {
                        granted.add(name)
                    }
                }
            }
        }
        return granted
    }

    // ---------------------------------------------- identity (uid binding)

    /**
     * Binds a package to the broker uid of its first attaching process
     * (called by the activity manager's attach path). The broker's uid map
     * is stable per package for the session, so a second process of the same
     * package carries the same uid.
     */
    fun bindPackageUid(packageName: String, uid: Int) {
        if (uid == Process.SYSTEM_UID) return
        val newlyBound = synchronized(lock) {
            val fresh = packages[packageName]?.appId == -1
            bindPackageUidLocked(packageName, uid)
            fresh
        }
        invalidateClientCaches()
        if (newlyBound) notifyPackageUidBound(packageName, uid)
    }

    /** PMS notifies AppOps once a scanned package has a real UID, outside the PM lock. */
    private fun notifyPackageUidBound(packageName: String, uid: Int) {
        com.android.server.LocalServices.getService(android.app.AppOpsManagerInternal::class.java)
            ?.onPackageAdded(packageName, uid)
        for (observer in packageObservers) observer.onPackageAdded(packageName, uid)
    }

    /** Requires `lock`; caller invalidates caches once outside it. */
    private fun bindPackageUidLocked(packageName: String, uid: Int) {
        val state = packages[packageName]
            ?: throw SecurityException("cannot attach unknown package $packageName")
        if (state.appId != -1 && state.appId != uid) {
            throw SecurityException(
                "package " + packageName + " is uid " + state.appId + ", not " + uid,
            )
        }
        val holder = packagesByUid[uid]
        if (holder != null && holder !== state) {
            throw SecurityException(
                "uid " + uid + " already owns " + holder.packageName +
                    " and may not attach as " + packageName,
            )
        }
        state.appId = uid
        packagesByUid[uid] = state
        // PackageInfoUtils reads ApplicationInfo.uid from the parsed package's
        // appId, not from PackageStateInternal; keep them in sync so generated
        // info reports the broker uid (single user 0, so appId == uid).
        (state.pkg as PackageImpl).setUid(uid)
    }

    // ------------------------------------------------- Computer (resolver)

    override fun getComponentResolver(): com.android.server.pm.resolution.ComponentResolverApi = componentResolver

    override fun getPackage(packageName: String): AndroidPackage? =
        synchronized(lock) { packages[packageName]?.pkg }

    override fun getPackageStateInternal(packageName: String): PackageStateInternal? =
        synchronized(lock) { packages[packageName] }

    /** Capture the mutable hosted registry fields under its lock. Parsed manifest
     * collections are frozen after install; only UID/platform-signing facts change. */
    internal fun packageStateSnapshot(): Map<String, com.android.server.pm.pkg.PackageState> = synchronized(lock) {
        java.util.Collections.unmodifiableMap(packages.mapValues { (_, state) ->
            val uid = state.appId
            val user = state.userState.snapshot()
            val manifestUid = state.pkg.uid
            val signedWithPlatform = state.pkg.isSignedWithPlatformKey
            val manifest = object : AndroidPackage by state.pkg {
                override fun getUid(): Int = manifestUid
                override fun isSignedWithPlatformKey(): Boolean = signedWithPlatform
            }
            object : com.android.server.pm.pkg.PackageState by state {
                override fun getAppId(): Int = uid
                override fun getAndroidPackage(): AndroidPackage = manifest
                override fun getStateForUser(handle: android.os.UserHandle): com.android.server.pm.pkg.PackageUserState =
                    if (handle.identifier == 0) user else com.android.server.pm.pkg.PackageUserState.DEFAULT
            }
        })
    }

    private val packageObservers = java.util.concurrent.CopyOnWriteArrayList<android.content.pm.PackageManagerInternal.PackageListObserver>()
    fun getPackageListInternal(observer: android.content.pm.PackageManagerInternal.PackageListObserver?): com.android.server.pm.PackageList {
        val result = synchronized(lock) { com.android.server.pm.PackageList(packages.keys.toList(), observer) }
        if (observer != null) packageObservers.add(result)
        return result
    }
    fun removePackageListObserverInternal(observer: android.content.pm.PackageManagerInternal.PackageListObserver) {
        packageObservers.remove(observer)
    }

    @Volatile var externalSourcesPolicy: android.content.pm.PackageManagerInternal.ExternalSourcesPolicy? = null

    // -------------------------------------------------- internal (in-process)

    fun getApkPathInternal(packageName: String): String? =
        synchronized(lock) { packages[packageName]?.pathString }

    fun getPackageUidInternal(packageName: String): Int =
        synchronized(lock) { packages[packageName]?.appId ?: -1 }

    /** ActivityClientController's internal-caller check uses the parsed platform-signature flag. */
    internal fun isPlatformSignedUid(uid: Int): Boolean = synchronized(lock) {
        packagesByUid[uid]?.pkg?.isSignedWithPlatformKey == true
    }

    /** Hosted identity map used by PackageManagerInternal.isSameApp. */
    fun isSameAppInternal(packageName: String?, uid: Int): Boolean {
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return true
        return getPackagesForUid(uid)?.contains(packageName) == true
    }

    /** First match for a service intent (activity manager's bind/start path). */
    fun resolveServiceInfo(intent: Intent): ServiceInfo? =
        resolveService(intent, intent.type, 0L, 0)?.serviceInfo

    // ----------------------------------------------------- IPackageManager

    /** The single hosted user is always running and unlocked, so every query
     * matches both direct-boot states (source note:
     * ComputerEngine.updateFlagsForResolve / updateFlagsForComponent). */
    private fun withDirectBoot(flags: Long): Long =
        flags or PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()

    override fun getPackageInfo(packageName: String?, flags: Long, userId: Int): PackageInfo? {
        val name = packageName ?: return null
        synchronized(lock) {
            val state = packages[name] ?: return null
            return PackageInfoUtils.generate(
                state.pkg, gidsFor(state), withDirectBoot(flags), 0, 0,
                state.requestedPermissions, state.grantedPermissions,
                state.userState, userId, state,
            )
        }
    }

    override fun getApplicationInfo(packageName: String?, flags: Long, userId: Int): ApplicationInfo? {
        val name = packageName ?: return null
        synchronized(lock) {
            val state = packages[name] ?: return null
            return PackageInfoUtils.generateApplicationInfo(
                state.pkg, withDirectBoot(flags), state.userState, userId, state,
            )
        }
    }

    override fun getTargetSdkVersion(packageName: String?): Int = synchronized(lock) {
        packages[packageName]?.pkg?.targetSdkVersion ?: -1
    }

    override fun isPackageAvailable(packageName: String?, userId: Int): Boolean =
        synchronized(lock) { packages.containsKey(packageName) }

    override fun getAllPackages(): MutableList<String> =
        synchronized(lock) { packages.keys.toMutableList() }

    override fun getInstalledPackages(flags: Long, userId: Int): ParceledListSlice<*> {
        val result = ArrayList<PackageInfo>()
        synchronized(lock) {
            for (state in packages.values) {
                result.add(
                    PackageInfoUtils.generate(
                        state.pkg, gidsFor(state), withDirectBoot(flags), 0, 0,
                        state.requestedPermissions, state.grantedPermissions,
                        state.userState, userId, state,
                    ) ?: continue,
                )
            }
        }
        return ParceledListSlice(result)
    }

    override fun getInstalledApplications(flags: Long, userId: Int): ParceledListSlice<*> {
        val result = ArrayList<ApplicationInfo>()
        synchronized(lock) {
            for (state in packages.values) {
                result.add(
                    PackageInfoUtils.generateApplicationInfo(
                        state.pkg, withDirectBoot(flags), state.userState, userId, state,
                    ) ?: continue,
                )
            }
        }
        return ParceledListSlice(result)
    }

    override fun getPackageUid(packageName: String?, flags: Long, userId: Int): Int =
        synchronized(lock) { packages[packageName]?.appId ?: -1 }

    override fun getPackagesForUid(uid: Int): Array<String>? = synchronized(lock) {
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return arrayOf("android")
        packagesByUid[uid]?.let { arrayOf(it.packageName) }
    }

    override fun getNameForUid(uid: Int): String? = getPackagesForUid(uid)?.firstOrNull()

    override fun getNamesForUids(uids: IntArray?): Array<String>? =
        uids?.map { getNameForUid(it) ?: "" }?.toTypedArray()

    override fun getPackageGids(packageName: String?, flags: Long, userId: Int): IntArray =
        IntArray(0)

    private fun gidsFor(state: HostedPackageState): IntArray = IntArray(0)

    // ------------------------------------------------------- components

    /**
     * Enabled/direct-boot gate AOSP applies before returning a component from
     * an explicit lookup (source note: ComputerEngine.getActivityInfoInternal
     * etc. -> PackageUserStateUtils.isMatch). PackageInfoUtils.generate* does
     * not evaluate enabled state, so without this a component disabled through
     * setComponent/ApplicationEnabledSetting would still resolve. Requires `lock`.
     */
    private fun <T : android.content.pm.ComponentInfo> matchedOrNull(
        state: HostedPackageState,
        info: T?,
        flags: Long,
    ): T? {
        if (info == null) return null
        return if (com.android.server.pm.pkg.PackageUserStateUtils.isMatch(
                state.userState, info, flags,
            )
        ) {
            info
        } else {
            null
        }
    }

    override fun getActivityInfo(className: ComponentName?, flags: Long, userId: Int): ActivityInfo? {
        val component = className ?: return null
        synchronized(lock) {
            val activity = componentResolver.getActivity(component) ?: return null
            val state = packages[activity.packageName] ?: return null
            return matchedOrNull(
                state,
                PackageInfoUtils.generateActivityInfo(
                    state.pkg, activity, withDirectBoot(flags), state.userState, userId, state,
                ),
                withDirectBoot(flags),
            )
        }
    }

    override fun getReceiverInfo(className: ComponentName?, flags: Long, userId: Int): ActivityInfo? {
        val component = className ?: return null
        synchronized(lock) {
            val receiver = componentResolver.getReceiver(component) ?: return null
            val state = packages[receiver.packageName] ?: return null
            return matchedOrNull(
                state,
                PackageInfoUtils.generateActivityInfo(
                    state.pkg, receiver, withDirectBoot(flags), state.userState, userId, state,
                ),
                withDirectBoot(flags),
            )
        }
    }

    override fun getServiceInfo(className: ComponentName?, flags: Long, userId: Int): ServiceInfo? {
        val component = className ?: return null
        synchronized(lock) {
            val service = componentResolver.getService(component) ?: return null
            val state = packages[service.packageName] ?: return null
            return matchedOrNull(
                state,
                PackageInfoUtils.generateServiceInfo(
                    state.pkg, service, withDirectBoot(flags), state.userState, userId, state,
                ),
                withDirectBoot(flags),
            )
        }
    }

    override fun getProviderInfo(className: ComponentName?, flags: Long, userId: Int): ProviderInfo? {
        val component = className ?: return null
        synchronized(lock) {
            val provider = componentResolver.getProvider(component) ?: return null
            val state = packages[provider.packageName] ?: return null
            val app = PackageInfoUtils.generateApplicationInfo(
                state.pkg, withDirectBoot(flags), state.userState, userId, state,
            ) ?: return null
            return matchedOrNull(
                state,
                PackageInfoUtils.generateProviderInfo(
                    state.pkg, provider, withDirectBoot(flags), state.userState, app, userId, state,
                ),
                withDirectBoot(flags),
            )
        }
    }

    // ------------------------------------------------------- resolution

    /** Explicit-component fast path shared by the query methods (source note:
     * ComputerEngine.queryIntentActivitiesInternal component branch). */
    private fun componentMatch(info: Any?): List<ResolveInfo> {
        if (info == null) return emptyList()
        val resolve = ResolveInfo()
        when (info) {
            is ActivityInfo -> resolve.activityInfo = info
            is ServiceInfo -> resolve.serviceInfo = info
            is ProviderInfo -> resolve.providerInfo = info
        }
        return listOf(resolve)
    }

    private fun queryActivitiesInternal(
        intent: Intent,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): List<ResolveInfo> {
        intent.component?.let {
            return componentMatch(getActivityInfo(it, flags, userId))
        }
        synchronized(lock) {
            val pkg = intent.`package`
            if (pkg != null) {
                val state = packages[pkg] ?: return emptyList()
                return componentResolver.queryActivities(
                    this, intent, resolvedType, withDirectBoot(flags), state.pkg.activities, userId,
                ) ?: emptyList()
            }
            return componentResolver.queryActivities(this, intent, resolvedType, withDirectBoot(flags), userId)
                ?: emptyList()
        }
    }

    /** ActivityStarter's package-manager lookup, shared with the hosted task service. */
    internal fun resolveActivityInfo(intent: Intent, resolvedType: String?): ActivityInfo? =
        queryActivitiesInternal(intent, resolvedType, 0L, UserHandle.USER_SYSTEM)
            .firstOrNull()
            ?.activityInfo

    override fun queryIntentActivities(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ParceledListSlice<*> =
        ParceledListSlice(intent?.let { queryActivitiesInternal(it, resolvedType, flags, userId) } ?: emptyList<ResolveInfo>())

    override fun queryIntentReceivers(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ParceledListSlice<*> {
        if (intent == null) return ParceledListSlice(emptyList<ResolveInfo>())
        intent.component?.let {
            return ParceledListSlice(componentMatch(getReceiverInfo(it, flags, userId)))
        }
        synchronized(lock) {
            val pkg = intent.`package`
            if (pkg != null) {
                val state = packages[pkg] ?: return ParceledListSlice(emptyList<ResolveInfo>())
                return ParceledListSlice(
                    componentResolver.queryReceivers(
                        this, intent, resolvedType, withDirectBoot(flags), state.pkg.receivers, userId,
                    ) ?: emptyList(),
                )
            }
            return ParceledListSlice(
                componentResolver.queryReceivers(this, intent, resolvedType, withDirectBoot(flags), userId)
                    ?: emptyList(),
            )
        }
    }

    private fun queryServicesInternal(
        intent: Intent,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): List<ResolveInfo> {
        intent.component?.let {
            return componentMatch(getServiceInfo(it, flags, userId))
        }
        synchronized(lock) {
            val pkg = intent.`package`
            if (pkg != null) {
                val state = packages[pkg] ?: return emptyList()
                return componentResolver.queryServices(
                    this, intent, resolvedType, withDirectBoot(flags), state.pkg.services, userId,
                ) ?: emptyList()
            }
            return componentResolver.queryServices(this, intent, resolvedType, withDirectBoot(flags), userId)
                ?: emptyList()
        }
    }

    override fun queryIntentServices(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ParceledListSlice<*> =
        ParceledListSlice(intent?.let { queryServicesInternal(it, resolvedType, flags, userId) } ?: emptyList<ResolveInfo>())

    override fun queryIntentContentProviders(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ParceledListSlice<*> {
        if (intent == null) return ParceledListSlice(emptyList<ResolveInfo>())
        intent.component?.let {
            return ParceledListSlice(componentMatch(getProviderInfo(it, flags, userId)))
        }
        synchronized(lock) {
            return ParceledListSlice(
                componentResolver.queryProviders(this, intent, resolvedType, withDirectBoot(flags), userId)
                    ?: emptyList(),
            )
        }
    }

    override fun resolveIntent(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ResolveInfo? {
        if (intent == null) return null
        // The resolver returns the list priority-sorted; first is best
        // (source note: ComputerEngine.chooseBestActivity, reduced).
        return queryActivitiesInternal(intent, resolvedType, flags, userId).firstOrNull()
    }

    override fun resolveService(
        intent: Intent?,
        resolvedType: String?,
        flags: Long,
        userId: Int,
    ): ResolveInfo? {
        if (intent == null) return null
        return queryServicesInternal(intent, resolvedType, flags, userId).firstOrNull()
    }

    // android 16: uid-scoped provider resolution; the hosted session has a
    // single app uid, so visibility equals the caller's own resolve.
    override fun resolveContentProviderForUid(authority: String?, flags: Long, userId: Int, callingUid: Int): ProviderInfo? =
        resolveContentProvider(authority, flags, userId)

    override fun resolveContentProvider(name: String?, flags: Long, userId: Int): ProviderInfo? {
        val authority = name ?: return null
        synchronized(lock) {
            return componentResolver.queryProvider(this, authority, withDirectBoot(flags), userId)
        }
    }

    override fun queryContentProviders(
        processName: String?,
        uid: Int,
        flags: Long,
        metaDataKey: String?,
    ): ParceledListSlice<ProviderInfo> = synchronized(lock) {
        // ComputerEngine.queryContentProviders: resolve the process/UID, filter
        // enabled components, then initialize larger initOrder values first.
        val userId = if (processName != null) UserHandle.getUserId(uid) else UserHandle.getCallingUserId()
        val effectiveFlags = withDirectBoot(flags)
        val matches = componentResolver.queryProviders(this, processName, metaDataKey,
            uid, effectiveFlags, userId) ?: emptyList<ProviderInfo>()
        ParceledListSlice(matches.filter { provider ->
            com.android.server.pm.pkg.PackageStateUtils.isEnabledAndMatches(
                packages[provider.packageName], provider, effectiveFlags, userId)
        }.sortedByDescending { it.initOrder })
    }

    // ------------------------------------------------------- permissions

    /** PermissionService metadata projection over the installed parsed definitions.
     * This host has one visible user and does not install instant applications. */
    fun permissionInfo(name: String?, opPackageName: String?, flags: Int): PermissionInfo? =
        synchronized(lock) {
            val definition = permissionDefs[name] ?: return null
            val info = PackageInfoUtils.generatePermissionInfo(definition.permission, flags.toLong())
                ?: return null
            info.flags = info.flags or PermissionInfo.FLAG_INSTALLED
            val uid = Binder.getCallingUid()
            val targetSdk = if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID)
                android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
            else packages[opPackageName]?.pkg?.targetSdkVersion ?: android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
            if (targetSdk < android.os.Build.VERSION_CODES.O && info.protection != PermissionInfo.PROTECTION_SIGNATURE) {
                info.protectionLevel = info.protection
            }
            info
        }

    fun allPermissionGroups(flags: Int): List<PermissionGroupInfo> = synchronized(lock) {
        permissionGroups.values.mapNotNull { PackageInfoUtils.generatePermissionGroupInfo(it, flags.toLong()) }
    }

    fun permissionsByGroup(group: String?, flags: Int): List<PermissionInfo>? = synchronized(lock) {
        if (group != null && !permissionGroups.containsKey(group)) return null
        permissionDefs.values.filter { it.permission.group == group }.mapNotNull {
            PackageInfoUtils.generatePermissionInfo(it.permission, flags.toLong())?.apply {
                this.flags = this.flags or PermissionInfo.FLAG_INSTALLED
            }
        }
    }

    override fun checkPermission(permName: String?, pkgName: String?, userId: Int): Int {
        if (permName == null || pkgName == null) return PackageManager.PERMISSION_DENIED
        if (pkgName == "android") return PackageManager.PERMISSION_GRANTED
        synchronized(lock) {
            val state = packages[pkgName] ?: return PackageManager.PERMISSION_DENIED
            return if (state.grantedPermissions.contains(permName)) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
    }

    override fun checkUidPermission(permName: String?, uid: Int): Int {
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return PackageManager.PERMISSION_GRANTED
        }
        if (permName == null) return PackageManager.PERMISSION_DENIED
        synchronized(lock) {
            val state = packagesByUid[uid] ?: return PackageManager.PERMISSION_DENIED
            return if (state.grantedPermissions.contains(permName)) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
    }

    override fun getPackagesHoldingPermissions(
        permissions: Array<String>?,
        flags: Long,
        userId: Int,
    ): ParceledListSlice<*> {
        val wanted = permissions ?: return ParceledListSlice(emptyList<PackageInfo>())
        val result = ArrayList<PackageInfo>()
        synchronized(lock) {
            for (state in packages.values) {
                if (wanted.none { state.grantedPermissions.contains(it) }) continue
                result.add(
                    PackageInfoUtils.generate(
                        state.pkg, gidsFor(state), withDirectBoot(flags), 0, 0,
                        state.requestedPermissions, state.grantedPermissions,
                        state.userState, userId, state,
                    ) ?: continue,
                )
            }
        }
        return ParceledListSlice(result)
    }

    /**
     * Nonce bump after any mutation the client-side caches observe (source
     * note: PackageManagerService.onChanged / scheduleWritePackageRestrictions
     * paths call the same statics). Sets the cache_key.* system properties
     * through the broker; only this (system) process may.
     */
    private fun invalidateClientCaches() {
        PackageManager.invalidatePackageInfoCache()
        android.app.ApplicationPackageManager.invalidateGetPackagesForUidCache()
        android.app.ApplicationPackageManager.invalidateHasSystemFeatureCache()
    }

    private fun enforceSystem(what: String) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID) {
            throw SecurityException("uid " + callingUid + " may not " + what)
        }
    }

    override fun grantRuntimePermission(packageName: String?, permissionName: String?, userId: Int) {
        enforceSystem("grant permissions")
        if (packageName == null || permissionName == null) return
        synchronized(lock) {
            val state = packages[packageName] ?: return
            if (!state.requestedPermissions.contains(permissionName)) {
                throw SecurityException(
                    packageName + " does not request " + permissionName,
                )
            }
            state.grantedPermissions.add(permissionName)
        }
        invalidateClientCaches()
    }

    /** System-only revoke; not on IPackageManager (AOSP moved it to
     * PermissionManager) but the hosted permission client still needs it. */
    fun revokeRuntimePermission(packageName: String?, permissionName: String?) {
        enforceSystem("revoke permissions")
        if (packageName == null || permissionName == null) return
        synchronized(lock) {
            packages[packageName]?.grantedPermissions?.remove(permissionName)
        }
        invalidateClientCaches()
    }

    override fun getPermissionGroupInfo(name: String?, flags: Int): PermissionGroupInfo? =
        synchronized(lock) {
            permissionGroups[name]?.let {
                PackageInfoUtils.generatePermissionGroupInfo(it, flags.toLong())
            }
        }

    // ------------------------------------------------------ signatures

    override fun checkSignatures(pkg1: String?, pkg2: String?, userId: Int): Int {
        synchronized(lock) {
            val a = packages[pkg1] ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            val b = packages[pkg2] ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            return if (a.signingDetails.signaturesMatchExactly(b.signingDetails)) {
                PackageManager.SIGNATURE_MATCH
            } else {
                PackageManager.SIGNATURE_NO_MATCH
            }
        }
    }

    override fun checkUidSignatures(uid1: Int, uid2: Int): Int {
        synchronized(lock) {
            val a = packagesByUid[uid1] ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            val b = packagesByUid[uid2] ?: return PackageManager.SIGNATURE_UNKNOWN_PACKAGE
            return checkSignatures(a.packageName, b.packageName, 0)
        }
    }

    override fun hasSigningCertificate(packageName: String?, certificate: ByteArray?, flags: Int): Boolean =
        synchronized(lock) {
            val state = packages[packageName] ?: return false
            certificate != null && hasCertificate(state.signingDetails, certificate, flags)
        }

    override fun hasUidSigningCertificate(uid: Int, certificate: ByteArray?, flags: Int): Boolean =
        synchronized(lock) {
            val state = packagesByUid[uid] ?: return false
            certificate != null && hasCertificate(state.signingDetails, certificate, flags)
        }

    /** flags is PackageManager.CERT_INPUT_RAW_X509 or CERT_INPUT_SHA256. */
    private fun hasCertificate(details: SigningDetails, certificate: ByteArray, type: Int): Boolean =
        if (type == PackageManager.CERT_INPUT_SHA256) {
            details.hasSha256Certificate(certificate)
        } else {
            details.hasCertificate(certificate)
        }

    // ------------------------------------------------- enabled settings

    override fun setComponentEnabledSetting(
        componentName: ComponentName?,
        newState: Int,
        flags: Int,
        userId: Int,
        callingPackage: String?,
    ) {
        val component = componentName ?: return
        enforceOwnPackageOrSystem(component.packageName, "change component enabled state")
        synchronized(lock) {
            val state = packages[component.packageName] ?: return
            val enabled = android.util.ArraySet(state.userState.enabledComponents)
            val disabled = android.util.ArraySet(state.userState.disabledComponents)
            enabled.remove(component.className)
            disabled.remove(component.className)
            when (newState) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> enabled.add(component.className)
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                -> disabled.add(component.className)
            }
            state.userState.setEnabledComponents(enabled)
            state.userState.setDisabledComponents(disabled)
        }
        invalidateClientCaches()
        notifyPackageChanged(component.packageName, arrayListOf(component.className), flags, userId)
    }

    override fun getComponentEnabledSetting(componentName: ComponentName?, userId: Int): Int {
        val component = componentName ?: return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        synchronized(lock) {
            val state = packages[component.packageName]
                ?: return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            return when {
                state.userState.isComponentEnabled(component.className) ->
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                state.userState.isComponentDisabled(component.className) ->
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
        }
    }

    override fun setApplicationEnabledSetting(
        packageName: String?,
        newState: Int,
        flags: Int,
        userId: Int,
        callingPackage: String?,
    ) {
        enforceOwnPackageOrSystem(packageName, "change application enabled state")
        synchronized(lock) {
            packages[packageName]?.userState?.setEnabledState(newState)
        }
        invalidateClientCaches()
        if (packageName != null) notifyPackageChanged(packageName, arrayListOf(packageName), flags, userId)
    }

    /** AOSP allows a package to change its own components; anything else
     * needs CHANGE_COMPONENT_ENABLED_STATE (system here). */
    private fun enforceOwnPackageOrSystem(packageName: String?, what: String) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.ROOT_UID) return
        synchronized(lock) {
            if (packages[packageName]?.appId == callingUid) return
        }
        throw SecurityException("uid " + callingUid + " may not " + what + " of " + packageName)
    }

    override fun getApplicationEnabledSetting(packageName: String?, userId: Int): Int =
        synchronized(lock) {
            packages[packageName]?.userState?.enabledState
                ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

    // ------------------------------------------------- benign constants

    override fun checkPackageStartable(packageName: String?, userId: Int) {}
    override fun isProtectedBroadcast(actionName: String?): Boolean = false
    override fun isInstantApp(packageName: String?, userId: Int): Boolean = false
    override fun getInstallerPackageName(packageName: String?): String? = null
    /** The system features this host declares: app-widget hosting (the hosted IAppWidgetService). */
    override fun hasSystemFeature(name: String?, version: Int): Boolean = name in HOST_FEATURES && version <= 0
    override fun getSystemAvailableFeatures(): ParceledListSlice<*> =
        ParceledListSlice(HOST_FEATURES.map { android.content.pm.FeatureInfo().apply { this.name = it } })
    override fun getSystemSharedLibraryNames(): Array<String>? = null
    override fun isSafeMode(): Boolean = false
    override fun isFirstBoot(): Boolean = false
    override fun isDeviceUpgrading(): Boolean = false
    override fun isStorageLow(): Boolean = false
    override fun currentToCanonicalPackageNames(names: Array<String>?): Array<String>? = names
    override fun canonicalToCurrentPackageNames(names: Array<String>?): Array<String>? = names
    override fun notifyPackageUse(packageName: String?, reason: Int) {}
    override fun notifyDexLoad(
        loadingPackageName: String?,
        classLoaderContextMap: MutableMap<String, String>?,
        loaderIsa: String?,
    ) {}
    override fun getMimeGroup(packageName: String?, group: String?): MutableList<String>? = null
    override fun isPackageSuspendedForUser(packageName: String?, userId: Int): Boolean = false
    override fun isPackageQuarantinedForUser(packageName: String?, userId: Int): Boolean = false
    override fun isPackageStoppedForUser(packageName: String?, userId: Int): Boolean = false
    override fun isUidPrivileged(uid: Int): Boolean = uid == Process.SYSTEM_UID
    override fun getPrivateFlagsForUid(uid: Int): Int = 0
    override fun getFlagsForUid(uid: Int): Int = 0
    override fun getUidForSharedUser(sharedUserName: String?): Int = -1
    // AOSP PackageManagerService.get/setSplashScreenTheme: cross-user access,
    // owner-only writes, then the installed package's user-state value. This
    // service currently installs packages only for the hosted user (0).
    override fun getSplashScreenTheme(packageName: String?, userId: Int): String? {
        enforceSplashThemeUser(userId, "getSplashScreenTheme")
        if (userId != UserHandle.USER_SYSTEM) return null
        return synchronized(lock) { packages[packageName]?.userState?.splashScreenTheme }
    }

    // The pinned setter is annotated @NonNull although PMS and its nullable
    // Binder API pass null to clear the theme. Preserve that upstream operation.
    @Suppress("ARGUMENT_TYPE_MISMATCH", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun setSplashScreenTheme(packageName: String?, themeName: String?, userId: Int) {
        enforceSplashThemeUser(userId, "setSplashScreenTheme")
        val callingUid = Binder.getCallingUid()
        if (UserHandle.getAppId(callingUid) != Process.SYSTEM_UID &&
            getPackagesForUid(callingUid)?.contains(packageName) != true
        ) {
            throw SecurityException("Calling uid $callingUid does not own package $packageName")
        }
        if (userId != UserHandle.USER_SYSTEM) return
        synchronized(lock) { packages[packageName]?.userState?.setSplashScreenTheme(themeName) }
    }

    private fun enforceSplashThemeUser(userId: Int, operation: String) {
        require(userId >= 0) { "Invalid userId $userId" }
        val uid = Binder.getCallingUid()
        if (UserHandle.getUserId(uid) == userId || uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return
        if (checkUidPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid) == PackageManager.PERMISSION_GRANTED ||
            checkUidPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, uid) == PackageManager.PERMISSION_GRANTED
        ) return
        throw SecurityException("$operation requires cross-user permission for user $userId")
    }
    override fun getHarmfulAppWarning(packageName: String?, userId: Int): CharSequence? = null

    private companion object {
        val HOST_FEATURES: Set<String> = setOf(PackageManager.FEATURE_APP_WIDGETS, PackageManager.FEATURE_COMPANION_DEVICE_SETUP, PackageManager.FEATURE_WEBVIEW)

        const val TAG = "HostedPackageManager"
    }
}
