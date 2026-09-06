package dev.artzig.system

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import dev.artzig.ArtzigTestDiagnostics

/**
 * AOSP AssistUtils.showSessionForActiveServiceInternal, used after the stock
 * ActivityClientController's top/visible-activity checks. The hosted session
 * has no voice-interaction manager or microphone/hotword service stack. Retain
 * the stock absent-service result and capture service availability at systemReady.
 */
class HostedAssistSessions internal constructor(private val manager: IBinder?) {
    constructor() : this(ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE))

    fun showFromActivity(args: Bundle?, attributionTag: String?, activityToken: IBinder?): Boolean {
        if (manager == null) return false
        throw ArtzigTestDiagnostics.unsupported("AssistUtils", "showSessionForActiveService",
            "A voice-interaction manager is published, but its hosted session adapter is unavailable")
    }
}
