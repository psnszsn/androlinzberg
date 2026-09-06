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
        val services = object : HostedUserLifecycle.Services {
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
        check(calls == listOf("starting", "unlocking", "unlocked"))
        check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_UNLOCKED))
        check(!lifecycle.isRunning(0, android.app.ActivityManager.FLAG_AND_LOCKED or
            android.app.ActivityManager.FLAG_AND_UNLOCKED))
        check(lifecycle.isRunning(0, android.app.ActivityManager.FLAG_OR_STOPPED or
            android.app.ActivityManager.FLAG_AND_LOCKED))
        check(!lifecycle.start(services) && calls.size == 3)
        println("User lifecycle: stock states, callback ordering and idempotent startup passed")
    }
}
