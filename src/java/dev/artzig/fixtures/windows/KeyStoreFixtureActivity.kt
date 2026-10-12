package dev.artzig.fixtures.windows

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An app's AndroidKeyStore keys: generated in KeyMint, used, listed and deleted. */
class KeyStoreFixtureActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Keystore ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, 60))
            addView(Button(this@KeyStoreFixtureActivity).apply {
                text = "Check keystore"
                setOnClickListener { isEnabled = false; verifyKeys() }
            })
        })
    }

    private fun verifyKeys() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretAlias = "fixture-aes"
        val pairAlias = "fixture-ec"
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(secretAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
            val key = store.getKey(secretAlias, null) as SecretKey
            val plain = "keystore2 round trip".toByteArray()
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = encrypt.doFinal(plain)
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv))
            }
            check(decrypt.doFinal(sealed).contentEquals(plain)) { "AES-GCM round trip" }

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(pairAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
            val entry = store.getEntry(pairAlias, null) as KeyStore.PrivateKeyEntry
            val certificate = entry.certificate as X509Certificate
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(entry.privateKey)
                update(plain)
            }.sign()
            check(Signature.getInstance("SHA256withECDSA").apply {
                initVerify(certificate.publicKey)
                update(plain)
            }.verify(signature)) { "EC signature verifies with the KeyMint certificate" }

            check(store.aliases().toList().containsAll(listOf(secretAlias, pairAlias))) { "aliases listed" }
        } finally {
            store.deleteEntry(secretAlias)
            store.deleteEntry(pairAlias)
        }
        check(!store.containsAlias(secretAlias) && !store.containsAlias(pairAlias)) { "keys deleted" }
        status.text = "Keystore keys verified"
        status.setBackgroundColor(Color.CYAN)
        Log.i("KeyStoreFixture", "app keys: AES-GCM round trip, EC signature over the KeyMint certificate, listing and deletion verified")
    }
}
