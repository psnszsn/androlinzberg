package dev.artzig

import android.os.Looper

/**
 * Entry class of `androlinzberg --app-process <apk>`: a headless app process
 * that hosts one package's services through the session's activity manager.
 * The activity manager tells it to exit (`scheduleExit`) once nothing is
 * bound or started.
 */
internal object AppProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        ApkHost.initHeadless()
        Looper.loop()
    }
}
