package dev.artzig.system

import android.os.CreateAppDataArgs
import android.os.CreateAppDataResult
import android.os.Environment
import android.os.IInstalld
import android.os.ParcelFileDescriptor
import android.os.ReconcileSdkDataArgs
import android.os.storage.CrateMetadata
import dev.artzig.ArtzigTestDiagnostics
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The installd daemon of a hosted session: the `IInstalld` Binder the stock
 * `com.android.server.pm.Installer` connects to. Pinned
 * frameworks/native/cmds/installd/InstalldNativeService.cpp creates, clears and
 * measures app data below /data with per-app Linux UIDs and SELinux labels.
 * Here every file belongs to the session's single host user and there is no
 * SELinux policy, so ownership and relabel requests have nothing to change;
 * the directory layout follows installd's `utils.cpp` paths under
 * `Environment.getDataDirectory()`. Only internal storage (null volume UUID)
 * exists. ART Service owns dexopt through artd, so the legacy dexopt entry
 * points and rollback data snapshots report a structured unsupported result.
 */
class HostedInstalld : IInstalld.Stub() {
    private val data: File get() = Environment.getDataDirectory()

    /** InstalldNativeService ENFORCE_UID(AID_SYSTEM): every IInstalld call
     * comes from system_server's Installer. */
    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) enforceSystemCaller("installd")
        return super.onTransact(code, data, reply, flags)
    }

    private fun unsupported(member: String, detail: String): UnsupportedOperationException =
        ArtzigTestDiagnostics.unsupported("installd", member, detail)

    private fun internal(uuid: String?, member: String) {
        if (uuid != null) throw unsupported(member, "Adoptable storage volume $uuid is not hosted")
    }

    // utils.cpp create_data_user_ce_path / create_data_user_de_path.
    private fun userCe(userId: Int) = File(data, "user/$userId")
    private fun userDe(userId: Int) = File(data, "user_de/$userId")
    private fun appCe(pkg: String, userId: Int) = File(userCe(userId), pkg)
    private fun appDe(pkg: String, userId: Int) = File(userDe(userId), pkg)
    private fun curProfiles(userId: Int, pkg: String) = File(data, "misc/profiles/cur/$userId/$pkg")
    private fun refProfiles(pkg: String) = File(data, "misc/profiles/ref/$pkg")

    private fun mkdirs(dir: File) {
        check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "cannot create $dir" }
    }

    private fun inode(file: File): Long =
        (Files.getAttribute(file.toPath(), "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong()

    private fun clearContents(dir: File, keep: (File) -> Boolean = { false }) {
        dir.listFiles()?.forEach { if (!keep(it)) it.deleteRecursively() }
    }

    /** Bytes of regular files below [file]; like installd's fts walk, symbolic
     * links (such as `<ce>/lib`) are counted as links, never followed. */
    private fun size(file: File): Long {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0L
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }

    // ------------------------------------------------------------- users

    override fun createUserData(uuid: String?, userId: Int, userSerial: Int, flags: Int) {
        internal(uuid, "createUserData")
        if (flags and FLAG_STORAGE_DE != 0) {
            mkdirs(userDe(userId))
            for (dir in listOf("misc_de/$userId", "system_de/$userId", "misc/profiles/cur/$userId")) mkdirs(File(data, dir))
        }
        if (flags and FLAG_STORAGE_CE != 0) {
            mkdirs(userCe(userId))
            for (dir in listOf("misc_ce/$userId", "system_ce/$userId")) mkdirs(File(data, dir))
        }
    }

    override fun destroyUserData(uuid: String?, userId: Int, flags: Int) {
        internal(uuid, "destroyUserData")
        if (flags and FLAG_STORAGE_DE != 0) {
            for (dir in listOf(userDe(userId), File(data, "misc_de/$userId"), File(data, "system_de/$userId"),
                File(data, "misc/profiles/cur/$userId"))) dir.deleteRecursively()
        }
        if (flags and FLAG_STORAGE_CE != 0) {
            for (dir in listOf(userCe(userId), File(data, "misc_ce/$userId"), File(data, "system_ce/$userId"))) dir.deleteRecursively()
        }
    }

    // First-boot bookkeeping only throttles installd's own I/O.
    override fun setFirstBoot() {}

    // ----------------------------------------------------------- app data

    override fun createAppData(args: CreateAppDataArgs): CreateAppDataResult {
        internal(args.uuid, "createAppData")
        val result = CreateAppDataResult()
        // InstalldNativeService::createAppDataLocked: package dir plus cache and code_cache.
        if (args.flags and FLAG_STORAGE_CE != 0) {
            val dir = appCe(args.packageName, args.userId)
            for (child in listOf(dir, File(dir, "cache"), File(dir, "code_cache"))) mkdirs(child)
            result.ceDataInode = inode(dir)
        }
        if (args.flags and FLAG_STORAGE_DE != 0) {
            val dir = appDe(args.packageName, args.userId)
            for (child in listOf(dir, File(dir, "cache"), File(dir, "code_cache"))) mkdirs(child)
            result.deDataInode = inode(dir)
            mkdirs(curProfiles(args.userId, args.packageName))
            mkdirs(refProfiles(args.packageName))
        }
        return result
    }

    override fun createAppDataBatched(args: Array<CreateAppDataArgs>): Array<CreateAppDataResult> =
        Array(args.size) { index ->
            try {
                createAppData(args[index])
            } catch (e: RuntimeException) {
                CreateAppDataResult().apply {
                    // binder::Status::EX_SERVICE_SPECIFIC, as installd reports per-argument failures.
                    exceptionCode = -8
                    exceptionMessage = e.message
                }
            }
        }

    override fun reconcileSdkData(args: ReconcileSdkDataArgs) {
        internal(args.uuid, "reconcileSdkData")
        if (args.subDirNames.isNullOrEmpty()) return
        throw unsupported("reconcileSdkData", "SDK sandbox data directories are not hosted")
    }

    // No SELinux policy exists on the host; there is no label to restore.
    override fun restoreconAppData(uuid: String?, packageName: String, userId: Int, flags: Int, appId: Int, seInfo: String?) =
        internal(uuid, "restoreconAppData")

    // Pre-N layout migration (/data/data to /data/user/0) does not apply to a hosted data tree.
    override fun migrateAppData(uuid: String?, packageName: String, userId: Int, flags: Int) =
        internal(uuid, "migrateAppData")

    override fun clearAppData(uuid: String?, packageName: String, userId: Int, flags: Int, ceDataInode: Long) {
        internal(uuid, "clearAppData")
        val dirs = buildList {
            if (flags and FLAG_STORAGE_CE != 0) add(appCe(packageName, userId))
            if (flags and FLAG_STORAGE_DE != 0) add(appDe(packageName, userId))
        }
        for (dir in dirs) when {
            flags and FLAG_CLEAR_CACHE_ONLY != 0 -> clearContents(File(dir, "cache"))
            flags and FLAG_CLEAR_CODE_CACHE_ONLY != 0 -> clearContents(File(dir, "code_cache"))
            // InstalldNativeService::clearAppData keeps the package directory itself.
            else -> clearContents(dir)
        }
        if (flags and (FLAG_CLEAR_CACHE_ONLY or FLAG_CLEAR_CODE_CACHE_ONLY or FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES) == 0 &&
            flags and FLAG_STORAGE_DE != 0) clearContents(curProfiles(userId, packageName))
    }

    override fun destroyAppData(uuid: String?, packageName: String, userId: Int, flags: Int, ceDataInode: Long) {
        internal(uuid, "destroyAppData")
        if (flags and FLAG_STORAGE_CE != 0) appCe(packageName, userId).deleteRecursively()
        if (flags and FLAG_STORAGE_DE != 0) {
            appDe(packageName, userId).deleteRecursively()
            curProfiles(userId, packageName).deleteRecursively()
        }
    }

    // Ownership is the session user's for every file; nothing to fix up.
    override fun fixupAppData(uuid: String?, flags: Int) = internal(uuid, "fixupAppData")

    // InstalldNativeService::getAppSize returns {codeSize, dataSize, cacheSize, externalCode, externalData, externalCache}.
    override fun getAppSize(uuid: String?, packageNames: Array<String>, userId: Int, flags: Int, appId: Int,
        ceDataInodes: LongArray?, codePaths: Array<String?>?): LongArray {
        internal(uuid, "getAppSize")
        var code = 0L
        var dataSize = 0L
        var cache = 0L
        codePaths?.filterNotNull()?.forEach { code += size(File(it)) }
        for (pkg in packageNames) for (dir in listOf(appCe(pkg, userId), appDe(pkg, userId))) {
            val dirCache = size(File(dir, "cache")) + size(File(dir, "code_cache"))
            dataSize += size(dir) - dirCache
            cache += dirCache
        }
        return longArrayOf(code, dataSize, cache, 0, 0, 0)
    }

    override fun getUserSize(uuid: String?, userId: Int, flags: Int, appIds: IntArray?): LongArray {
        internal(uuid, "getUserSize")
        val total = size(userCe(userId)) + size(userDe(userId))
        return longArrayOf(0, total, 0, 0, 0, 0)
    }

    // InstalldNativeService::getExternalSize returns {total, audio, video,
    // image, app, obb}; the hosted session measures no emulated external tree.
    override fun getExternalSize(uuid: String?, userId: Int, flags: Int, appIds: IntArray?): LongArray {
        internal(uuid, "getExternalSize")
        return LongArray(6)
    }

    override fun getAppCrates(uuid: String?, packageNames: Array<String>?, userId: Int): Array<CrateMetadata>? {
        internal(uuid, "getAppCrates")
        return emptyArray()
    }

    override fun getUserCrates(uuid: String?, userId: Int): Array<CrateMetadata>? {
        internal(uuid, "getUserCrates")
        return emptyArray()
    }

    // isQuotaSupported is false: installd ignores quotas on such filesystems.
    override fun setAppQuota(uuid: String?, userId: Int, appId: Int, cacheQuota: Long) = internal(uuid, "setAppQuota")

    override fun isQuotaSupported(uuid: String?): Boolean {
        internal(uuid, "isQuotaSupported")
        return false
    }

    override fun moveCompleteApp(fromUuid: String?, toUuid: String?, packageName: String, appId: Int, seInfo: String?,
        targetSdkVersion: Int, fromCodePath: String?): Nothing =
        throw unsupported("moveCompleteApp", "Moving apps between storage volumes is not hosted")

    // --------------------------------------------------------- code paths

    override fun rmPackageDir(packageName: String, packageDir: String) {
        // InstalldNativeService::rmPackageDir accepts only directories below /data/app.
        val dir = File(packageDir).canonicalFile
        val appRoot = Environment.getDataAppDirectory(null).canonicalFile
        require(dir.path.startsWith(appRoot.path + File.separator)) { "Invalid package dir $packageDir" }
        dir.deleteRecursively()
    }

    // InstalldNativeService::linkNativeLibraryDirectory: <ce data>/lib -> native library dir.
    override fun linkNativeLibraryDirectory(uuid: String?, packageName: String, nativeLibPath32: String, userId: Int) {
        internal(uuid, "linkNativeLibraryDirectory")
        val link = File(appCe(packageName, userId), "lib").toPath()
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, File(nativeLibPath32).toPath())
    }

    /** InstalldNativeService::freeCache: nothing to do when the data partition
     * already has [targetFreeBytes] free (unless DEFY_TARGET_FREE_BYTES);
     * otherwise app cache files are deleted, least recently modified first,
     * until the target is met. There are no per-app quotas to weigh here. */
    override fun freeCache(uuid: String?, targetFreeBytes: Long, flags: Int) {
        internal(uuid, "freeCache")
        val defyTarget = flags and FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES != 0
        fun satisfied() = !defyTarget && data.usableSpace >= targetFreeBytes
        if (satisfied() || flags and FLAG_FREE_CACHE_NOOP != 0) return
        val caches = listOf(File(data, "user"), File(data, "user_de")).flatMap { root ->
            root.listFiles().orEmpty().flatMap { user -> user.listFiles().orEmpty().map { app -> File(app, "cache") } }
        }
        val files = caches.flatMap { cache ->
            if (!Files.isDirectory(cache.toPath(), LinkOption.NOFOLLOW_LINKS)) emptyList()
            else Files.walk(cache.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }
        }.sortedBy { runCatching { Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L) }
        for (file in files) {
            if (satisfied()) return
            Files.deleteIfExists(file)
        }
    }

    // Mount mirrors and legacy OBB layouts belong to Android's vold namespace setup.
    override fun invalidateMounts() {}
    override fun tryMountDataMirror(volumeUuid: String?) = internal(volumeUuid, "tryMountDataMirror")
    override fun onPrivateVolumeRemoved(volumeUuid: String?) = internal(volumeUuid, "onPrivateVolumeRemoved")
    override fun migrateLegacyObbData() {}

    // InstalldNativeService::cleanupInvalidPackageDirs / utils.cpp
    // cleanup_invalid_package_dirs_under_path: below the user's CE and DE data
    // roots, remove directories renamed for deletion (==deleted== suffix) and
    // names that are not valid package names.
    override fun cleanupInvalidPackageDirs(uuid: String?, userId: Int, flags: Int) {
        internal(uuid, "cleanupInvalidPackageDirs")
        val roots = buildList {
            if (flags and FLAG_STORAGE_CE != 0) add(userCe(userId))
            if (flags and FLAG_STORAGE_DE != 0) add(userDe(userId))
        }
        for (root in roots) root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && (dir.name.endsWith("==deleted==") || !isValidPackageName(dir.name))) {
                dir.deleteRecursively()
            }
        }
    }

    /** utils.cpp is_valid_package_name (borrowed from PackageParser). */
    private fun isValidPackageName(name: String): Boolean {
        var front = true
        var index = 0
        while (index < name.length && name[index] != '-') {
            val c = name[index++]
            if (c in 'a'..'z' || c in 'A'..'Z') { front = false; continue }
            if (!front && (c in '0'..'9' || c == '_')) continue
            if (c == '.') { front = true; continue }
            return false
        }
        if (front) return false
        while (index < name.length) {
            val c = name[index++]
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '=') continue
            return false
        }
        return true
    }

    // ---------------------------------------------------------- profiles

    override fun prepareAppProfile(packageName: String, userId: Int, appId: Int, profileName: String, codePath: String,
        dexMetadata: String?): Boolean {
        mkdirs(curProfiles(userId, packageName))
        mkdirs(refProfiles(packageName))
        return true
    }

    override fun clearAppProfiles(packageName: String, profileName: String) {
        File(refProfiles(packageName), profileName).delete()
        File(data, "misc/profiles/cur").listFiles()?.forEach { File(File(it, packageName), profileName).delete() }
    }

    override fun destroyAppProfiles(packageName: String) {
        refProfiles(packageName).deleteRecursively()
        File(data, "misc/profiles/cur").listFiles()?.forEach { File(it, packageName).deleteRecursively() }
    }

    override fun deleteReferenceProfile(packageName: String, profileName: String) {
        File(refProfiles(packageName), profileName).delete()
    }

    // ------------------------------------------- legacy dexopt (ART Service owns it)

    private fun legacyDexopt(member: String): UnsupportedOperationException =
        unsupported(member, "Legacy installd dexopt is not hosted; ART Service dexopts through artd")

    override fun dexopt(apkPath: String?, uid: Int, packageName: String?, instructionSet: String?, dexoptNeeded: Int,
        outputPath: String?, dexFlags: Int, compilerFilter: String?, uuid: String?, sharedLibraries: String?,
        seInfo: String?, downgrade: Boolean, targetSdkVersion: Int, profileName: String?, dexMetadataPath: String?,
        compilationReason: String?): Boolean = throw legacyDexopt("dexopt")
    override fun controlDexOptBlocking(block: Boolean) {}
    override fun rmdex(codePath: String?, instructionSet: String?) = throw legacyDexopt("rmdex")
    override fun mergeProfiles(uid: Int, packageName: String?, profileName: String?): Int = throw legacyDexopt("mergeProfiles")
    override fun dumpProfiles(uid: Int, packageName: String?, profileName: String?, codePath: String?, dumpClassesAndMethods: Boolean): Boolean =
        throw legacyDexopt("dumpProfiles")
    override fun copySystemProfile(systemProfile: String?, uid: Int, packageName: String?, profileName: String?): Boolean =
        throw legacyDexopt("copySystemProfile")
    override fun createProfileSnapshot(appId: Int, packageName: String?, profileName: String?, classpath: String?): Boolean =
        throw legacyDexopt("createProfileSnapshot")
    override fun destroyProfileSnapshot(packageName: String?, profileName: String?) = throw legacyDexopt("destroyProfileSnapshot")
    // Hosted ART produces no install-time OAT directory; nothing to create, link or delete.
    override fun createOatDir(packageName: String?, oatDir: String?, instructionSet: String?) {}
    override fun linkFile(packageName: String?, relativePath: String?, fromBase: String?, toBase: String?) =
        throw legacyDexopt("linkFile")
    override fun moveAb(packageName: String?, apkPath: String?, instructionSet: String?, outputPath: String?) =
        throw legacyDexopt("moveAb")
    override fun deleteOdex(packageName: String?, apkPath: String?, instructionSet: String?, outputPath: String?): Long = 0
    override fun getOdexVisibility(packageName: String?, apkPath: String?, instructionSet: String?, outputPath: String?): Int = 0
    override fun reconcileSecondaryDexFile(dexPath: String?, pkgName: String?, uid: Int, isas: Array<String>?, volume_uuid: String?,
        storage_flag: Int): Boolean = throw legacyDexopt("reconcileSecondaryDexFile")
    override fun hashSecondaryDexFile(dexPath: String?, pkgName: String?, uid: Int, volumeUuid: String?, storageFlag: Int): ByteArray =
        throw legacyDexopt("hashSecondaryDexFile")

    // ---------------------------------------- rollback snapshots and fs-verity

    override fun snapshotAppData(uuid: String?, packageName: String?, userId: Int, snapshotId: Int, storageFlags: Int): Long =
        throw unsupported("snapshotAppData", "Rollback data snapshots are not hosted")
    override fun restoreAppDataSnapshot(uuid: String?, packageName: String?, appId: Int, seInfo: String?, user: Int, snapshotId: Int,
        storageflags: Int) = throw unsupported("restoreAppDataSnapshot", "Rollback data snapshots are not hosted")
    override fun destroyAppDataSnapshot(uuid: String?, packageName: String?, userId: Int, ceSnapshotInode: Long, snapshotId: Int,
        storageFlags: Int) = throw unsupported("destroyAppDataSnapshot", "Rollback data snapshots are not hosted")
    // No snapshot was ever created, so there is none to keep or destroy.
    override fun destroyCeSnapshotsNotSpecified(uuid: String?, userId: Int, retainSnapshotIds: IntArray?) =
        internal(uuid, "destroyCeSnapshotsNotSpecified")
    override fun createFsveritySetupAuthToken(authFd: ParcelFileDescriptor?, uid: Int): IInstalld.IFsveritySetupAuthToken =
        throw unsupported("createFsveritySetupAuthToken", "fs-verity is not hosted")
    override fun enableFsverity(authToken: IInstalld.IFsveritySetupAuthToken?, filePath: String?, packageName: String?): Int =
        throw unsupported("enableFsverity", "fs-verity is not hosted")
}

/** The daemons Android runs outside system_server accept only its uid. */
internal fun enforceSystemCaller(service: String) {
    val uid = android.os.Binder.getCallingUid()
    if (uid != android.os.Process.SYSTEM_UID) throw SecurityException("$service: uid $uid is not the system server")
}
