package dev.artzig.systemtest

import dev.artzig.system.HostedUserLifecycle

internal object HostedUserLifecycleChecks {
    fun run() {
        val lifecycle = HostedUserLifecycle()
        val calls = mutableListOf<String>()
        check(lifecycle.isRunning(0) && !lifecycle.isUnlocked(0))
        check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_LOCKED))
        check(!lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_UNLOCKED))
        check(!lifecycle.isRunning(1) && !lifecycle.isUnlockingOrUnlocked(1))
        val states = mutableListOf<Int>()
        val services = object : HostedUserLifecycle.Services {
            override fun beforeStart(userId: Int) {
                check(userId == 0 && lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_LOCKED))
                calls += "beforeStart"
            }
            override fun beforeUnlock(userId: Int) {
                // UserController.finishUserUnlocking: user data is prepared while locked.
                check(userId == 0 && !lifecycle.isUnlockingOrUnlocked(0))
                calls += "beforeUnlock"
            }
            override fun stateChanged(userId: Int, state: Int) {
                check(userId == 0)
                states += state
            }
            override fun starting(userId: Int) {
                check(userId == 0 && lifecycle.isRunning(0) && !lifecycle.isUnlockingOrUnlocked(0))
                calls += "starting"
            }
            override fun unlocking(userId: Int) {
                check(userId == 0 && lifecycle.isUnlockingOrUnlocked(0) && !lifecycle.isUnlocked(0))
                calls += "unlocking"
                check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_UNLOCKING_OR_UNLOCKED))
                check(!lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_UNLOCKED))
            }
            override fun unlocked(userId: Int) {
                check(userId == 0 && lifecycle.isRunning(0) && lifecycle.isUnlocked(0))
                calls += "unlocked"
            }
        }
        check(lifecycle.start(services))
        check(calls == listOf("beforeStart", "starting", "beforeUnlock", "unlocking", "unlocked"))
        check(states == listOf(com.android.server.am.UserState.STATE_BOOTING, com.android.server.am.UserState.STATE_RUNNING_LOCKED,
            com.android.server.am.UserState.STATE_RUNNING_UNLOCKING, com.android.server.am.UserState.STATE_RUNNING_UNLOCKED))
        check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_UNLOCKED))
        check(!lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_LOCKED or
            android.app.ActivityManager.FLAG_AND_UNLOCKED))
        check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_OR_STOPPED or
            android.app.ActivityManager.FLAG_AND_LOCKED))
        check(!lifecycle.start(services) && calls.size == 5)
        println("User lifecycle: stock states, callback ordering and idempotent startup passed")
    }
}
