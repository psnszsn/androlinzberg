package dev.artzig.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.security.Flags
import android.util.Slog
import android.widget.Toast
import com.android.internal.util.FrameworkStatsLog
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Host process seam for pinned ActivityManagerService.IntentCreatorToken,
 * addCreatorToken and refreshIntentCreatorToken. AMS itself requires the Android
 * process supervisor; the host owns process attachment instead. Keep the stock
 * token key and local-Binder validation contract. A reference queue replaces the
 * AMS finalizer Handler without retaining tokens after their Binder owners die.
 */
class HostedIntentCreators(private val context: Context) {
    internal data class Key(
        val creatorUid: Int,
        val creatorPackage: String?,
        val action: String?,
        val data: Uri?,
        val type: String?,
        val targetPackage: String?,
        val component: ComponentName?,
        val flags: Int,
        val clipUris: List<Uri>?,
    ) {
        constructor(uid: Int, pkg: String?, intent: Intent) : this(
            uid, pkg, intent.action, intent.data, intent.type, intent.`package`,
            intent.component, intent.flags and Intent.IMMUTABLE_FLAGS,
            intent.clipData?.cloneOnlyUriItems()?.let { clip ->
                ArrayList<Uri>().also { clip.collectUris(it) }.takeIf { it.isNotEmpty() }
            },
        )
    }

    class Token internal constructor(internal val key: Key) : Binder() {
        val creatorUid: Int get() = key.creatorUid
        val creatorPackage: String? get() = key.creatorPackage
        companion object {
            @JvmStatic fun isValid(intent: Intent): Boolean = HostedIntentCreators.isValid(intent)
        }
    }

    private class TokenReference(token: Token, queue: ReferenceQueue<Token>) :
        WeakReference<Token>(token, queue) {
        val key = token.key
    }

    companion object {
        private const val TAG = "HostedIntentCreators"
        private val cache = HashMap<Key, TokenReference>()
        private val collected = ReferenceQueue<Token>()

        init {
            Thread({
                while (true) {
                    val ref = collected.remove() as TokenReference
                    synchronized(cache) {
                        // An old token's collection must not remove a replacement.
                        if (cache[ref.key] === ref) cache.remove(ref.key)
                    }
                }
            }, "IntentCreatorTokenCleaner").apply { isDaemon = true; start() }
        }

        @JvmStatic fun isValid(intent: Intent): Boolean {
            // A foreign process can instantiate the class, but its Binder comes
            // back here as a proxy. Only tokens owned by this process are valid.
            val token = intent.creatorToken as? Token ?: return false
            return token.key == Key(token.creatorUid, token.creatorPackage, intent)
        }

        private fun tokenFor(key: Key): Token = synchronized(cache) {
            cache[key]?.get() ?: Token(key).also {
                cache[key] = TokenReference(it, collected)
            }
        }

        internal fun create(intent: Intent, uid: Int, pkg: String?): Token? =
            if (isValid(intent)) null else tokenFor(Key(uid, pkg, intent))

        /** Caller must enforce INTERACT_ACROSS_USERS_FULL before entering. */
        internal fun refresh(intent: Intent): IBinder? {
            val token = intent.creatorToken as? Token ?: return null
            return tokenFor(Key(token.creatorUid, token.creatorPackage, intent))
        }
    }

    fun add(intent: Intent?, creatorPackage: String?) {
        if (!Flags.preventIntentRedirect() || intent == null) return
        val callingUid = Binder.getCallingUid()
        if (intent.extendedFlags and Intent.EXTENDED_FLAG_NESTED_INTENT_KEYS_COLLECTED == 0 &&
            intent.extras?.hasIntent() == true
        ) {
            val message = "The intent does not have its nested keys collected as a preparation " +
                "for creating intent creator tokens. Intent: $intent; creatorPackage: $creatorPackage"
            Slog.wtf(TAG, message)
            FrameworkStatsLog.write(FrameworkStatsLog.EXTRA_INTENT_KEYS_COLLECTED_ON_SERVER, callingUid)
            if (Flags.preventIntentRedirectShowToastIfNestedKeysNotCollectedRW()) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            if (Flags.preventIntentRedirectThrowExceptionIfNestedKeysNotCollected()) {
                throw SecurityException(message)
            }
            if (Flags.preventIntentRedirectCollectNestedKeysOnServerIfNotCollected()) {
                intent.collectExtraIntentKeys(true)
            }
        }
        val targetPackage = intent.component?.packageName ?: intent.`package`
        val sameCreator = creatorPackage != null && creatorPackage == targetPackage
        val uid = if (intent.extraIntentKeys.isNullOrEmpty()) -1 else callingUid
        intent.forEachNestedCreatorToken { nested ->
            if (sameCreator) {
                FrameworkStatsLog.write(FrameworkStatsLog.INTENT_CREATOR_TOKEN_ADDED, uid, true)
            } else {
                create(nested, uid, creatorPackage)?.let { token ->
                    nested.setCreatorToken(token)
                    FrameworkStatsLog.write(FrameworkStatsLog.INTENT_CREATOR_TOKEN_ADDED, uid, false)
                }
            }
        }
    }
}
