package dev.artzig.system

import android.os.UserHandle
import com.android.server.am.UserState

/**
 * UserController.finishUserBoot / finishUserUnlocking / finishUserUnlocked,
 * for the host's single user and already-available data directory. The host has
 * no vold/FBE unlock operation. Stock UserState owns transitions and progress;
 * SystemServiceManager receives the normal starting/unlocking/unlocked order.
 */
internal class HostedUserLifecycle {
    interface Services {
        fun starting(userId: Int)
        fun unlocking(userId: Int)
        fun unlocked(userId: Int)
    }

    private val lock = Any()
    private val user = UserState(UserHandle.SYSTEM)
    private var started = false

    /** The Binder and local user services share this session's user catalogue.
     * Return a fresh stock value so a caller cannot mutate the authority. */
    fun userInfo(userId: Int): android.content.pm.UserInfo? =
        if (userId != UserHandle.USER_SYSTEM) null else android.content.pm.UserInfo(
            UserHandle.USER_SYSTEM, "host", null,
            android.content.pm.UserInfo.FLAG_PRIMARY or android.content.pm.UserInfo.FLAG_ADMIN or
                android.content.pm.UserInfo.FLAG_FULL or android.content.pm.UserInfo.FLAG_SYSTEM or
                android.content.pm.UserInfo.FLAG_MAIN,
            android.os.UserManager.USER_TYPE_FULL_SYSTEM)

    fun isRunning(userId: Int): Boolean = synchronized(lock) {
        userId == UserHandle.USER_SYSTEM && user.state in
            UserState.STATE_BOOTING..UserState.STATE_RUNNING_UNLOCKED
    }

    /** UserController.isUserRunning flag precedence, over the same stock UserState. */
    fun isRunning(userId: Int, flags: Int): Boolean = synchronized(lock) {
        if (userId != UserHandle.USER_SYSTEM) return@synchronized false
        when {
            flags and android.app.ActivityManager.FLAG_OR_STOPPED != 0 -> true
            flags and android.app.ActivityManager.FLAG_AND_LOCKED != 0 ->
                user.state == UserState.STATE_BOOTING || user.state == UserState.STATE_RUNNING_LOCKED
            flags and android.app.ActivityManager.FLAG_AND_UNLOCKING_OR_UNLOCKED != 0 ->
                user.state == UserState.STATE_RUNNING_UNLOCKING || user.state == UserState.STATE_RUNNING_UNLOCKED
            flags and android.app.ActivityManager.FLAG_AND_UNLOCKED != 0 ->
                user.state == UserState.STATE_RUNNING_UNLOCKED
            else -> user.state != UserState.STATE_STOPPING && user.state != UserState.STATE_SHUTDOWN
        }
    }

    fun isUnlocked(userId: Int): Boolean = synchronized(lock) {
        userId == UserHandle.USER_SYSTEM && user.state == UserState.STATE_RUNNING_UNLOCKED
    }

    fun isUnlockingOrUnlocked(userId: Int): Boolean = synchronized(lock) {
        userId == UserHandle.USER_SYSTEM && (user.state == UserState.STATE_RUNNING_UNLOCKING ||
            user.state == UserState.STATE_RUNNING_UNLOCKED)
    }

    /** Returns false on a repeated start; a failed bootstrap is not partially replayed. */
    fun start(services: Services): Boolean {
        synchronized(lock) {
            if (started) return false
            started = true
        }
        // Do not hold the state lock across service callbacks: they can query
        // the IUserManager Binder from a different thread.
        services.starting(UserHandle.USER_SYSTEM)
        transition(UserState.STATE_BOOTING, UserState.STATE_RUNNING_LOCKED)
        user.mUnlockProgress.start()
        user.mUnlockProgress.setProgress(5)
        transition(UserState.STATE_RUNNING_LOCKED, UserState.STATE_RUNNING_UNLOCKING)
        user.mUnlockProgress.setProgress(20)
        services.unlocking(UserHandle.USER_SYSTEM)
        transition(UserState.STATE_RUNNING_UNLOCKING, UserState.STATE_RUNNING_UNLOCKED)
        user.mUnlockProgress.finish()
        services.unlocked(UserHandle.USER_SYSTEM)
        return true
    }

    private fun transition(before: Int, after: Int) = synchronized(lock) {
        check(user.setState(before, after)) { "Unexpected system-user state ${user.state}" }
    }
}
