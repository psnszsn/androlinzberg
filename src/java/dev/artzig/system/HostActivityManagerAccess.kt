package dev.artzig.system

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.util.Slog

/**
 * Pinned ActivityManagerService.checkComponentPermission/addPidLocked/removePidLocked,
 * ItemMatcher and scheduler helpers used by WMS. The host owns process attachment
 * in place of AMS/zygote. Keep permission denials tied to the process generation,
 * so a late death callback cannot clear a replacement process's restrictions.
 * Package and component policy still comes from the hosted package authority.
 */
object HostActivityManagerAccess {
    const val STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES
    // Pinned ProcessList constants consumed by WM; no Android LMKD is hosted.
    const val INVALID_ADJ = -10000
    const val SCHED_GROUP_TOP_APP = 3

    private class ProcessPermissions(val owner: IBinder, val denied: Set<String>)
    private val processes = HashMap<Int, ProcessPermissions>()

    internal fun attached(pid: Int, owner: IBinder, denied: Set<String>?) {
        synchronized(processes) {
            if (denied.isNullOrEmpty()) processes.remove(pid)
            else processes[pid] = ProcessPermissions(owner, denied.toSet())
        }
    }

    internal fun died(pid: Int, owner: IBinder) {
        synchronized(processes) {
            if (processes[pid]?.owner === owner) processes.remove(pid)
        }
    }

    @JvmStatic fun checkComponentPermission(
        permission: String?, pid: Int, uid: Int, owningUid: Int, exported: Boolean,
    ): Int {
        if (pid == Process.myPid()) return PackageManager.PERMISSION_GRANTED
        if (permission != null && synchronized(processes) {
                processes[pid]?.denied?.contains(permission) == true
            }) return PackageManager.PERMISSION_DENIED
        return HostedComponentPermissions.check(permission, uid, owningUid, exported)
    }

    @JvmStatic fun scheduleAsRegularPriority(tid: Int, suppressLogs: Boolean): Boolean =
        schedule(tid, Process.SCHED_OTHER, 0, suppressLogs)

    @JvmStatic fun scheduleAsFifoPriority(tid: Int, suppressLogs: Boolean): Boolean =
        schedule(tid, Process.SCHED_FIFO or Process.SCHED_RESET_ON_FORK, 1, suppressLogs)

    private fun schedule(tid: Int, policy: Int, priority: Int, suppressLogs: Boolean): Boolean {
        try {
            Process.setThreadScheduler(tid, policy, priority)
            return true
        } catch (error: IllegalArgumentException) {
            if (!suppressLogs) Slog.w("ActivityManager", "Failed to set scheduling policy, thread does not exist:\n$error")
        } catch (error: SecurityException) {
            if (!suppressLogs) Slog.w("ActivityManager", "Failed to set scheduling policy, not allowed:\n$error")
        }
        return false
    }

    /** Stock task-dump selection: exact components, hexadecimal identity, then substring. */
    class ItemMatcher {
        private val components = ArrayList<ComponentName>()
        private val strings = ArrayList<String>()
        private val objects = ArrayList<Int>()
        private var all = true

        fun build(name: String) {
            val component = ComponentName.unflattenFromString(name)
            if (component != null) components.add(component)
            else {
                val identity = name.toIntOrNull(16)
                if (identity != null) objects.add(identity) else strings.add(name)
            }
            all = false
        }

        fun build(args: Array<String>, start: Int): Int {
            var index = start
            while (index < args.size) {
                val name = args[index++]
                if (name == "--") return index
                build(name)
            }
            return index
        }

        fun match(obj: Any?, component: ComponentName?): Boolean {
            if (all || component in components || System.identityHashCode(obj) in objects) return true
            return strings.isNotEmpty() && strings.any { component!!.flattenToString().contains(it) }
        }
    }
}
