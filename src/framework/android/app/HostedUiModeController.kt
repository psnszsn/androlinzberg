package android.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * In-process substitute for the UiModeManagerService application-night-mode
 * path. Android normally persists the package setting in system_server and
 * sends the resulting Configuration through ActivityThread. The hosted
 * process has neither service, so this module owns persistence, resource
 * selection, and the existing hosted Activity lifecycle seam.
 */
object HostedUiModeController {
    private const val PREFERENCES_PREFIX = "artzig-hosted-ui-mode-"
    private const val APPLICATION_NIGHT_MODE = "application-night-mode"
    private const val SYSTEM_NIGHT_MODE = "system-night-mode"

    @JvmStatic
    fun getSystemNightMode(context: Context?): Int =
        context
            ?.let(::preferences)
            ?.getInt(SYSTEM_NIGHT_MODE, UiModeManager.MODE_NIGHT_YES)
            ?.takeIf(::isValidNightMode)
            ?: UiModeManager.MODE_NIGHT_YES

    @JvmStatic
    fun getApplicationNightMode(context: Context): Int =
        preferences(context)
            .getInt(APPLICATION_NIGHT_MODE, UiModeManager.MODE_NIGHT_AUTO)
            .takeIf(::isValidNightMode)
            ?: UiModeManager.MODE_NIGHT_AUTO

    @JvmStatic
    fun setSystemNightMode(context: Context?, mode: Int) {
        requireValidNightMode(mode)
        if (context == null) return
        preferences(context).edit().putInt(SYSTEM_NIGHT_MODE, mode).apply()
        val applicationMode = getApplicationNightMode(context)
        if (applicationMode == UiModeManager.MODE_NIGHT_AUTO ||
            applicationMode == UiModeManager.MODE_NIGHT_CUSTOM
        ) {
            applyConfiguration(context, mode)
        }
    }

    @JvmStatic
    fun setApplicationNightMode(context: Context?, mode: Int) {
        requireValidNightMode(mode)
        if (context == null) return
        preferences(context).edit().putInt(APPLICATION_NIGHT_MODE, mode).apply()
        applyConfiguration(context, mode)
    }

    private fun applyConfiguration(context: Context, requestedMode: Int) {
        val resources = context.resources
        val current = Configuration(resources.configuration)
        val next = Configuration(current)
        val nightBits = resolveNightBits(context, requestedMode, current)
        next.uiMode = (next.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits

        val changes = current.diff(next)
        if (changes and ActivityInfo.CONFIG_UI_MODE == 0) return

        val metrics = resources.displayMetrics
        // AOSP ActivityThread.handleConfigurationChanged: the process-wide ResourcesManager applies it.
        ResourcesManager.getInstance().applyConfigurationToResources(next, null)
        ActivityThread.applyConfigurationInProcess(
            next,
            changes,
            metrics.widthPixels,
            metrics.heightPixels,
        )
    }

    private fun resolveNightBits(
        context: Context,
        requestedMode: Int,
        current: Configuration,
    ): Int = when (requestedMode) {
        UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
        UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
        UiModeManager.MODE_NIGHT_AUTO,
        UiModeManager.MODE_NIGHT_CUSTOM,
        -> when (getSystemNightMode(context)) {
            UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            else -> current.uiMode
                .and(Configuration.UI_MODE_NIGHT_MASK)
                .takeIf { it != Configuration.UI_MODE_NIGHT_UNDEFINED }
                ?: Configuration.UI_MODE_NIGHT_NO
        }
        else -> error("validated night mode became invalid: $requestedMode")
    }

    /** The per-package night-mode record (AOSP keeps it in UiModeManagerService's
     * settings). A system context has no package data directory (AOSP throws), so
     * the record lives under the system data directory instead. */
    private fun preferences(context: Context): SharedPreferences {
        val dataDir = try {
            context.dataDir
        } catch (e: RuntimeException) {
            android.os.Environment.getDataSystemDirectory()
        }
        val file = java.io.File(java.io.File(dataDir, "shared_prefs"), PREFERENCES_PREFIX + context.packageName + ".xml")
        return context.getSharedPreferences(file, Context.MODE_PRIVATE)
    }

    private fun requireValidNightMode(mode: Int) {
        require(isValidNightMode(mode)) { "Unknown night mode: $mode" }
    }

    private fun isValidNightMode(mode: Int): Boolean =
        mode == UiModeManager.MODE_NIGHT_AUTO ||
            mode == UiModeManager.MODE_NIGHT_NO ||
            mode == UiModeManager.MODE_NIGHT_YES ||
            mode == UiModeManager.MODE_NIGHT_CUSTOM
}
