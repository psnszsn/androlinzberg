package dev.artzig.systemtest

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import dev.artzig.system.HostedIntentCreators

/** Observable provenance fields from AMS.IntentCreatorToken, including AZKB ownership. */
internal object IntentCreatorChecks {
    private fun intent() = Intent("test.original").apply {
        setDataAndType(Uri.parse("content://original/1"), "test/type")
        `package` = "target"
        component = ComponentName("target", "target.Activity")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        clipData = ClipData.newRawUri("label", Uri.parse("content://clip/1"))
    }

    fun local(context: Context) {
        val original = intent()
        val token = checkNotNull(HostedIntentCreators.create(original, 10001, "creator"))
        check(token.creatorUid == 10001 && token.creatorPackage == "creator")
        original.setCreatorToken(token)
        check(HostedIntentCreators.isValid(original))
        check(HostedIntentCreators.create(original, 10002, "forwarder") == null)
        check(HostedIntentCreators.create(intent(), 10001, "creator") === token)
        check(HostedIntentCreators.create(intent(), 10002, "creator") !== token)
        check(HostedIntentCreators.create(intent(), 10001, "other") !== token)

        val changes: List<(Intent) -> Unit> = listOf(
            { it.action = "test.changed" },
            { it.setDataAndType(Uri.parse("content://changed/2"), it.type) },
            { it.setDataAndType(it.data, "test/changed") },
            { it.`package` = "other" },
            { it.component = ComponentName("other", "other.Activity") },
            { it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) },
            { it.clipData = ClipData.newRawUri("label", Uri.parse("content://changed/2")) },
        )
        for (change in changes) {
            // Intent's copy constructor shares CreatorTokenInfo. Use independent
            // metadata when checking replacement, so refreshing one case cannot
            // change the original token used by another case.
            val changed = intent().apply { setCreatorToken(token) }.also(change)
            check(!HostedIntentCreators.isValid(changed))
            val refreshed = checkNotNull(HostedIntentCreators.refresh(changed))
            check(refreshed !== token)
            check(HostedIntentCreators.refresh(changed) === refreshed)
            changed.setCreatorToken(refreshed)
            check(HostedIntentCreators.isValid(changed))
            check((refreshed as HostedIntentCreators.Token).creatorUid == 10001)
            check(refreshed.creatorPackage == "creator")
        }
        val mutable = Intent(original).apply {
            putExtra("extra", "changed")
            addCategory("test.category")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("changed label", Uri.parse("content://clip/1"))
        }
        check(HostedIntentCreators.isValid(mutable))
        val noClip = Intent("plain")
        noClip.setCreatorToken(checkNotNull(HostedIntentCreators.create(noClip, 10001, null)))
        noClip.clipData = ClipData.newPlainText("text", "not a URI")
        check(HostedIntentCreators.isValid(noClip))
        val fake = intent().apply { setCreatorToken(Binder()) }
        check(!HostedIntentCreators.isValid(fake) && HostedIntentCreators.refresh(fake) == null)

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.writeStrongBinder(token)
            parcel.setDataPosition(0)
            val restored = Intent.CREATOR.createFromParcel(parcel)
            if (!android.security.Flags.preventIntentRedirect()) {
                check(restored.creatorToken == null)
                restored.setCreatorToken(checkNotNull(parcel.readStrongBinder()))
            } else {
                check(parcel.readStrongBinder() === token)
            }
            check(restored.creatorToken === token && HostedIntentCreators.isValid(restored))
        } finally { parcel.recycle() }

        val nested = Intent("nested")
        val outer = Intent("outer").setPackage("receiver").putExtra("intent", nested)
        outer.collectExtraIntentKeys(true)
        HostedIntentCreators(context).add(outer, "creator")
        check(HostedIntentCreators.isValid(nested) == android.security.Flags.preventIntentRedirect())
        val own = Intent("own")
        val self = Intent("outer").setPackage("creator").putExtra("intent", own)
        self.collectExtraIntentKeys(true)
        HostedIntentCreators(context).add(self, "creator")
        check(own.creatorToken == null)
        println("IntentCreator: field validation, canonical identity, refresh and nested policy passed")
    }

    fun remote(service: dev.artzig.binder.IBinderTestService) {
        val sent = intent()
        val token = checkNotNull(HostedIntentCreators.create(sent, 10001, "creator"))
        sent.setCreatorToken(token)
        val returned = checkNotNull(service.roundTripBundle(Bundle().apply {
            putParcelable("creator-intent", sent)
            // Pinned Intent omits creator metadata when its aconfig flag is
            // disabled. Exercise Binder ownership independently in that mode.
            putBinder("creator-token", token)
        }))
        check(!returned.getBoolean("creator-valid-in-peer", true))
        val restored = checkNotNull(returned.getParcelable("creator-intent", Intent::class.java))
        if (!android.security.Flags.preventIntentRedirect()) {
            check(restored.creatorToken == null)
            restored.setCreatorToken(checkNotNull(returned.getBinder("creator-token")))
        }
        check(restored.creatorToken === token && HostedIntentCreators.isValid(restored))
        var denied = false
        try { android.app.ActivityManager.getService().refreshIntentCreatorToken(restored) }
        catch (_: SecurityException) { denied = true }
        check(denied) { "Token refresh must require INTERACT_ACROSS_USERS_FULL" }
        println("IntentCreator: foreign token rejected, owner recovered over AZKB, refresh permission enforced")
    }
}
