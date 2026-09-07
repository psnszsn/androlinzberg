package dev.artzig

import android.net.ipsec.ike.ChildSaProposal
import android.net.ipsec.ike.IkeSaProposal
import android.net.ipsec.ike.SaProposal
import android.net.ipsec.ike.SaProposal.*
import android.os.PersistableBundle
import com.android.internal.net.ipsec.ike.crypto.IkeCipher
import com.android.internal.net.ipsec.ike.crypto.IkeCombinedModeCipher
import com.android.internal.net.ipsec.ike.message.IkeSaPayload.EncryptionTransform
import javax.crypto.AEADBadTagException

/** Stock proposal policy, serialized configurations and pinned authenticated-cipher vectors. */
internal object StockIpSecProposalSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return
        }
        error("Expected ${T::class.java.name}")
    }

    private fun ike(keyLength: Int) = IkeSaProposal.Builder()
        .addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, keyLength)
        .addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
        .addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_256)
        .addDhGroup(DH_GROUP_2048_BIT_MODP)

    private fun proposals() {
        val offer = ike(KEY_LEN_AES_128)
            .addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_256)
            .addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_128)
            .build()
        check(offer.encryptionAlgorithms.size == 2)
        check(offer.encryptionAlgorithms.map { it.second } == listOf(KEY_LEN_AES_128, KEY_LEN_AES_256))
        val selected = ike(KEY_LEN_AES_256).build()
        check(selected.isNegotiatedFrom(offer) && !offer.isNegotiatedFrom(selected))
        check(!ike(KEY_LEN_AES_192).build().isNegotiatedFrom(offer))
        offer.encryptionAlgorithms.clear()
        check(offer.encryptionAlgorithms.size == 2)
        val restored = SaProposal.fromPersistableBundle(offer.toPersistableBundle())
        check(restored is IkeSaProposal && restored == offer && restored.hashCode() == offer.hashCode())
        check(restored.pseudorandomFunctions == listOf(PSEUDORANDOM_FUNCTION_SHA2_256))
        val child = ChildSaProposal.Builder()
            .addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256)
            .addIntegrityAlgorithm(INTEGRITY_ALGORITHM_NONE)
            .build()
        check(child.dhGroups.isEmpty())
        check(!child.isNegotiatedFrom(offer))
        check(SaProposal.fromPersistableBundle(child.toPersistableBundle()) == child)
        check(DH_GROUP_CURVE_25519 in SaProposal.getSupportedDhGroups())
        val supported = SaProposal.getSupportedDhGroups()
        supported.clear()
        check(SaProposal.getSupportedDhGroups().isNotEmpty())

        rejects<IllegalArgumentException> { IkeSaProposal.Builder().build() }
        rejects<IllegalArgumentException> { ike(129).build() }
        rejects<IllegalArgumentException> {
            IkeSaProposal.Builder().addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_128)
                .addDhGroup(DH_GROUP_2048_BIT_MODP).addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_256).build()
        }
        rejects<IllegalArgumentException> { ike(KEY_LEN_AES_128).addDhGroup(DH_GROUP_NONE).build() }
        rejects<IllegalArgumentException> {
            ike(KEY_LEN_AES_128).addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128)
        }
        rejects<IllegalArgumentException> {
            IkeSaProposal.Builder().addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128)
                .addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_256)
                .addDhGroup(DH_GROUP_2048_BIT_MODP).build()
        }
        rejects<IllegalArgumentException> { SaProposal.fromPersistableBundle(PersistableBundle()) }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun authenticatedCipher() {
        // Pinned IPsec IkeCombinedModeCipherTest.testAesGcmEncrypt/testAesGcmDecrypt vector.
        val key = hex("7C04513660DEC572D896105254EF92608054F8E6EE19E79CE52AB8697B2B5F2C2AA90C29")
        val iv = hex("fbd69d9de2dafc5e")
        val cleartext = hex("000000080000400f00")
        val additional = hex("77c708b4523e39a471dc683c1d4f21362e202508000000060000004129000025")
        val ciphertext = hex("f4109834e9f3559758c05edf119917521b885f67f0d14ced43")
        fun cipher() = IkeCipher.create(EncryptionTransform(
            ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256)) as IkeCombinedModeCipher
        check(cipher().encrypt(cleartext, additional, key, iv).contentEquals(ciphertext))
        check(cipher().decrypt(ciphertext, additional, key, iv).contentEquals(cleartext))
        val tampered = ciphertext.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        rejects<AEADBadTagException> { cipher().decrypt(tampered, additional, key, iv) }
        val wrongAdditional = additional.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        rejects<AEADBadTagException> { cipher().decrypt(ciphertext, wrongAdditional, key, iv) }
    }

    fun run() {
        proposals()
        authenticatedCipher()
        println("stock IPsec proposals, negotiation, bundles and authenticated cipher: PASS")
    }
}
