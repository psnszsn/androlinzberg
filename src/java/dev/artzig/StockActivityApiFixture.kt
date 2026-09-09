package dev.artzig

import android.app.Activity
import android.app.ActivityThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.IBinder

/** Direct-object fixtures use stock hidden entry points without adding methods to Activity. */
internal fun Activity.attach(base: Context, activityInfo: ActivityInfo? = null) {
    val info = activityInfo ?: ActivityInfo().apply {
        packageName = base.packageName
        name = this@attach.javaClass.name
        applicationInfo = base.applicationInfo
    }
    val launchIntent = intent ?: Intent()
    if (launchIntent.component == null) {
        launchIntent.component = ComponentName(base.packageName, info.name ?: javaClass.name)
    }
    val thread = ActivityThread.currentActivityThread()
    val contextType = Class.forName("android.app.ContextImpl")
    val contextToken = if (contextType.isInstance(base)) {
        contextType.getDeclaredMethod("getActivityToken").apply { isAccessible = true }.invoke(base) as? IBinder
    } else null
    val token = contextToken ?: android.os.Binder()
    val method = Activity::class.java.declaredMethods.single { it.name == "attach" && it.parameterCount == 20 }
    method.isAccessible = true
    try {
        method.invoke(this, base, thread, thread.instrumentation, token, 0,
            ActivityThread.currentApplication(), launchIntent, info, "", null, null, null,
            android.content.res.Configuration(base.resources.configuration), null, null,
            null, null, null, null, null)
    } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
}

private fun Activity.fixtureLifecycle(name: String, vararg arguments: Any?) {
    val method = Activity::class.java.declaredMethods.single {
        it.name == name && it.parameterCount == arguments.size
    }
    method.isAccessible = true
    try { method.invoke(this, *arguments) }
    catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
}

internal fun Activity.performCreate(state: android.os.Bundle?) = fixtureLifecycle("performCreate", state)
internal fun Activity.performSaveInstanceState(state: android.os.Bundle) = fixtureLifecycle("performSaveInstanceState", state)
// Test-only record setup/observations. Stock ActivityThread keeps these fields package-private;
// fixture access must not change their visibility in the framework.
private fun ActivityThread.ActivityClientRecord.fixtureField(name: String) =
    ActivityThread.ActivityClientRecord::class.java.getDeclaredField(name).apply { isAccessible = true }
internal var ActivityThread.ActivityClientRecord.fixtureActivity: Activity?
    get() = fixtureField("activity").get(this) as Activity?
    set(value) { fixtureField("activity").set(this, value) }
