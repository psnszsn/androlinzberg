package dev.artzig.system

import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process as AndroidProcess
import android.os.UserHandle
import android.system.Os
import android.system.OsConstants
import android.system.UnixSocketAddress
import android.util.Log
import dev.artzig.BinderSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore

/** Pinned ProcessList -> ZygoteProcess -> ZygoteConnection ordering, with exec
 * replacing Android's fork specialization. The original Process client, parser,
 * ActivityThread entry and lifecycle transactions remain responsible for their
 * contracts. Prepared starts avoid re-entering AMS while it waits on its socket. */
internal class HostedZygoteService(
    private val packages: HostedPackageManagerService,
    private val dispatchExit: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val session = checkNotNull(System.getenv("AZK_BINDER_SESSION_DIR"))
    private val executable = checkNotNull(System.getenv("AZK_LAUNCHER_EXE"))
    private val children = CopyOnWriteArrayList<Child>()
    private val prepared = ConcurrentHashMap<Long, Prepared>()
    private val peers = CopyOnWriteArrayList<LocalSocket>()
    private val admission = Semaphore(16)
    private val policyLock = Any()
    private var exemptions = emptyArray<String>()
    private var logRate = 0
    private var statsRate = 0
    @Volatile private var closed = false
    private val socketFile = File(File(session, "sockets"), "zygote")
    private val server: LocalServerSocket

    private class Grant(credential: String) {
        private val parts = credential.split(':', limit = 3)
        init { check(parts.size == 3 && parts[2].length == 64) }
        val id = parts[0].toLong()
        val uid = parts[1].toInt()
        val secret = parts[2] // Never include credentials in diagnostics/toString.
    }
    private class Prepared(
        val request: HostedProcessStarts.Request<ApplicationInfo>,
        val info: ApplicationInfo,
        val grant: Grant,
        val onExit: () -> Unit,
    ) {
        @Volatile var child: Child? = null
    }
    private inner class Child(val process: java.lang.Process, val grant: Grant,
        override val identity: HostedProcessStarts.Identity) : HostedProcessStarts.Child {
        override fun terminate() {
            process.destroy()
            BinderSession.revokeLaunch(grant.id)
        }
    }

    init {
        check(socketFile.parentFile!!.isDirectory || socketFile.parentFile!!.mkdirs())
        val descriptor = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM or OsConstants.SOCK_CLOEXEC, 0)
        try {
            Os.bind(descriptor, UnixSocketAddress.createFileSystem(socketFile.path))
            Os.listen(descriptor, 16)
            server = LocalServerSocket(descriptor)
        } catch (failure: Throwable) { Os.close(descriptor); throw failure }
        Thread(::accept, "process-launch-listener").apply { isDaemon = true; start() }
        Runtime.getRuntime().addShutdownHook(Thread(::close, "process-launch-shutdown"))
    }

    fun serviceLauncher() = HostedProcessStarts.Launcher<ApplicationInfo> { request, onExit ->
        val info = request.metadata ?: packages.getApplicationInfo(request.key.packageName, 0, request.key.userId)
            ?: return@Launcher null
        val kind = if (request.key.isolated) BinderSession.LaunchKind.ISOLATED else BinderSession.LaunchKind.APP
        val grant = Grant(BinderSession.reserveLaunchForUid(request.key.packageName, request.key.processName,
            kind, request.sequence, request.key.userId, -1))
        val start = Prepared(request, ApplicationInfo(info), grant, onExit)
        check(prepared.putIfAbsent(request.sequence, start) == null)
        try {
            val result = AndroidProcess.start("android.app.ActivityThread", request.key.processName,
                grant.uid, grant.uid, null, 0, 0, info.targetSdkVersion, info.seInfo,
                info.primaryCpuAbi ?: Build.SUPPORTED_ABIS.first(), null, info.dataDir,
                null, request.key.packageName, AndroidProcess.ZYGOTE_POLICY_FLAG_EMPTY,
                false, null, null, null, false, false, false, arrayOf("seq=${request.sequence}"))
            checkNotNull(start.child).also { check(it.identity.pid == result.pid && !result.usingWrapper) }
        } catch (failure: Throwable) {
            start.child?.terminate()
            BinderSession.revokeLaunch(grant.id)
            Log.w(TAG, "Cannot launch ${request.key.processName}", failure)
            null
        } finally { prepared.remove(request.sequence, start) }
    }

    private fun accept() {
        while (!closed) {
            val peer = try { server.accept() } catch (failure: Exception) {
                if (!closed) Log.w(TAG, "Process launch listener closed", failure)
                return
            }
            if (!admission.tryAcquire()) { peer.close(); continue }
            peers.add(peer)
            try { Thread({
                try { serve(peer) }
                catch (failure: Exception) { if (!closed) Log.d(TAG, "Process launch connection ended: ${failure.javaClass.simpleName}") }
                finally { peers.remove(peer); peer.close(); admission.release() }
            }, "process-launch-client").apply { isDaemon = true; start() } }
            catch (failure: Throwable) { peers.remove(peer); peer.close(); admission.release(); throw failure }
        }
    }

    private fun serve(peer: LocalSocket) {
        if (!BinderSession.isSystemSocketPeer(peer.fileDescriptor)) return
        val input = BufferedInputStream(peer.inputStream)
        val output = DataOutputStream(peer.outputStream)
        while (!closed) {
            val arguments = HostedZygoteProtocol.read(input) ?: return
            if (!BinderSession.isSystemSocketPeer(peer.fileDescriptor)) return
            when {
                arguments.flag("mAbiListQuery") -> writeText(output, Build.SUPPORTED_ABIS.joinToString(","))
                arguments.flag("mPidQuery") -> writeText(output, AndroidProcess.myPid().toString())
                arguments.flag("mBootCompleted") -> {
                    dalvik.system.VMRuntime.bootCompleted()
                    output.writeInt(0)
                }
                arguments.flag("mUsapPoolStatusSpecified") -> {
                    // Exec has no pool of unspecialized fork children.
                    output.writeInt(if (arguments.flag("mUsapPoolEnabled")) -1 else 0)
                }
                arguments.value<Array<String>>("mApiDenylistExemptions") != null -> {
                    synchronized(policyLock) { exemptions = checkNotNull(arguments.value("mApiDenylistExemptions")) }
                    output.writeInt(0)
                }
                arguments.int("mHiddenApiAccessLogSampleRate") != -1 || arguments.int("mHiddenApiAccessStatslogSampleRate") != -1 -> {
                    synchronized(policyLock) {
                        if (arguments.int("mHiddenApiAccessLogSampleRate") != -1) logRate = arguments.int("mHiddenApiAccessLogSampleRate")
                        if (arguments.int("mHiddenApiAccessStatslogSampleRate") != -1) statsRate = arguments.int("mHiddenApiAccessStatslogSampleRate")
                    }
                    output.writeInt(0)
                }
                arguments.flag("mPreloadDefault") -> {
                    // System assets/fonts/module initialization already completed;
                    // exec children initialize their own VM instead of sharing pages.
                    output.writeInt(1)
                }
                else -> {
                    val child = try {
                        arguments.applyPolicy(peer.peerCredentials)
                        launch(arguments)
                    } catch (failure: Exception) {
                        Log.w(TAG, "Process launch rejected: ${failure.message}")
                        null
                    }
                    output.writeInt(child?.identity?.pid ?: -1)
                    output.writeBoolean(false)
                }
            }
            output.flush()
        }
    }

    private fun launch(arguments: HostedZygoteProtocol.Arguments): Child {
        require(arguments.long("mPermittedCapabilities") == 0L && arguments.long("mEffectiveCapabilities") == 0L)
        val uid = arguments.int("mUid")
        require(uid >= AndroidProcess.SYSTEM_UID && arguments.int("mGid") == uid)
        val entry = checkNotNull(arguments.value<Array<String>>("mRemainingArgs"))
        require(entry.isNotEmpty())
        if (arguments.flag("mStartChildZygote")) throw dev.artzig.ArtzigTestDiagnostics.unsupported(
            "HostedZygoteService", "startChildZygote", "Dedicated child-zygote exec setup is not configured")
        val packageName = arguments.value<String>("mPackageName") ?: if (uid == AndroidProcess.SYSTEM_UID) "android"
            else packages.getPackagesForUid(uid)?.singleOrNull() ?: throw IllegalArgumentException("No unique package for requested UID")
        val processName = arguments.value<String>("mNiceName") ?: packageName
        val sequence = entry.drop(1).lastOrNull { it.startsWith("seq=") }?.substring(4)?.toLong() ?: 0L
        val pending = if (sequence > 0) prepared[sequence] else null
        if (pending != null) {
            require(pending.request.key.packageName == packageName && pending.request.key.processName == processName &&
                pending.grant.uid == uid && entry[0] == "android.app.ActivityThread")
        }
        if (pending == null) {
            val users = android.os.IUserManager.Stub.asInterface(android.os.ServiceManager.getService(android.content.Context.USER_SERVICE))
            require(users.getUserInfo(UserHandle.getUserId(uid)) != null) { "Requested Android user does not exist" }
        }
        val info = pending?.info ?: packages.getApplicationInfo(packageName, 0, UserHandle.getUserId(uid))
            ?: throw IllegalArgumentException("Package is not installed for requested user")
        val kind = when {
            uid == AndroidProcess.SYSTEM_UID -> BinderSession.LaunchKind.SYSTEM
            AndroidProcess.isIsolated(uid) -> BinderSession.LaunchKind.ISOLATED
            else -> BinderSession.LaunchKind.APP
        }
        if (kind == BinderSession.LaunchKind.APP) require(info.uid == uid)
        validateSpecialization(arguments)
        val grant = pending?.grant ?: Grant(BinderSession.reserveLaunchForUid(packageName, processName,
            kind, sequence, UserHandle.getUserId(uid), uid))
        try {
            val config = synchronized(policyLock) {
                JSONObject().put("targetSdk", arguments.int("mTargetSdkVersion"))
                    .put("entry", JSONArray(entry.toList()))
                    .put("disabledCompat", JSONArray(arguments.value<LongArray>("mDisabledCompatChanges")?.toList() ?: emptyList<Long>()))
                    .put("exemptions", JSONArray(exemptions.toList()))
                    .put("logRate", logRate).put("statsRate", statsRate)
            }
            val child = spawn(pending?.request?.apkPath ?: info.sourceDir, packageName, processName,
                sequence, kind, grant, config) { pending?.onExit?.let(dispatchExit) }
            pending?.child = child
            return child
        } catch (failure: Throwable) { BinderSession.revokeLaunch(grant.id); throw failure }
    }

    private fun validateSpecialization(arguments: HostedZygoteProtocol.Arguments) {
        val unsupported = when {
            arguments.value<String>("mInvokeWith") != null -> "invoke-with wrappers"
            arguments.value<Any>("mRLimits") != null -> "per-child resource limits"
            arguments.value<IntArray>("mGids")?.isNotEmpty() == true -> "supplementary Android groups"
            arguments.int("mRuntimeFlags") != 0 -> "runtime specialization flags"
            arguments.int("mMountExternal") != 0 || arguments.flag("mBindMountAppDataDirs") ||
                arguments.flag("mBindMountAppStorageDirs") || arguments.flag("mBindMountSyspropOverrides") -> "Android mount specialization"
            arguments.value<Array<String>>("mPkgDataInfoList") != null || arguments.value<Array<String>>("mAllowlistedDataInfoList") != null -> "Android package data mount maps"
            arguments.value<String>("mInstructionSet")?.let { it != "x86_64" } == true -> "foreign instruction set"
            else -> return
        }
        throw dev.artzig.ArtzigTestDiagnostics.unsupported("HostedZygoteService", "specialize", "$unsupported is unavailable in the hosted exec runtime")
    }

    private fun spawn(apk: String, packageName: String, processName: String, sequence: Long,
        kind: BinderSession.LaunchKind, grant: Grant, config: JSONObject, onExit: () -> Unit): Child {
        check(!closed)
        val builder = ProcessBuilder(executable, "--join", session, "--runtime-process", apk)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT)
        val environment = builder.environment()
        environment.remove("AZK_BINDER_SYSTEM_COOKIE")
        environment.remove("AZK_BINDER_COOKIE")
        environment.remove("AZK_TEST_CLASS")
        environment["AZK_BINDER_PACKAGE"] = packageName
        environment["AZK_BINDER_ROLE"] = if (kind == BinderSession.LaunchKind.SYSTEM) "system" else "app"
        environment["AZK_LAUNCH_CREDENTIAL"] = grant.secret
        environment["AZK_LAUNCH_UID"] = grant.uid.toString()
        environment["AZK_APP_PROCESS_NAME"] = processName
        environment["AZK_APP_START_SEQUENCE"] = sequence.toString()
        // A valid bounded Zygote command can exceed Linux's per-environment-string
        // limit after JSON escaping. Send the setup through the authorization pipe.
        // ASCII also preserves supplementary Unicode through JNI's modified UTF-8.
        val payload = android.util.Base64.encode(config.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP)
        require(payload.size in 1..524288) { "Process setup exceeds transport limit" }
        val tunables = environment["GLIBC_TUNABLES"].orEmpty().split(':')
            .filter { it.isNotEmpty() && !it.startsWith("glibc.pthread.rseq=") }
        environment["GLIBC_TUNABLES"] = (tunables + "glibc.pthread.rseq=0").joinToString(":")
        val process = builder.start()
        val child: Child
        try {
            val pid = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
            BinderSession.bindLaunch(grant.id, pid)
            child = Child(process, grant, HostedProcessStarts.Identity(pid, grant.uid))
            children.add(child)
            DataOutputStream(process.outputStream).use {
                it.write(1)
                it.writeInt(payload.size)
                it.write(payload)
                it.flush()
            }
            Thread({
                process.waitFor()
                try { BinderSession.revokeLaunch(grant.id) }
                finally { children.remove(child); onExit() }
            }, "process-exit-$sequence").apply { isDaemon = true; start() }
        } catch (failure: Throwable) {
            process.destroy()
            children.removeAll { it.process === process }
            throw failure
        }
        return child
    }

    override fun close() {
        closed = true
        server.close()
        peers.forEach { runCatching { it.close() } }
        children.forEach { if (it.process.isAlive) it.process.destroy() }
        socketFile.delete()
    }

    private fun writeText(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
    private companion object { const val TAG = "HostedZygote" }
}
