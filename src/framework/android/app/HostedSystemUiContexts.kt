package android.app

import android.content.Context
import java.lang.ref.WeakReference

/**
 * Pinned ActivityThread.getSystemUiContext/getSystemUiContextNoCreateLocked and
 * onSystemUiContextCleanup. The hosted ActivityThread replaces Android process
 * bootstrap; ContextImpl and WindowTokenClient retain the stock display/token
 * attachment and resource lifecycle.
 */
internal class HostedSystemUiContexts {
    private val contexts = ArrayList<WeakReference<Context>>()

    @Synchronized
    fun get(systemContext: ContextImpl, displayId: Int): Context {
        contexts.removeAll { it.get() == null }
        return peek(displayId) ?: ContextImpl.createSystemUiContext(systemContext, displayId)
            .also { contexts.add(WeakReference(it)) }
    }

    @Synchronized
    fun peek(displayId: Int): Context? = contexts.firstNotNullOfOrNull {
        it.get()?.takeIf { context -> context.displayId == displayId }
    }

    @Synchronized
    fun release(context: Context) {
        contexts.removeAll { it.get().let { value -> value == null || value === context } }
    }
}
